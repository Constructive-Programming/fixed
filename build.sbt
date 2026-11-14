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

// Scalafix semantic rules (RemoveUnused, OrganizeImports) read SemanticDB.
ThisBuild / semanticdbEnabled := true

// Copy-paste detection: PMD CPD in an isolated config, forked, so its
// Scala 2.13 deps never touch the compiler classpath. Fails on any
// duplicate block of >= cpdMinTokens tokens.
val pmdVersion = "7.29.0"
val cpdMinTokens = 25
lazy val Cpd = config("cpd").hide
lazy val cpd = taskKey[Unit]("Run PMD CPD copy-paste detection over compiler sources")

lazy val compiler = (project in file("compiler"))
  .settings(
    name := "fixed-compiler",
    libraryDependencies ++= Seq(
      "dev.constructive" %% "cats-eo" % "1.3.0", // brings cats-core
      "org.scalameta" %% "munit" % "1.0.0" % Test
    ),
    Test / parallelExecution := false,
    // Suite tests parse the full `examples/` corpus inside hot loops
    // (CorruptionResilienceSuite × 200 iterations). Fork with a
    // generous heap and pin baseDirectory to the repo root so the
    // example files resolve.
    Test / fork := true,
    Test / javaOptions += "-Xmx2g",
    Test / baseDirectory := (ThisBuild / baseDirectory).value
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
  .aggregate(compiler)
  .configs(Cpd)
  .settings(
    name := "fixed",
    publish / skip := true,
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
