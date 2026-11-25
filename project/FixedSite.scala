package fixed.build

import laika.ast.Path.Root
import laika.helium.config.{TextLink, ThemeNavigationSection}

/** Sidebar groups and reading order for the Fixed site.
  *
  * The auto-generated tree is limited to the landing pages (see
  * `site/docs/directory.conf`); every other page is listed here so the
  * navigation carries the site's information architecture rather than the
  * order of the source directories. `readingOrder` is the same sequence,
  * flattened, and drives the previous/next links appended by
  * [[FixedSpecSplit]]. Spec pages keep their root URLs, because `spec/` is
  * served directly by Laika.
  */
object FixedSite {

  final case class Page(path: String, title: String) {
    def link: TextLink = TextLink.internal(Root / path, title)
  }

  val index: Page = Page("index.md", "Fixed")
  val gettingStarted: Page = Page("getting-started.md", "Getting started")
  val typeSystem: Page = Page("type_system.md", "Type system")
  val effects: Page = Page("effects.md", "Effects")
  val patternMatching: Page = Page("pattern_matching.md", "Pattern matching")
  val properties: Page = Page("properties.md", "Properties")
  val quantities: Page = Page("quantities.md", "Quantities (QTT)")
  val memoryManagement: Page = Page("memory_management.md", "Memory management")
  val compilationModel: Page = Page("compilation_model.md", "Compilation model")
  val cost: Page = Page("cost.md", "Cost contracts")
  val diagnostics: Page = Page("diagnostics.md", "Diagnostics")
  val reports: Page = Page("reports.md", "Reports")
  val benchmarks: Page = Page("benchmarks.md", "Benchmarks")
  val stdlib: Page = Page("stdlib.md", "Standard library")
  val editors: Page = Page("editors.md", "Editor support")
  val cookbookOverview: Page = Page("cookbook/README.md", "Overview")
  val capabilities: Page = Page("cookbook/capabilities-not-data.md", "Capabilities, not data")
  val errorHandling: Page = Page("cookbook/error-handling.md", "Error handling without data")
  val recursionSchemes: Page = Page("cookbook/recursion-schemes.md", "Recursion schemes")
  val workingWithJson: Page = Page("cookbook/working-with-json.md", "Working with JSON")
  val phantomTypes: Page = Page("cookbook/phantom-types.md", "Phantom types")
  val effectsAndHandlers: Page = Page("cookbook/effects-and-handlers.md", "Effects and handlers")
  val cookbookProperties: Page = Page("cookbook/properties.md", "Properties")
  val referencesOverview: Page = Page("references/README.md", "Overview")
  val constructive: Page = Page("references/constructive.md", "Constructive")
  val eo: Page = Page("references/eo.md", "eo")
  val assumptions: Page = Page("references/assumptions.md", "Assumptions and limits")
  val bibliography: Page = Page("references/bibliography.md", "Bibliography")

  /** The spec documents the tree processor splits into section pages. */
  val specIndexes: Seq[String] = Seq(
    "compilation_model.md",
    "cost.md",
    "diagnostics.md",
    "effects.md",
    "memory_management.md",
    "pattern_matching.md",
    "properties.md",
    "quantities.md",
    "reports.md",
    "stdlib.md",
    "type_system.md"
  )

  private val languageSpecifications: Seq[Page] =
    Seq(typeSystem, effects, patternMatching, properties, quantities, memoryManagement)

  private val compilerDetails: Seq[Page] =
    Seq(compilationModel, cost, diagnostics, reports, benchmarks)

  private val ecosystem: Seq[Page] = Seq(stdlib, editors)

  private val cookbook: Seq[Page] = Seq(
    cookbookOverview,
    capabilities,
    errorHandling,
    recursionSchemes,
    workingWithJson,
    phantomTypes,
    effectsAndHandlers,
    cookbookProperties
  )

  private val references: Seq[Page] =
    Seq(referencesOverview, constructive, eo, assumptions, bibliography)

  /** The linear order used by the previous/next links at the end of each page. */
  val readingOrder: Seq[Page] =
    index +: gettingStarted +: (languageSpecifications ++ compilerDetails ++ ecosystem ++ cookbook ++ references)

  private val grammar: TextLink =
    TextLink.external("https://fixed.constructive.dev/syntax_grammar.ebnf", "Syntax grammar (EBNF)")

  private def section(title: String, links: Seq[TextLink]): ThemeNavigationSection =
    ThemeNavigationSection(title, links.head, links.tail: _*)

  val navigation: Seq[ThemeNavigationSection] = Seq(
    section(
      "Language Specifications",
      Seq(typeSystem.link, grammar) ++ languageSpecifications.tail.map(_.link)
    ),
    section("Compiler Details", compilerDetails.map(_.link)),
    section("Ecosystem", ecosystem.map(_.link)),
    section("Cookbook", cookbook.map(_.link)),
    section("References", references.map(_.link))
  )
}
