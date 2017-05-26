name         := "SparkMe Project"

version      := "1.0"

scalaVersion := "2.11.6"

libraryDependencies += "org.apache.spark" %% "spark-core" % "2.1.1"
libraryDependencies += "org.apache.spark" % "spark-streaming_2.11" % "2.1.1"
resolvers += "Akka Repository" at "http://repo.akka.io/releases/"
