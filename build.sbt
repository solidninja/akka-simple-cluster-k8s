name := "akka-simple-cluster-k8s"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.13.1"

enablePlugins(JavaServerAppPackaging, DockerPlugin)

Global / onChangedBuildSource := ReloadOnSourceChanges

val akkaVersion = "2.6.0"
val akkaHttpVersion = "10.1.10"
val akkaManagementVersion = "1.0.5"

libraryDependencies ++=Seq(
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-http-core" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % akkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management" % akkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaManagementVersion,
//  "com.lightbend.akka.discovery" %% "akka-discovery-dns" % akkaManagementVersion,
)

dockerBaseImage := "adoptopenjdk:11"
dockerUsername := Some("softwaremill")
