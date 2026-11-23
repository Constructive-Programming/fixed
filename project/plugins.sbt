addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.10")
addSbtPlugin("dev.constructive" % "sbt-cardinality" % "0.0.6")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")

// `sbt-typelevel-site` drives the Laika + Helium docs site (fixed.constructive.dev) —
// the same mechanism as the sister project eo. Pairs the markdown under `site/docs/`
// (through mdoc) with the `spec/` tree (direct Laika input — single source of truth).
// Built and deployed to Cloudflare Pages by .github/workflows/deploy-site.yml.
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % "0.8.7")
