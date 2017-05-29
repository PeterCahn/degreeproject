//import AssemblyKeys._

name         := "Thesis01"

version      := "1.0"

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
    "org.apache.spark" % "spark-streaming_2.11" % "2.1.1",
    "org.apache.spark" % "spark-streaming-kafka-0-10_2.11" % "2.1.0",
    "org.apache.spark" % "spark-core_2.11" % "2.1.1" % "provided",
    "org.apache.kafka" % "kafka_2.11" % "0.10.0.0"
)

resolvers ++= Seq(
    "Maven Central" at "http://repo1.maven.org/maven2/",
    "Akka Repository" at "http://repo.akka.io/releases/"
)

