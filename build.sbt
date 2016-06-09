import com.trueaccord.scalapb.{ScalaPbPlugin => PB}

lazy val buildSettings = Seq(
  organization := "io.aecor",
  scalaVersion := "2.11.8"
)

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots"),
    "Twitter Repository"               at "http://maven.twttr.com",
    "Websudos releases"                at "https://dl.bintray.com/websudos/oss-releases/"
  ),
  libraryDependencies ++= Seq(
    "com.github.mpilquist" %% "simulacrum" % "0.7.0",
    "org.typelevel" %% "machinist" % "0.4.1",
    compilerPlugin("org.spire-math" %% "kind-projector" % "0.6.3"),
    compilerPlugin("com.milessabin" % "si2712fix-plugin" % "1.1.0" cross CrossVersion.full)
  ),
  parallelExecution in Test := false,
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value.filter(_ != "-Xfatal-warnings")
) ++ warnUnusedImport



lazy val aecorSettings = buildSettings ++ commonSettings

lazy val aecor = project.in(file("."))
  .settings(moduleName := "aecor")
  .settings(aecorSettings)
  .aggregate(core, example, tests, bench)
  .dependsOn(core, example, tests % "test-internal -> test", bench % "compile-internal;test-internal -> test")

lazy val core = project
  .settings(moduleName := "aecor-core")
  .settings(aecorSettings:_*)
  .settings(coreSettings)
  .settings(libraryDependencies += "org.scalacheck" %% "scalacheck" % scalacheckVersion % "test")

lazy val bench = project.dependsOn(core, example)
  .settings(moduleName := "aecor-bench")
  .settings(aecorSettings)
  .enablePlugins(JmhPlugin)

lazy val tests = project.dependsOn(core, example)
  .settings(moduleName := "aecor-tests")
  .settings(aecorSettings)
  .settings(testingDependencies: _*)

lazy val example = project.dependsOn(core)
  .settings(moduleName := "aecor-example")
  .settings(aecorSettings)

val circeVersion = "0.4.1"
val akkaVersion = "2.4.7"
val akkaStreamKafka = "0.11-M3"
val akkaPersistenceCassandra = "0.16"
val catsVersion = "0.5.0"
val akkaHttpJson = "1.6.0"
val kamonVersion = "0.6.1"
val scalacheckVersion = "1.13.0"

def dependency(organization: String)(modules: String*)(version: String) = modules.map(module => organization %% module % version)

lazy val coreSettings = Seq(
  libraryDependencies ++= dependency("com.typesafe.akka")(
    "akka-http-experimental",
    "akka-cluster-sharding",
    "akka-persistence",
    "akka-slf4j",
    "akka-contrib",
    "akka-persistence-query-experimental"
  )(akkaVersion),
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandra,
    "com.typesafe.akka" %% "akka-stream-kafka" % akkaStreamKafka,
    "com.github.romix.akka" %% "akka-kryo-serialization" % "0.4.1",
    "org.fusesource" % "sigar" % "1.6.4",
    "ch.qos.logback" % "logback-classic" % "1.1.7"
  ),
  libraryDependencies ++= Seq(
    "io.kamon" %% "kamon-core",
    "io.kamon" %% "kamon-jmx",
    "io.kamon" %% "kamon-akka",
    "io.kamon" %% "kamon-akka-remote_akka-2.4",
    "io.kamon" %% "kamon-autoweave"
  ).map(_ % kamonVersion),
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser"
  ).map(_ % circeVersion),
  libraryDependencies += "org.typelevel" %% "cats" % catsVersion,
  libraryDependencies += "de.heikoseeberger" %% "akka-http-circe" % akkaHttpJson,
  PB.flatPackage in PB.protobufConfig := true
) ++ PB.protobufSettings

lazy val testingDependencies = Seq(
  libraryDependencies += "org.scalacheck" %% "scalacheck" % scalacheckVersion,
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.0-RC1" % "test",
  libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
)

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:experimental.macros",
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Yinline-warnings",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Ywarn-unused-import",
  "-Xfuture"
)

lazy val warnUnusedImport = Seq(
  scalacOptions in (Compile, console) ~= {_.filterNot("-Ywarn-unused-import" == _)},
  scalacOptions in (Test, console) <<= (scalacOptions in (Compile, console))
)
