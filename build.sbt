import com.trueaccord.scalapb.{ScalaPbPlugin => PB}

lazy val buildSettings = Seq(
  organization := "io.aecor",
  scalaVersion := "2.11.8"
)

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  resolvers ++= Seq(
//    "Twitter Repository"               at "http://maven.twttr.com",
    "Websudos releases"                at "https://dl.bintray.com/websudos/oss-releases/"
  ),
  libraryDependencies ++= Seq(
    "com.github.mpilquist" %% "simulacrum" % "0.7.0",
    "org.typelevel" %% "machinist" % "0.4.1",
    compilerPlugin("org.spire-math" %% "kind-projector" % "0.6.3"),
    compilerPlugin("com.milessabin" % "si2712fix-plugin" % "1.1.0" cross CrossVersion.full),
    compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
  ),
  parallelExecution in Test := false,
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value.filter(_ != "-Xfatal-warnings")
) ++ warnUnusedImport

lazy val aecorSettings = buildSettings ++ commonSettings

lazy val aecor = project.in(file("."))
  .settings(moduleName := "aecor")
  .settings(aecorSettings)
  .aggregate(core, api, circe, example, tests, bench)
  .dependsOn(core, api, circe, example % "compile-internal", tests % "test-internal -> test", bench % "compile-internal;test-internal -> test")

lazy val core = project
  .settings(moduleName := "aecor-core")
  .settings(aecorSettings)
  .settings(coreSettings)
  .settings(libraryDependencies += "org.scalacheck" %% "scalacheck" % scalacheckVersion % "test")

lazy val api = project
  .settings(moduleName := "aecor-api")
  .settings(aecorSettings)
    .settings(apiSettings)

lazy val bench = project.dependsOn(core, example)
  .settings(moduleName := "aecor-bench")
  .settings(aecorSettings)
  .enablePlugins(JmhPlugin)

lazy val tests = project.dependsOn(core, example)
  .settings(moduleName := "aecor-tests")
  .settings(aecorSettings)
  .settings(testingSettings)

lazy val circe = project.dependsOn(core)
  .settings(moduleName := "aecor-circe")
  .settings(aecorSettings)
  .settings(circeSettings)

lazy val example = project.dependsOn(core, api, circe)
  .settings(moduleName := "aecor-example")
  .settings(aecorSettings)
  .settings(exampleSettings)

val circeVersion = "0.5.0-M2"
val akkaVersion = "2.4.8"
val reactiveKafka = "0.11-M4"
val akkaPersistenceCassandra = "0.17"
val catsVersion = "0.6.0"
val akkaHttpJson = "1.6.0"
val kamonVersion = "0.6.1"
lazy val scalacheckVersion = "1.13.0"
val shapelessVersion = "2.3.1"

def dependency(organization: String)(modules: String*)(version: String) = modules.map(module => organization %% module % version)

lazy val coreSettings = Seq(
  libraryDependencies ++= dependency("com.typesafe.akka")(
    "akka-cluster-sharding",
    "akka-persistence",
    "akka-slf4j",
    "akka-contrib"
  )(akkaVersion),
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandra,
    "com.typesafe.akka" %% "akka-stream-kafka" % reactiveKafka,
    "org.fusesource" % "sigar" % "1.6.4",
    "ch.qos.logback" % "logback-classic" % "1.1.7"
  ),

  libraryDependencies ++= Seq(
    "com.chuusai" %% "shapeless" % "2.3.1"
  ),

  libraryDependencies ++= Seq(
    "io.kamon" %% "kamon-core",
    "io.kamon" %% "kamon-jmx",
    "io.kamon" %% "kamon-akka",
    "io.kamon" %% "kamon-akka-remote_akka-2.4",
    "io.kamon" %% "kamon-autoweave"
  ).map(_ % kamonVersion),

  libraryDependencies += "org.typelevel" %% "cats" % catsVersion
) ++
  PB.protobufSettings ++
  Seq(
    version in PB.protobufConfig := "2.6.1",
    javaSource in PB.protobufConfig <<= (sourceManaged in Compile),
    scalaSource in PB.protobufConfig <<= (sourceManaged in Compile),
    PB.flatPackage in PB.protobufConfig := true
  )

lazy val apiSettings = Seq(
  libraryDependencies ++= Seq(
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "com.typesafe.akka" %% "akka-http-experimental" % akkaVersion,
    "de.heikoseeberger" %% "akka-http-circe" % akkaHttpJson
  )
)

lazy val exampleSettings = Seq(
  libraryDependencies += "com.github.romix.akka" %% "akka-kryo-serialization" % "0.4.1"
)

lazy val circeSettings = Seq(
  libraryDependencies ++= dependency("io.circe")(
    "circe-core",
    "circe-generic",
    "circe-parser"
  )(circeVersion)
)

lazy val testingSettings = Seq(
  libraryDependencies += "org.scalacheck" %% "scalacheck" % scalacheckVersion,
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.0-RC1" % Test,
  libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test
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
