ThisBuild / scalaVersion := "3.10.1"
ThisBuild / organization := "com.fixed"
ThisBuild / version := "0.1.0-SNAPSHOT"

// sbt-typelevel (pulled in by sbt-typelevel-site, see the `docs` project below) evaluates
// sbt-git's `git.gitUncommittedChanges` at load, and its bundled JGit cannot handle linked
// git worktrees ("Bare Repository has neither a working tree, nor an index"). Pin the key so
// the build loads in worktree checkouts; nothing in this build consumes it (the version is
// static above), so the pinned value is inert.
ThisBuild / git.gitUncommittedChanges := false

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

// Docs site — Laika + Helium via sbt-typelevel-site, the same mechanism as the
// sister project eo (eo.constructive.dev). Rooted at `site/` on disk, named `docs`
// in sbt so `sbt docs/tlSite` / `sbt docs/tlSitePreview` read naturally. Kept out
// of the root aggregator so plain `sbt check test` stays fast;
// .github/workflows/deploy-site.yml builds it and deploys to Cloudflare Pages
// (fixed.constructive.dev).
lazy val docs = (project in file("site"))
  .enablePlugins(TypelevelSitePlugin)
  .settings(
    name := "fixed-docs",
    publish / skip := true,
    // Disable sbt-typelevel-site's default gh-pages publish step: production is
    // served from Cloudflare Pages via .github/workflows/deploy-site.yml and
    // GitHub Pages is not used on this repo (same as eo).
    tlSitePublishBranch := None,
    tlSitePublishTags := false,
    // The plugin's mdocIn default is the ROOT `docs/` directory, which holds
    // internal plans/decisions/baselines the public site should not ingest.
    // Point it at the site's own docs directory.
    mdocIn := (ThisBuild / baseDirectory).value / "site" / "docs",
    // Laika's input tree is mdoc's output by default. Append the language spec
    // itself so the site serves it straight from `spec/` — one source of truth,
    // no duplicated spec copies to keep in sync; the .ebnf grammar rides along
    // as a static file. Internal working notes (`docs/`) stay off the site,
    // mirroring eo's exclusion of its own internal notes.
    Laika / sourceDirectories += (ThisBuild / baseDirectory).value / "spec",
    // Helium theme config — same constructive.dev brand identity as
    // eo.constructive.dev: indigo accent, OS-driven dark mode, top nav with
    // the brand favicon and the GitHub source link.
    tlSiteHelium ~= {
      import laika.ast.{Image, Path}
      import laika.helium.config.{HeliumIcon, IconLink, ImageLink, TextLink}
      import laika.theme.config.Color
      _.all
        .themeColors(
          primary = Color.hex("3b3b8c"),
          primaryMedium = Color.hex("8585c5"),
          primaryLight = Color.hex("eaeaf5"),
          secondary = Color.hex("5e5eb8"),
          text = Color.hex("23272e"),
          background = Color.hex("ffffff"),
          bgGradient = (Color.hex("ffffff"), Color.hex("ffffff"))
        )
        .site
        .darkMode
        .themeColors(
          primary = Color.hex("7878d6"),
          primaryMedium = Color.hex("5e5eb8"),
          primaryLight = Color.hex("2d333b"),
          secondary = Color.hex("a3a3e6"),
          text = Color.hex("e6edf3"),
          background = Color.hex("1f242b"),
          bgGradient = (Color.hex("1f242b"), Color.hex("23272e"))
        )
        .site
        .topNavigationBar(
          // Home link: project name as a text link to the site root.
          // Helium's default home link (`DynamicHomeLink.default`)
          // expects a configured landing page; we don't have one.
          homeLink = TextLink.internal(Path.Root / "index.md", "Fixed"),
          // Right-side nav: constructive.dev favicon (back to brand
          // site) first, GitHub source link second.
          navLinks = Seq(
            ImageLink.external(
              "https://www.constructive.dev/",
              Image.external("https://www.constructive.dev/assets/img/favicon.svg")
            ),
            IconLink.external(
              "https://github.com/Constructive-Programming/fixed",
              HeliumIcon.github
            )
          )
        )
    }
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
