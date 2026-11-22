ThisBuild / scalaVersion := "3.10.1"
ThisBuild / organization := "com.fixed"
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Werror",
  "-source:3.10"
)

// Point git at the committed hooks in .githooks/ whenever the build loads,
// so the pre-commit gate (`sbt check test`) is active for anyone using sbt.
Global / onLoad := {
  val previous = (Global / onLoad).value
  val base = (LocalRootProject / baseDirectory).value
  previous.andThen { state =>
    import scala.sys.process._
    val hooks = base / ".githooks"
    if (hooks.isDirectory && (base / ".git").exists) {
      val current = scala.util.Try(Process(Seq("git", "config", "--get", "core.hooksPath"), base).!!.trim).getOrElse("")
      if (current != ".githooks") {
        val rc = Process(Seq("git", "config", "core.hooksPath", ".githooks"), base).!
        if (rc == 0) state.log.info(s"git hooks: core.hooksPath set to .githooks (was '${current}')")
        else state.log.warn("git hooks: could not set core.hooksPath; run `git config core.hooksPath .githooks`")
      }
    }
    state
  }
}

// Scalafix semantic rules (RemoveUnused, OrganizeImports) read SemanticDB.
ThisBuild / semanticdbEnabled := true

// Copy-paste detection: PMD CPD in an isolated config, forked, so its
// Scala 2.13 deps never touch the compiler classpath. Fails on any
// duplicate block of >= cpdMinTokens tokens.
val pmdVersion = "7.29.0"
val cpdMinTokens = 25
lazy val Cpd = config("cpd").hide
lazy val cpd = taskKey[Unit]("Run PMD CPD copy-paste detection over compiler sources")

import _root_.scalafix.sbt.{BuildInfo => ScalafixBuildInfo}

// Custom scalafix rules (.claude/skills/scalafix-rule). Rules are Scala 3 compiled against
// scalafix-core's 2.13 artifact; scalafix-testkit is published per full Scala version, so the
// test harness pins the newest one available.
val scalafixTestkitScala = "3.10.0"

lazy val `scalafix-rules` = (project in file("scalafix/rules"))
  .disablePlugins(ScalafixPlugin)
  .settings(
    publish / skip := true,
    libraryDependencies +=
      ("ch.epfl.scala" %% "scalafix-core" % ScalafixBuildInfo.scalafixVersion).cross(CrossVersion.for3Use2_13),
    scalacOptions --= Seq("-Werror")
  )

lazy val `scalafix-input` = (project in file("scalafix/input"))
  .disablePlugins(ScalafixPlugin, ScalafmtPlugin) // testkit fixtures: formatting would move asserts
  .settings(publish / skip := true, scalacOptions --= Seq("-Werror", "-Wunused:all", "-Wvalue-discard"))

lazy val `scalafix-output` = (project in file("scalafix/output"))
  .disablePlugins(ScalafixPlugin, ScalafmtPlugin) // testkit fixtures: formatting would move asserts
  .settings(publish / skip := true, scalacOptions --= Seq("-Werror", "-Wunused:all", "-Wvalue-discard"))

lazy val `scalafix-tests` = (project in file("scalafix/tests"))
  .dependsOn(`scalafix-input`, `scalafix-rules`)
  .enablePlugins(ScalafixTestkitPlugin)
  .disablePlugins(ScalafixPlugin)
  .settings(
    publish / skip := true,
    scalaVersion := scalafixTestkitScala,
    scalacOptions --= Seq("-Werror"),
    libraryDependencies +=
      ("ch.epfl.scala" % "scalafix-testkit" % ScalafixBuildInfo.scalafixVersion % Test).cross(CrossVersion.full),
    scalafixTestkitOutputSourceDirectories := (`scalafix-output` / Compile / sourceDirectories).value,
    scalafixTestkitInputSourceDirectories := (`scalafix-input` / Compile / sourceDirectories).value,
    scalafixTestkitInputClasspath := (`scalafix-input` / Compile / fullClasspath).value,
    scalafixTestkitInputScalacOptions := (`scalafix-input` / Compile / scalacOptions).value,
    scalafixTestkitInputScalaVersion := (`scalafix-input` / Compile / scalaVersion).value
  )

lazy val compiler = (project in file("compiler"))
  .dependsOn(`scalafix-rules` % ScalafixConfig)
  .settings(
    name := "fixed-compiler",
    libraryDependencies ++= Seq(
      "dev.constructive" %% "cats-eo" % "1.3.0", // brings cats-core
      ("io.github.uuverifiers" %% "princess" % "2025-06-25").cross(CrossVersion.for3Use2_13),
      "org.scalameta" %% "munit" % "1.0.0" % Test
    ),
    Test / parallelExecution := false,
    // Suite tests parse the full `examples/` corpus inside hot loops
    // (CorruptionResilienceSuite × 200 iterations). Fork with a
    // generous heap and pin baseDirectory to the repo root so the
    // example files resolve.
    Test / fork := true,
    Test / javaOptions += "-Xmx2g",
    Test / baseDirectory := (ThisBuild / baseDirectory).value,
    // `Main` calls sys.exit, so run it in a forked JVM rooted at the repo (example paths resolve).
    run / fork := true,
    run / baseDirectory := (ThisBuild / baseDirectory).value
  )

// JMH benchmarks for the front end. Not aggregated: run explicitly with
// `sbt "bench/Jmh/run -prof gc"`.
lazy val bench = (project in file("bench"))
  .dependsOn(compiler)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "fixed-bench",
    publish / skip := true,
    // JMH state classes need `var` fields and loops; generated code trips -Werror lints.
    scalacOptions --= Seq("-Werror", "-Wunused:all", "-Wvalue-discard")
  )

lazy val root = (project in file("."))
  .aggregate(compiler, `scalafix-tests`)
  .dependsOn(`scalafix-rules` % ScalafixConfig)
  .configs(Cpd)
  .settings(
    name := "fixed",
    publish / skip := true,
    Compile / run := (compiler / Compile / run).evaluated,
    libraryDependencies ++= Seq(
      "net.sourceforge.pmd" % "pmd-cli" % pmdVersion % Cpd,
      "net.sourceforge.pmd" % "pmd-scala_2.13" % pmdVersion % Cpd
    ),
    cpd := {
      val cp = Classpaths.managedJars(Cpd, classpathTypes.value, update.value).map(_.data)
      val src = (compiler / Compile / sourceDirectories).value.filter(_.exists)
      val args = Seq(
        "-cp",
        cp.mkString(java.io.File.pathSeparator),
        "net.sourceforge.pmd.cli.PmdCli",
        "cpd",
        "--language",
        "scala",
        "--minimum-tokens",
        cpdMinTokens.toString
      ) ++
        src.flatMap(d => Seq("--dir", d.getAbsolutePath))
      val code = Fork.java(ForkOptions(), args)
      code match {
        case 0 => ()
        case 4 => throw new MessageOnlyException("CPD found duplicated code (see report above)")
        case n => throw new MessageOnlyException(s"CPD failed to run (exit $n)")
      }
    },
    addCommandAlias("check", "scalafmtCheckAll; scalafmtSbtCheck; scalafixAll --check; cpd"),
    addCommandAlias("fix", "scalafixAll; scalafmtAll; scalafmtSbt")
  )
