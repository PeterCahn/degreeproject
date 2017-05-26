import sbtassembly.AssemblyKeys
import sbtassembly.AssemblyPlugin.autoImport._

name := "spark_ignite"

version := "1.0"

val spark = "org.apache.spark" %% "spark-core" % "2.1.0"
val ignite = "org.apache.ignite" % "ignite-core" % "2.0.0"
val ignite_spark = "org.apache.ignite" % "ignite-spark_2.10" % "2.0.0" excludeAll(ExclusionRule(organization="org.json4s"))
val spring = "org.apache.ignite" % "ignite-spring" % "2.0.0"
val indexing = "org.apache.ignite" % "ignite-indexing" % "2.0.0"
val log = "org.apache.ignite" % "ignite-log4j" % "2.0.0"
val scalar = "org.apache.ignite" % "ignite-scalar_2.10" % "2.0.0"

lazy val commonSettings = Seq(
  organization := "com.knoldus",
  version := "0.1.0",
  scalaVersion := "2.10.4"
)

lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  settings(
    name := "spark-ignite",
    libraryDependencies ++= Seq(spark, ignite, ignite_spark, spring, indexing, log),
    mainClass in assembly := Some("com.knoldus.SparkIgniteAppOne")
  )
mergeStrategy in assembly := {
  case m if m.toLowerCase.endsWith("manifest.mf") => MergeStrategy.discard
  case m if m.toLowerCase.matches("meta-inf.*\\.sf$") => MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  case _ => MergeStrategy.first
}
assemblyShadeRules in assembly := Seq(
  ShadeRule.rename("org.apache.commons.io.**" -> "shadeio.@1").inAll,
  ShadeRule.rename("com.esotericsoftware.kryo.**" -> "shadekio.@1").inAll
)
