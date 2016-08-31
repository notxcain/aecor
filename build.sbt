import com.trueaccord.scalapb.{ScalaPbPlugin => PB}

lazy val buildSettings = Seq(
  organization := "io.aecor",
  scalaVersion := "2.11.8"
)

scalaOrganization := "org.typelevel"

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  resolvers ++= Seq(
    "Websudos releases" at "https://dl.bintray.com/websudos/oss-releases/",
    Resolver.bintrayRepo("hseeberger", "maven")
  ),
  libraryDependencies ++= Seq(
    "com.github.mpilquist" %% "simulacrum" % "0.7.0",
    "org.typelevel" %% "machinist" % "0.4.1",
    compilerPlugin("org.spire-math" %% "kind-projector" % "0.6.3"),
    compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
  ),
  parallelExecution in Test := false,
  scalacOptions in(Compile, doc) := (scalacOptions in(Compile, doc)).value.filter(_ != "-Xfatal-warnings")
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
                .settings(libraryDependencies += "org.scalacheck" %% "scalacheck" % scalaCheckVersion % "test")

lazy val api = project.dependsOn(core)
               .settings(moduleName := "aecor-api")
               .settings(aecorSettings)
               .settings(apiSettings)

lazy val schedule = project.dependsOn(core)
                    .settings(moduleName := "aecor-schedule")
                    .settings(aecorSettings)
                    .settings(scheduleSettings)

lazy val bench = project.dependsOn(core, example)
                 .settings(moduleName := "aecor-bench")
                 .settings(aecorSettings)
                 .enablePlugins(JmhPlugin)

lazy val tests = project.dependsOn(core, example, schedule)
                 .settings(moduleName := "aecor-tests")
                 .settings(aecorSettings)
                 .settings(testingSettings)

lazy val circe = project.dependsOn(core)
                 .settings(moduleName := "aecor-circe")
                 .settings(aecorSettings)
                 .settings(circeSettings)

lazy val example = project.dependsOn(core, api, circe, schedule)
                   .settings(moduleName := "aecor-example")
                   .settings(aecorSettings)
                   .settings(exampleSettings)

val circeVersion = "0.5.0-M3"
val akkaVersion = "2.4.9"
val reactiveKafka = "0.11-RC1"
val akkaPersistenceCassandra = "0.17"
val catsVersion = "0.7.0"
val akkaHttpJson = "1.9.0"

lazy val scalaCheckVersion = "1.13.2"
lazy val scalaTestVersion = "3.0.0"
lazy val scalaCheckShapelessVersion = "1.1.1"
lazy val shapelessVersion = "2.3.2"

def dependency(organization: String)(modules: String*)(version: String) = modules.map(module => organization %% module % version)

lazy val coreSettings = Seq(
  libraryDependencies ++= dependency("com.typesafe.akka")(
    "akka-cluster-sharding",
    "akka-persistence",
    "akka-persistence-query-experimental",
    "akka-slf4j"
  )(akkaVersion),
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandra,
    "com.typesafe.akka" %% "akka-stream-kafka" % reactiveKafka,
    "ch.qos.logback" % "logback-classic" % "1.1.7"
  ),

  libraryDependencies ++= Seq(
    "com.chuusai" %% "shapeless" % shapelessVersion
  ),

  libraryDependencies += "org.typelevel" %% "cats" % catsVersion
) ++ commonProtobufSettings


lazy val apiSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http-experimental" % akkaVersion
  )
)

lazy val scheduleSettings = commonProtobufSettings

lazy val exampleSettings = Seq(
  libraryDependencies ++= Seq(
    "com.github.romix.akka" %% "akka-kryo-serialization" % "0.4.1",
    "com.typesafe.akka" %% "akka-http-experimental" % akkaVersion,
    "de.heikoseeberger" %% "akka-http-circe" % akkaHttpJson
  )
)

lazy val circeSettings = Seq(
  libraryDependencies ++= dependency("io.circe")(
    "circe-core",
    "circe-generic",
    "circe-parser"
  )(circeVersion)
)

lazy val testingSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test
    ,"org.scalatest" %% "scalatest" % scalaTestVersion % Test
    ,"com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test
    ,"com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % scalaCheckShapelessVersion % Test
  )
)


lazy val commonProtobufSettings =
  PB.protobufSettings ++
    Seq(
      version in PB.protobufConfig := "2.6.1",
      javaSource in PB.protobufConfig <<= (sourceManaged in Compile),
      scalaSource in PB.protobufConfig <<= (sourceManaged in Compile),
      PB.flatPackage in PB.protobufConfig := true,
      PB.runProtoc in PB.protobufConfig := (args => com.github.os72.protocjar.Protoc.runProtoc("-v261" +: args.toArray))
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
  scalacOptions in(Compile, console) ~= {
    _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-value-discard"))
  },
  scalacOptions in(Test, console) <<= (scalacOptions in(Compile, console))
)
