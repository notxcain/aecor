import ReleaseTransformations._
import sbtrelease.Version.Bump

lazy val buildSettings = inThisBuild(
  Seq(
    organization := "io.aecor",
    scalaVersion := "2.11.11-bin-typelevel-4",
    scalaOrganization := "org.typelevel",
    crossScalaVersions := Seq("2.11.11-bin-typelevel-4", "2.12.3-bin-typelevel-4")
  )
)

lazy val akkaVersion = "2.5.4"
lazy val akkaPersistenceCassandraVersion = "0.55"
lazy val catsVersion = "0.9.0"
lazy val logbackVersion = "1.1.7"
lazy val cassandraDriverExtrasVersion = "3.1.0"
lazy val jsr305Version = "3.0.1"

lazy val monixVersion = "2.3.0"
lazy val fs2Version = "0.9.6"
lazy val scalaCheckVersion = "1.13.4"
lazy val scalaTestVersion = "3.0.1"
lazy val scalaCheckShapelessVersion = "1.1.4"
lazy val shapelessVersion = "2.3.2"
lazy val kindProjectorVersion = "0.9.4"
lazy val paradiseVersion = "2.1.0"
lazy val simulacrumVersion = "0.10.0"

// Example dependencies

lazy val circeVersion = "0.8.0"
lazy val akkaHttpVersion = "10.0.10"
lazy val akkaHttpJsonVersion = "1.16.0"
lazy val scalametaParadiseVersion = "3.0.0-M10"

lazy val liberatorVersion = "0.4.3"

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  libraryDependencies ++= Seq(
    compilerPlugin("org.spire-math" %% "kind-projector" % kindProjectorVersion)
  ),
  parallelExecution in Test := false,
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value
    .filter(_ != "-Xfatal-warnings")
) ++ warnUnusedImport

lazy val aecorSettings = buildSettings ++ commonSettings ++ publishSettings

lazy val aecor = project
  .in(file("."))
  .settings(moduleName := "aecor", name := "Aecor")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .aggregate(
    core,
    akkaPersistence,
    distributedProcessing,
    example,
    schedule,
    effectMonix,
    effectFs2,
    tests
  )
  .dependsOn(core, example % "compile-internal", tests % "test-internal -> test")

lazy val core =
  project
    .settings(moduleName := "aecor-core", name := "Aecor Core")
    .settings(aecorSettings)
    .settings(coreSettings)

lazy val akkaPersistence = project
  .in(file("aecor-akka-persistence"))
  .settings(
    moduleName := "aecor-akka-persistence",
    name := "Aecor Runtime based on Akka Cluster Sharding and Persistence"
  )
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(akkaPersistenceSettings)

lazy val akkaGeneric = project
  .in(file("aecor-akka-generic"))
  .settings(
    moduleName := "aecor-akka-generic",
    name := "Aecor Runtime based on Akka Cluster Sharding"
  )
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(akkaPersistenceSettings)

lazy val distributedProcessing =
  project
    .in(file("distributed-processing"))
    .settings(moduleName := "aecor-distributed-processing", name := "Aecor Distributed Processing")
    .dependsOn(core)
    .settings(aecorSettings)
    .settings(distributedProcessingSettings)

lazy val schedule = project
  .dependsOn(akkaPersistence, distributedProcessing)
  .settings(moduleName := "aecor-schedule", name := "Aecor Schedule")
  .settings(aecorSettings)
  .settings(scheduleSettings)

lazy val effectMonix = project
  .in(file("aecor-monix"))
  .settings(moduleName := "aecor-monix", name := "Aecor Monix")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(effectMonixSettings)

lazy val effectFs2 = project
  .in(file("aecor-fs2"))
  .settings(moduleName := "aecor-fs2", name := "Aecor FS2")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(effectFs2Settings)

lazy val testKit = project
  .in(file("aecor-test-kit"))
  .settings(moduleName := "aecor-test-kit", name := "Aecor Test Kit")
  .dependsOn(core)
  .settings(aecorSettings)

lazy val tests = project
  .dependsOn(
    core,
    example,
    schedule,
    effectMonix,
    effectFs2,
    testKit,
    akkaPersistence,
    distributedProcessing,
    akkaGeneric
  )
  .settings(moduleName := "aecor-tests", name := "Aecor Tests")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(testingSettings)

lazy val example = project
  .dependsOn(core, schedule, effectMonix, distributedProcessing)
  .settings(moduleName := "aecor-example", name := "Aecor Example Application")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(exampleSettings)

lazy val coreSettings = Seq(
  libraryDependencies ++= Seq(
    compilerPlugin("org.scalamacros" % "paradise" % paradiseVersion cross CrossVersion.patch),
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats" % catsVersion,
    "com.github.mpilquist" %% "simulacrum" % simulacrumVersion
  )
)

lazy val scheduleSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.datastax.cassandra" % "cassandra-driver-extras" % cassandraDriverExtrasVersion,
    "com.google.code.findbugs" % "jsr305" % jsr305Version % Compile
  )
)

lazy val distributedProcessingSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion
  )
)

lazy val akkaPersistenceSettings = commonProtobufSettings  ++ Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandraVersion
  )
)

lazy val akkaGenericSettings = Seq(
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion)
)

lazy val effectMonixSettings = Seq(
  libraryDependencies ++= Seq("io.monix" %% "monix-eval" % monixVersion)
)

lazy val effectFs2Settings = Seq(libraryDependencies ++= Seq("co.fs2" %% "fs2-core" % fs2Version))

lazy val exampleSettings = {
  val akkaKryoVersion = SettingKey[String]("akka-kryo-version", "")

  Seq(
    akkaKryoVersion := {
      if (scalaVersion.value startsWith "2.11") "0.5.0" else "0.5.1"
    },
    resolvers += Resolver.sonatypeRepo("releases"),
    sources in (Compile, doc) := Nil,
    libraryDependencies ++=
      Seq(
        compilerPlugin(
          "org.scalameta" % "paradise" % scalametaParadiseVersion cross CrossVersion.patch
        ),
        "com.github.romix.akka" %% "akka-kryo-serialization" % akkaKryoVersion.value,
        "io.aecor" %% "liberator" % liberatorVersion,
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
        "io.monix" %% "monix-cats" % monixVersion,
        "io.monix" %% "monix-reactive" % monixVersion,
        "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
        "de.heikoseeberger" %% "akka-http-circe" % akkaHttpJsonVersion,
        "io.circe" %% "circe-core" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-parser" % circeVersion,
        "io.circe" %% "circe-java8" % circeVersion,
        "ch.qos.logback" % "logback-classic" % logbackVersion
      )
  )
}

lazy val testingSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % akkaPersistenceCassandraVersion % Test,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % scalaCheckShapelessVersion % Test
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
  "-Ypartial-unification"
)

lazy val warnUnusedImport = Seq(scalacOptions in (Compile, console) ~= {
  _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-value-discard"))
}, scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value)

lazy val noPublishSettings = Seq(publish := (), publishLocal := (), publishArtifact := false)

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
    ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
    pushChanges
  )
)

addCommandAlias("validate", ";compile;test")
