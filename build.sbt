import ReleaseTransformations._
import sbtrelease.Version.Bump

lazy val buildSettings = inThisBuild(
  Seq(
    organization := "io.aecor",
    scalaVersion := "2.12.8"
  )
)

lazy val akkaVersion = "2.5.23"
lazy val akkaPersistenceCassandraVersion = "0.62"


lazy val apacheKafkaClientsVersion = "2.3.0"
lazy val catsVersion = "1.6.1"
lazy val catsEffectVersion = "1.3.1"

lazy val logbackVersion = "1.2.3"
lazy val cassandraDriverExtrasVersion = "3.7.2"
lazy val jsr305Version = "3.0.2"
lazy val boopickleVersion = "1.3.1"
lazy val monocleVersion = "1.6.0-RC1"

lazy val fs2Version = "1.0.5"
lazy val scodecBitsVersion = "1.1.12"
lazy val scodecCoreVersion = "1.11.4"

lazy val catsTaglessVersion = "0.9"

lazy val scalaCheckVersion = "1.13.5"
lazy val scalaTestVersion = "3.0.5"
lazy val scalaCheckShapelessVersion = "1.1.8"
lazy val shapelessVersion = "2.3.3"
lazy val kindProjectorVersion = "0.10.3"
lazy val betterMonadicForVersion = "0.3.0"

// Example dependencies

lazy val circeVersion = "0.11.1"
lazy val http4sVersion = "0.20.6"
lazy val log4catsVersion = "0.3.0"
lazy val catsMTLVersion = "0.5.0"

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  addCompilerPlugin("org.typelevel" %% "kind-projector" % kindProjectorVersion),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % betterMonadicForVersion),
  parallelExecution in Test := false,
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value
    .filter(_ != "-Xfatal-warnings"),
) ++ warnUnusedImport

lazy val macroSettings = Seq(
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
)

lazy val aecorSettings = buildSettings ++ commonSettings ++ publishSettings

lazy val aecor = project
  .in(file("."))
  .withId("aecor")
  .settings(moduleName := "aecor", name := "Aecor")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .aggregate(
    core,
    boopickleWireProtocol,
    akkaPersistence,
    akkaGeneric,
    distributedProcessing,
    kafkaDistributedProcessing,
    example,
    schedule,
    testKit,
    tests,
    benchmarks
  )

def aecorModule(id: String, description: String): Project =
  Project(id, file(s"modules/$id"))
    .settings(moduleName := id, name := description)

lazy val core = aecorModule("core", "Aecor Core")
  .settings(aecorSettings)
  .settings(coreSettings)

lazy val boopickleWireProtocol =
  aecorModule("boopickle-wire-protocol", "Aecor Boopickle Wire Protocol derivation")
    .dependsOn(core)
    .settings(aecorSettings)
    .settings(boopickleWireProtocolSettings)

lazy val akkaPersistence = aecorModule(
  "akka-persistence-runtime",
  "Aecor Runtime based on Akka Cluster Sharding and Persistence"
).dependsOn(core)
  .settings(aecorSettings)
  .settings(akkaPersistenceSettings)

lazy val akkaGeneric =
  aecorModule("akka-cluster-runtime", "Aecor Runtime based on Akka Cluster Sharding")
    .dependsOn(core)
    .dependsOn(boopickleWireProtocol % "test->compile")
    .settings(aecorSettings)
    .settings(macroSettings)
    .settings(commonTestSettings)
    .settings(akkaGenericSettings)

lazy val distributedProcessing =
  aecorModule("distributed-processing", "Aecor Distributed Processing")
    .dependsOn(core)
    .settings(aecorSettings)
    .settings(distributedProcessingSettings)

lazy val kafkaDistributedProcessing =
  aecorModule("kafka-distributed-processing", "Aecor Distributed Processing based on Kafka partition assignment")
    .dependsOn(core)
    .settings(aecorSettings)
    .settings(kafkaDistributedProcessingSettings)

lazy val schedule = aecorModule("schedule", "Aecor Schedule")
  .dependsOn(akkaPersistence, distributedProcessing, boopickleWireProtocol)
  .settings(aecorSettings)
  .settings(scheduleSettings)

lazy val testKit = aecorModule("test-kit", "Aecor Test Kit")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(testKitSettings)

lazy val tests = aecorModule("tests", "Aecor Tests")
  .dependsOn(core, schedule, testKit, akkaPersistence, distributedProcessing, kafkaDistributedProcessing, boopickleWireProtocol)
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(testingSettings)

