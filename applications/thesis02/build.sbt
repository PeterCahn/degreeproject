//import AssemblyKeys._

name         := "AverageApp"

version      := "1.0"

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
    "org.apache.spark" % "spark-streaming_2.11" % "2.1.1",
    "org.apache.spark" % "spark-streaming-kafka-0-10_2.11" % "2.1.1",
    "org.apache.spark" % "spark-core_2.11" % "2.1.1" % "provided",
    "org.apache.kafka" % "kafka_2.11" % "0.10.0.0",
    "org.apache.spark" % "spark-sql_2.11" % "2.1.1" % "provided",
    "org.twitter4j" % "twitter4j-stream" % "3.0.6",
    "org.json" % "json" % "20171018",
    "org.apache.logging.log4j" % "log4j-core" % "2.9.1",
    "io.dropwizard.metrics" % "metrics-core" % "3.1.0",
    "com.codahale.metrics" % "metrics-graphite" % "3.0.2" % "compile",
    "ch.cern.sparkmeasure" % "spark-measure_2.11" % "0.11"
)

resolvers ++= Seq(
    "Maven Central" at "http://repo1.maven.org/maven2/",
    "Akka Repository" at "http://repo.akka.io/releases/"
)

