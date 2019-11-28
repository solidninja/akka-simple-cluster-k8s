package is.solidninja.akka.downing.k8s

import java.nio.file.{Files, Paths}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, Props}
import akka.cluster.ClusterEvent.{ClusterDomainEvent, CurrentClusterState, UnreachableMember}
import akka.cluster.{Cluster, DowningProvider, Member}
import akka.event.Logging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.pattern.pipe
import com.typesafe.config.Config
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.TrustStoreConfig
import is.solidninja.akka.downing.k8s.KustomDowning.RemoveMembers
import spray.json.DefaultJsonProtocol

object KubernetesApi extends DefaultJsonProtocol {
  import spray.json._

  final case class PodList(kind: String, apiVersion: String, items: List[Pod])

  object PodList {
    implicit val podListFormat: RootJsonFormat[PodList] = jsonFormat3(PodList.apply)
  }

  final case class Pod(metadata: Metadata, status: PodStatus)

  object Pod {
    implicit val podFormat: JsonFormat[Pod] = jsonFormat2(Pod.apply)
  }

  final case class Metadata(name: String, namespace: String, labels: Map[String, String])

  object Metadata {
    implicit val metadataFormat: JsonFormat[Metadata] = jsonFormat3(Metadata.apply)
  }

  final case class PodStatus(phase: String, podIP: String, hostIP: String)

  object PodStatus {
    implicit val podStatusFormat: JsonFormat[PodStatus] = jsonFormat3(PodStatus.apply)
  }

}

trait KubernetesApiClient {
  def getPods(namespace: String, selectorLabel: (String, String)): Future[KubernetesApi.PodList]
}

object KubernetesApiClient extends SprayJsonSupport {
  import akka.http.scaladsl._
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.model.headers._
  import akka.http.scaladsl.unmarshalling.Unmarshal

  def apply(apiServerUrl: String, token: Option[String], apiCaPath: Option[String])(implicit as: ActorSystem): KubernetesApiClient =
    new KubernetesApiClient {
      implicit val ec: ExecutionContext = as.dispatcher
      val http = Http()(as)

      val connectionContext: HttpsConnectionContext = apiCaPath.map { caPath =>
        val trustStore = TrustStoreConfig(data = None, filePath = Some(caPath)).withStoreType("PEM")
        val c = AkkaSSLConfig()(as).mapSettings(s => s.withTrustManagerConfig(s.trustManagerConfig.withTrustStoreConfigs(Seq(trustStore))))
        http.createClientHttpsContext(c)
      }.getOrElse(http.defaultClientHttpsContext)

      val headers: Seq[HttpHeader] = token.map(t => Authorization(OAuth2BearerToken(t))).toSeq

      override def getPods(namespace: String, selectorLabel: (String, String)): Future[KubernetesApi.PodList] =
        http
          .singleRequest(
            HttpRequest(
              uri = Uri(s"$apiServerUrl/api/v1/namespaces/$namespace/pods")
                .withQuery(Uri.Query("labelSelector" -> s"${selectorLabel._1}=${selectorLabel._2}")),
              headers = headers
            ),
            connectionContext = connectionContext
          )
          .flatMap(Unmarshal(_).to[KubernetesApi.PodList])
    }

}

class KustomDowningProvider(system: ActorSystem) extends DowningProvider {
  val log = Logging(system, getClass)

  override def downRemovalMargin: FiniteDuration = Cluster(system).settings.DownRemovalMargin

  override def downingActorProps: Option[Props] = {
    val config = KustomDowningConfig.fromConfig(system.name, system.settings.config)
    log.info(s"Resolved config: $config")
    Some(KustomDowning.props(config))
  }
}

final case class KustomDowningConfig(
    apiServerUrl: String,
    apiServerToken: Option[String],
    apiCaPath: Option[String],
    namespace: String,
    selectorLabel: (String, String)
) {
  override def toString: String = s"Config(url=$apiServerUrl, token=${apiServerToken.map(_ => "***")}, " +
    s"caPath=$apiCaPath, namespace=$namespace, selectorLabel=$selectorLabel)"
}