lazy val example = aecorModule("example", "Aecor Example Application")
  .dependsOn(core, schedule, distributedProcessing, kafkaDistributedProcessing, boopickleWireProtocol)
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(exampleSettings)

lazy val benchmarks = aecorModule("benchmarks", "Aecor Benchmarks")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .enablePlugins(JmhPlugin)

lazy val coreSettings = Seq(
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % fs2Version,
    "org.typelevel" %% "cats-tagless-core" % catsTaglessVersion,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.scodec" %% "scodec-bits" % scodecBitsVersion,
    "org.scodec" %% "scodec-core" % scodecCoreVersion
  )
)

lazy val boopickleWireProtocolSettings = Seq(
  libraryDependencies ++= Seq(
    "io.suzaku" %% "boopickle" % boopickleVersion,
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"
  )
) ++ macroSettings

lazy val scheduleSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
    "com.datastax.cassandra" % "cassandra-driver-extras" % cassandraDriverExtrasVersion,
    "com.google.code.findbugs" % "jsr305" % jsr305Version % Compile
  )
) ++ macroSettings

lazy val distributedProcessingSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion)
)

lazy val kafkaDistributedProcessingSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "org.apache.kafka" % "kafka-clients" % apacheKafkaClientsVersion,
    "co.fs2" %% "fs2-core" % fs2Version,
    "co.fs2" %% "fs2-reactive-streams" % fs2Version
  )
)

lazy val akkaPersistenceSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandraVersion
  )
)

lazy val akkaGenericSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion % Test
  )
)

lazy val exampleSettings =
  Seq(
    resolvers += Resolver.sonatypeRepo("releases"),
    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven",
    libraryDependencies ++=
      Seq(
        "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
        "com.github.krasserm" %% "streamz-converter" % "0.10-M2",
        "org.typelevel" %% "cats-mtl-core" % catsMTLVersion,
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
        "org.http4s" %% "http4s-dsl" % http4sVersion,
        "org.http4s" %% "http4s-blaze-server" % http4sVersion,
        "org.http4s" %% "http4s-circe" % http4sVersion,
        "io.circe" %% "circe-core" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-parser" % circeVersion,
        "io.circe" %% "circe-java8" % circeVersion,
        "ch.qos.logback" % "logback-classic" % logbackVersion
      )
  ) ++ macroSettings

lazy val testKitSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-mtl-core" % catsMTLVersion,
    "com.github.julien-truffaut" %% "monocle-core" % monocleVersion,
    "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion
  )
)

lazy val testingSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-java8" % circeVersion,
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % akkaPersistenceCassandraVersion % Test,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % scalaCheckShapelessVersion % Test,
    "org.typelevel" %% "cats-testkit" % catsVersion % Test,
    "io.github.embeddedkafka" %% "embedded-kafka" % "2.2.0" % Test
  )
) ++ macroSettings

lazy val commonTestSettings =
  Seq(
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % akkaPersistenceCassandraVersion % Test,
      "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % scalaCheckShapelessVersion % Test,
      "org.typelevel" %% "cats-testkit" % catsVersion % Test
    )
  )

lazy val commonProtobufSettings =
  Seq(
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value
    )
  )

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:experimental.macros",
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Ywarn-unused-import",
  "-Ypartial-unification",
  "-Xsource:2.13"
)

lazy val warnUnusedImport = Seq(
  scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings")
)

lazy val noPublishSettings = Seq(publish := (()), publishLocal := (()), publishArtifact := false)

lazy val publishSettings = Seq(
  releaseCrossBuild := true,
  releaseVersionBump := Bump.Minor,
  releaseCommitMessage := s"Set version to ${if (releaseUseGlobalVersion.value) (version in ThisBuild).value
  else version.value}",
  releaseIgnoreUntrackedFiles := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  homepage := Some(url("https://github.com/notxcain/aecor")),
  licenses := Seq("MIT" -> url("http://opensource.org/licenses/MIT")),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  autoAPIMappings := true,
  scmInfo := Some(
    ScmInfo(url("https://github.com/notxcain/aecor"), "scm:git:git@github.com:notxcain/aecor.git")
  ),
  pomExtra :=
    <developers>
      <developer>
        <id>notxcain</id>
        <name>Denis Mikhaylov</name>
        <url>https://github.com/notxcain</url>
      </developer>
    </developers>
)

lazy val sharedReleaseProcess = Seq(
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    ReleaseStep(action = "sonatypeReleaseAll" :: _),
    pushChanges
  )
)

addCommandAlias("validate", ";compile;test")
