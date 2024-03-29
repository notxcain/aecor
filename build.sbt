import ReleaseTransformations._
import sbtrelease.Version.Bump

lazy val buildSettings = inThisBuild(
  Seq(organization := "io.aecor", crossScalaVersions := Seq("2.13.6", "2.12.15"))
)

lazy val akkaVersion = "2.5.26"
lazy val akkaPersistenceCassandraVersion = "0.62"

lazy val apacheKafkaClientsVersion = "2.3.0"

lazy val catsVersion = "2.6.1"
lazy val catsEffectVersion = "3.2.9"

lazy val logbackVersion = "1.2.3"
lazy val cassandraDriverExtrasVersion = "3.8.0"
lazy val jsr305Version = "3.0.2"
lazy val boopickleVersion = "1.3.1"
lazy val monocleVersion = "2.1.0"

lazy val fs2Version = "3.1.3"
lazy val scodecBitsVersion = "1.1.13"
lazy val scodecCoreVersion = "1.11.4"

lazy val catsTaglessVersion = "0.14.0"

lazy val scalatestPlusScalaCheckVersion = "3.1.0.0-RC2"
lazy val scalaCheckShapelessVersion = "1.2.4"
lazy val disciplineScalatestVersion = "1.0.0-RC1"
lazy val embeddedKafkaVersion = "2.3.0"
lazy val shapelessVersion = "2.3.3"
lazy val kindProjectorVersion = "0.13.2"
lazy val betterMonadicForVersion = "0.3.1"

// Example dependencies

lazy val circeVersion = "0.14.1"
lazy val http4sVersion = "0.23.4"
lazy val catsMTLVersion = "0.7.1"

lazy val commonSettings = Seq(
  scalacOptions += "-Xsource:2.13",
  scalacOptions ~= { opts => opts.filterNot(Set("-Xlint:nullary-override")) },
    libraryDependencies ++= Seq(
    compilerPlugin("org.typelevel" % "kind-projector" % kindProjectorVersion cross CrossVersion.full)
  ),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % betterMonadicForVersion),
  parallelExecution in Test := false
)

lazy val macroSettings =
  Seq(
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        // if scala 2.13+ is used, quasiquotes are merged into scala-reflect
        case Some((2, scalaMajor)) if scalaMajor >= 13 => Seq()
        // otherwise, quasiquotes are provided by macro paradise
        case _ =>
          Seq(compilerPlugin("org.scalamacros" %% "paradise" % "2.1.1" cross CrossVersion.full))
      }
    },
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % Provided,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided
    )
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
  .settings(commonProtobufSettings)
  .settings(akkaPersistenceSettings)

lazy val akkaGeneric =
  aecorModule("akka-cluster-runtime", "Aecor Runtime based on Akka Cluster Sharding")
    .dependsOn(core)
    .dependsOn(boopickleWireProtocol % "test->compile")
    .settings(aecorSettings)
    .settings(commonTestSettings)
    .settings(commonProtobufSettings)
    .settings(akkaGenericSettings)

lazy val distributedProcessing =
  aecorModule("distributed-processing", "Aecor Distributed Processing")
    .dependsOn(core)
    .settings(aecorSettings)
    .settings(commonProtobufSettings)
    .settings(distributedProcessingSettings)

lazy val kafkaDistributedProcessing =
  aecorModule(
    "kafka-distributed-processing",
    "Aecor Distributed Processing based on Kafka partition assignment"
  ).dependsOn(core)
    .settings(aecorSettings)
    .settings(commonTestSettings)
    .settings(kafkaDistributedProcessingSettings)

lazy val schedule = aecorModule("schedule", "Aecor Schedule")
  .dependsOn(akkaPersistence, distributedProcessing, boopickleWireProtocol)
  .settings(aecorSettings)
  .settings(commonProtobufSettings)
  .settings(scheduleSettings)

lazy val testKit = aecorModule("test-kit", "Aecor Test Kit")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(testKitSettings)

lazy val tests = aecorModule("tests", "Aecor Tests")
  .dependsOn(core, schedule, testKit, akkaPersistence, distributedProcessing, boopickleWireProtocol)
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(commonTestSettings)
  .settings(testsSettings)

lazy val example = aecorModule("example", "Aecor Example Application")
  .dependsOn(core, schedule, distributedProcessing, boopickleWireProtocol)
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

lazy val boopickleWireProtocolSettings =
  macroSettings ++ Seq(libraryDependencies ++= Seq("io.suzaku" %% "boopickle" % boopickleVersion))

lazy val scheduleSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
    "com.datastax.cassandra" % "cassandra-driver-extras" % cassandraDriverExtrasVersion,
    "com.google.code.findbugs" % "jsr305" % jsr305Version % Compile
  )
)

lazy val distributedProcessingSettings = Seq(
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion)
)

lazy val kafkaDistributedProcessingSettings = Seq(
  libraryDependencies ++= Seq(
    "org.apache.kafka" % "kafka-clients" % apacheKafkaClientsVersion,
    "co.fs2" %% "fs2-core" % fs2Version,
    "co.fs2" %% "fs2-reactive-streams" % fs2Version,
    "io.github.embeddedkafka" %% "embedded-kafka" % embeddedKafkaVersion % Test
  )
)

lazy val akkaPersistenceSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandraVersion
  )
)

lazy val akkaGenericSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % akkaPersistenceCassandraVersion % Test
  )
)

lazy val exampleSettings = Seq(
  libraryDependencies ++=
    Seq(
      "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
      "co.fs2" %% "fs2-reactive-streams" % fs2Version,
      "org.typelevel" %% "cats-mtl-core" % catsMTLVersion,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-blaze-server" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion
    )
)

lazy val testKitSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-mtl-core" % catsMTLVersion,
    "com.github.julien-truffaut" %% "monocle-core" % monocleVersion,
    "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion
  )
)

lazy val testsSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % akkaPersistenceCassandraVersion % Test
  )
)

lazy val commonTestSettings = Seq(
  libraryDependencies ++= Seq(
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.14" % scalaCheckShapelessVersion % Test,
    "org.scalatestplus" %% "scalatestplus-scalacheck" % scalatestPlusScalaCheckVersion % Test,
    "org.typelevel" %% "discipline-scalatest" % disciplineScalatestVersion % Test,
    "org.typelevel" %% "cats-laws" % catsVersion % Test
  )
)

lazy val commonProtobufSettings = Seq(
  PB.targets in Compile := Seq(scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value)
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
