import ReleaseTransformations._
import sbtrelease.Version.Bump

lazy val buildSettings = Seq(
  organization := "io.aecor",
  scalaVersion := "2.11.11-bin-typelevel-4",
  scalaOrganization := "org.typelevel",
  crossScalaVersions := Seq("2.11.11-bin-typelevel-4", "2.12.3-bin-typelevel-4")
)

lazy val akkaVersion = "2.5.8"
lazy val akkaPersistenceCassandra = "0.59"
lazy val catsVersion = "1.6.0"
lazy val logbackVersion = "1.1.7"
lazy val cassandraDriverExtrasVersion = "3.1.0"
lazy val jsr305Version = "3.0.1"

lazy val scalaCheckVersion = "1.13.4"
lazy val scalaTestVersion = "3.0.1"
lazy val scalaCheckShapelessVersion = "1.1.4"
lazy val shapelessVersion = "2.3.2"
lazy val kindProjectorVersion = "0.9.7"
lazy val paradiseVersion = "2.1.0"

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  libraryDependencies ++= Seq(
    compilerPlugin("org.spire-math" %% "kind-projector" % kindProjectorVersion cross CrossVersion.binary)
  ),
  parallelExecution in Test := false,
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value
    .filter(_ != "-Xfatal-warnings")
) ++ warnUnusedImport

lazy val aecorSettings = buildSettings ++ commonSettings ++ publishSettings

lazy val aecor = project
  .in(file("."))
  .settings(moduleName := "aecor")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .aggregate(core,  schedule, tests)
  .dependsOn(core,  tests % "test-internal -> test")

lazy val core =
  project.settings(moduleName := "aecor-core").settings(aecorSettings).settings(coreSettings)

lazy val schedule = project
  .dependsOn(core)
  .settings(moduleName := "aecor-schedule")
  .settings(aecorSettings)
  .settings(scheduleSettings)

lazy val tests = project
  .dependsOn(core, schedule)
  .settings(moduleName := "aecor-tests")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(testingSettings)

lazy val coreSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandra,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats-core" % catsVersion
  )
)

lazy val scheduleSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.datastax.cassandra" % "cassandra-driver-extras" % cassandraDriverExtrasVersion,
    "com.google.code.findbugs" % "jsr305" % jsr305Version % Compile
  )
)

lazy val testingSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "org.typelevel" %% "cats-laws" % catsVersion % Test,
    "org.typelevel" %% "cats-testkit" % catsVersion % Test,
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
