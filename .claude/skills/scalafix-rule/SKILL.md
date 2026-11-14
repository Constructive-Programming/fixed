---
name: scalafix-rule
description: Add a custom scalafix rule to this repo test-first. Use when asked to "add a scalafix rule", "lint for X", "forbid X", "enforce X with scalafix", or "write a rewrite that turns X into Y". Takes the desired goal, confirms a few concrete scenarios with the user, captures them as scalafix-testkit tests, then implements the rule red → green and enables it in the quality gate.
---

# Custom scalafix rule, test-first

Input: a goal in plain words ("forbid `println` outside tests", "rewrite `x.headOption.exists(p)`
to `x.nonEmpty && p(x.head)`"). Output: a tested rule under `scalafix/rules`, enabled in
`.scalafix.conf`, with the codebase passing `sbt check test`.

## 1. Pin down the rule — confirm before writing anything

1. Restate the goal as one rule: name (`UpperCamel`, e.g. `NoPrintln`), **lint** (report) or
   **rewrite** (patch), and **syntactic** (tree shape only) or **semantic** (needs symbols/types —
   e.g. "calls to `scala.Predef.println`", not just any `println`). Prefer syntactic when it is
   precise enough.
2. Measure impact first: grep the code for the pattern so the user sees how many sites it hits.
3. Propose 2–4 **scenarios**, each a tiny Scala 3 snippet with the expected outcome:
   - at least one **positive** (must be flagged / rewritten, and to what),
   - at least one **negative** (looks similar, must NOT trigger),
   - an edge case if one is obvious (nested, qualified name, inside a string/comment, test code).
4. Ask the user to confirm or adjust the scenarios (AskUserQuestion). Do not write tests until
   they agree — the scenarios are the spec.

## 2. Bootstrap (only if `scalafix/` does not exist yet)

Add to `build.sbt` above `lazy val compiler` (verified with scalafix 0.14.10, Scala 3.10.1):

```scala
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
  .disablePlugins(ScalafixPlugin)
  .settings(publish / skip := true, scalacOptions --= Seq("-Werror", "-Wunused:all", "-Wvalue-discard"))

lazy val `scalafix-output` = (project in file("scalafix/output"))
  .disablePlugins(ScalafixPlugin)
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
```

Then:
- `compiler` gets `.dependsOn(`scalafix-rules` % ScalafixConfig)` so `.scalafix.conf` can name
  local rules.
- Add `scalafix-tests` to `root`'s `.aggregate(...)` so `sbt test` (pre-commit + CI) runs the
  rule tests.
- `scalafix/tests/src/test/scala/fix/RuleSuite.scala`:
  ```scala
  package fix

  import org.scalatest.funsuite.AnyFunSuiteLike
  import scalafix.testkit.AbstractSemanticRuleSuite

  class RuleSuite extends AbstractSemanticRuleSuite with AnyFunSuiteLike:
    runAllTests()
  ```
- If the testkit lags a newer `scalaVersion`, bump `scalafixTestkitScala` to the newest
  `ch.epfl.scala:scalafix-testkit_3.x.y` on Maven Central.

## 3. RED — scenarios as testkit files

One input file per rule: `scalafix/input/src/main/scala/fix/<Rule>Test.scala`.

```scala
/*
rule = NoPrintln
 */
package fix

object NoPrintlnTest:
  def bad(): Unit = println("x") // assert: NoPrintln
  def ok(): Unit = System.out.flush()
```

- Header comment names the rule (and any `<Rule>.option = …` config).
- **Lint:** mark each expected diagnostic with `// assert: <Rule>` on that line. For a precise
  position/message use the caret form: `expr /* assert: <Rule>` / `^^^^` / `message` / `*/`.
- **Rewrite:** add `scalafix/output/src/main/scala/fix/<Rule>Test.scala` with the exact expected
  result (same package/object, no header). Output mirrors input file by file.
- Negative scenarios are plain code with no `assert` — any diagnostic on them fails the test.

Run `sbt "scalafix-tests/testOnly fix.RuleSuite -- -z <Rule>"` and confirm it fails **for the
right reason** (missing diagnostic / wrong output), not a compile error. Fix the test file if
it is the latter.

## 4. GREEN — minimal rule

`scalafix/rules/src/main/scala/fix/<Rule>.scala`, registered by adding `fix.<Rule>` as a line in
`scalafix/rules/src/main/resources/META-INF/services/scalafix.v1.Rule`.

```scala
package fix

import scalafix.v1.*
import scala.meta.*

class NoPrintln extends SemanticRule("NoPrintln"):
  override def fix(using doc: SemanticDocument): Patch =
    doc.tree.collect {
      case t @ Term.Apply.After_4_6_0(Term.Name("println"), _) =>
        Patch.lint(Diagnostic("", "use Reporter instead of println", t.pos))
    }.asPatch
```

- `SyntacticRule` + `SyntacticDocument` when no symbols are needed.
- Semantic matching: `SymbolMatcher.normalized("scala.Predef.println")` and `t.symbol`.
- Rewrites: `Patch.replaceTree(t, "…")`, `Patch.addLeft/addRight`, `Patch.removeTokens`.
- Use the `*.After_4_6_0` extractors (`Term.Apply.After_4_6_0`, …) — the plain ones are deprecated.

Rerun the suite until green. If implementing reveals a case the scenarios missed, add it as a
new scenario (red first), and tell the user.

## 5. Enable and clean the codebase

1. Add the rule to `rules = [...]` in `.scalafix.conf`.
2. `sbt "scalafixAll --check"` — this covers main **and** test sources.
3. Every hit: rewrite rules → `sbt scalafixAll`; lint rules → fix the code. If hits are legitimate
   (e.g. `println` in benchmarks), ask the user whether to narrow the rule (new scenario, back to
   step 3) or to suppress per file with `// scalafix:off <Rule>` plus a reason. Never suppress
   silently.

## 6. Gate and commit

`sbt check test` must pass (it also runs the rule tests via the aggregate). Commit with a
Conventional Commit, e.g. `feat(build): add NoPrintln scalafix rule` — body lists the confirmed
scenarios.
