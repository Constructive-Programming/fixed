ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "com.fixed"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Werror",
  "-source:3.8"
)

lazy val compiler = (project in file("compiler"))
  .settings(
    name := "fixed-compiler",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.0" % Test
    ),
    Test / parallelExecution := false
  )

lazy val root = (project in file("."))
  .aggregate(compiler)
  .settings(
    name := "fixed",
    publish / skip := true
  )