object KustomDowningConfig {
  def fromConfig(systemName: String, config: Config): KustomDowningConfig =
    KustomDowningConfig(
      apiServerUrl = optionalString(config, "akka.discovery.kubernetes-api.api-server-url")
        .getOrElse("https://kubernetes.default.svc:443"),
      apiServerToken = optionalString(config, "akka.discovery.kubernetes-api.api-token")
        .orElse(
          Try(readFromFilesystem(
            path = optionalString(config, "akka.discovery.kubernetes-api.api-token-path")
              .getOrElse("/var/run/secrets/kubernetes.io/serviceaccount/token")
          )).toOption
      ),
      apiCaPath = optionalString(config, "akka.discovery.kubernetes-api.api-ca-path").orElse {
        val file = Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt").toFile
        if (file.canRead) Some(file.getPath) else None
      },
      namespace = optionalString(config, "akka.discovery.kubernetes-api.pod-namespace")
        .getOrElse(
          readFromFilesystem(
            path = optionalString(config, "akka.discovery.kubernetes-api.pod-namespace-path")
              .getOrElse("/var/run/secrets/kubernetes.io/serviceaccount/namespace")
          )
        ),
      selectorLabel = optionalString(config, "akka.discovery.kubernetes-api.pod-label-selector").getOrElse("app=%s").split('=') match {
        case Array(label, value) => label -> value.replaceAllLiterally("%s", systemName)
        case other => throw new IllegalArgumentException(s"Invalid pod label selector parts: ${other.mkString("=")}")
      }
    )

  private def optionalString(config: Config, path: String): Option[String] =
    if (config.hasPath(path)) Option(config.getString(path)) else None

  private def readFromFilesystem(path: String): String =
    new String(Files.readAllBytes(Paths.get(path)))
}

class KustomDowning(konfig: KustomDowningConfig) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = context.dispatcher
  val cluster = Cluster(context.system)
  val apiClient = KubernetesApiClient(konfig.apiServerUrl, konfig.apiServerToken, konfig.apiCaPath)(context.system)

  override def receive: Receive = {
    case state: CurrentClusterState =>
      log.info(s"Received cluster state: ${state}")
      if (state.unreachable.nonEmpty) {
        log.debug(s"Received unreachable nodes ${state.unreachable} in cluster state, checking")
        getPodIps().map(filterUnreachable(state.unreachable, _)).map(toRemoveMsg).pipeTo(self)
      }
    case UnreachableMember(m) =>
      log.info(s"Unreachable member: $m")
      getPodIps().map(filterUnreachable(Set(m), _)).map(toRemoveMsg).pipeTo(self)
    case KustomDowning.RemoveMembers(addresses) if addresses.nonEmpty =>
      log.info(s"Would remove members: ${addresses}")
      addresses.foreach { addr =>
        cluster.down(addr)
      }
  }

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[ClusterDomainEvent])
  }
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  private def getPodIps(): Future[Set[String]] =
    apiClient.getPods(konfig.namespace, konfig.selectorLabel).map { podList =>
      log.debug(s"Received pod list: ${podList}")
      val res = podList.items.collect {
        case KubernetesApi.Pod(_, status) if status.phase == "Running" => status.podIP
      }.toSet
      log.info(s"Found pod ips that are running: $res")
      res
    }

  private def filterUnreachable(unreachableMembers: Set[Member], runningPodIps: Set[String]): Set[Member] = {
    val res = unreachableMembers.filterNot(m => runningPodIps.contains(m.address.host.getOrElse(""))
      || m.address == cluster.selfAddress)
    if (res.nonEmpty)
      log.debug(s"Confirmed unreachable: ${res}")
    res
  }

  private def toRemoveMsg(unreachableMembers: Set[Member]): RemoveMembers =
    RemoveMembers(unreachableMembers.map(_.address).toList)
}

object KustomDowning {

  final case class RemoveMembers(nodes: List[Address])

  def props(config: KustomDowningConfig): Props = Props(classOf[KustomDowning], config)
}
