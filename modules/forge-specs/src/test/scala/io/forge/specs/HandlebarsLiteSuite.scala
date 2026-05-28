package io.forge.specs

import io.forge.specs.HandlebarsLite.Value.*
import io.forge.specs.HandlebarsLite.{RenderError, Value}

/** Task 1.4.4 D2 — the Handlebars-subset renderer in isolation, against small hand-built templates so each construct
  * (interpolation, dotted paths, `#if`, `#each`, helpers, comments, standalone-line trimming) and each failure mode
  * (unbalanced / unknown / unterminated tags, unknown helper, non-scalar interpolation) is pinned independently of the
  * shipped `decomposition.md.hbs`. `DocSyncSuite` exercises the same renderer against the real template.
  */
class HandlebarsLiteSuite extends munit.FunSuite:

  private def render(t: String, root: Value.Obj, helpers: Map[String, String => String] = Map.empty): String =
    HandlebarsLite.render(t, root, helpers) match
      case Right(s) => s
      case Left(e) => fail(s"expected successful render, got $e")

  private def renderErr(t: String, root: Value.Obj = Obj(Map.empty)): RenderError =
    HandlebarsLite.render(t, root) match
      case Right(s) => fail(s"expected a RenderError, got successful render: '$s'")
      case Left(e) => e

  // --- interpolation ---------------------------------------------------------

  test("interpolates a top-level variable"):
    assertEquals(render("Hello {{name}}!", Obj(Map("name" -> Str("world")))), "Hello world!")

  test("interpolates a dotted path"):
    val root: Value.Obj = Obj(Map("feature" -> Obj(Map("title" -> Str("Stripe webhook")))))
    assertEquals(render("# {{feature.title}}", root), "# Stripe webhook")

  test("a missing key renders as the empty string"):
    assertEquals(render("x={{missing}}y", Obj(Map.empty)), "x=y")

  test("an Absent optional renders as the empty string"):
    assertEquals(render("x={{opt}}y", Obj(Map("opt" -> Absent))), "x=y")

  test("interpolating a non-scalar is an Eval error"):
    renderErr("{{feature}}", Obj(Map("feature" -> Obj(Map("a" -> Str("b")))))) match
      case RenderError.Eval(_) => ()
      case other => fail(s"expected Eval, got $other")

  // --- #if -------------------------------------------------------------------

  test("#if renders the body for a non-empty string"):
    assertEquals(render("{{#if x}}yes{{/if}}", Obj(Map("x" -> Str("v")))), "yes")

  test("#if skips the body for an empty string"):
    assertEquals(render("a{{#if x}}yes{{/if}}b", Obj(Map("x" -> Str("")))), "ab")

  test("#if skips the body for an Absent value"):
    assertEquals(render("a{{#if x}}yes{{/if}}b", Obj(Map("x" -> Absent))), "ab")

  test("#if skips the body for an empty array"):
    assertEquals(render("a{{#if xs}}yes{{/if}}b", Obj(Map("xs" -> Arr(Vector.empty)))), "ab")

  // --- #each -----------------------------------------------------------------

  test("#each iterates an array rebinding this"):
    val root: Value.Obj = Obj(Map("items" -> Arr(Vector(Obj(Map("n" -> Str("1"))), Obj(Map("n" -> Str("2")))))))
    assertEquals(render("{{#each items}}- {{this.n}}\n{{/each}}", root), "- 1\n- 2\n")

  test("#each over a missing/absent path renders nothing"):
    assertEquals(render("x{{#each items}}{{this.n}}{{/each}}y", Obj(Map.empty)), "xy")

  test("nested #each containing #if"):
    val root: Value.Obj = Obj(
      Map(
        "items" -> Arr(
          Vector(
            Obj(Map("n" -> Str("a"), "flag" -> Str("y"))),
            Obj(Map("n" -> Str("b"), "flag" -> Absent))
          )
        )
      )
    )
    assertEquals(render("{{#each items}}{{this.n}}{{#if this.flag}}!{{/if}} {{/each}}", root), "a! b ")

  // --- helpers ---------------------------------------------------------------

  test("a two-token interpolation invokes a registered helper"):
    val helpers = Map[String, String => String]("shout" -> (_.toUpperCase))
    assertEquals(render("{{shout name}}", Obj(Map("name" -> Str("hi"))), helpers), "HI")

  test("a helper sees the resolved scalar of its argument path"):
    val helpers = Map[String, String => String]("badge" -> (s => s"[$s]"))
    val root: Value.Obj = Obj(Map("p" -> Obj(Map("status" -> Str("merged")))))
    assertEquals(render("{{badge p.status}}", root, helpers), "[merged]")

  test("an unknown helper is an Eval error"):
    renderErr("{{frobnicate x}}", Obj(Map("x" -> Str("v")))) match
      case RenderError.Eval(_) => ()
      case other => fail(s"expected Eval, got $other")

  // --- comments --------------------------------------------------------------

  test("block comments are stripped"):
    assertEquals(render("a{{!-- a multi\nline comment --}}b", Obj(Map.empty)), "ab")

  test("short comments are stripped"):
    assertEquals(render("a{{! short }}b", Obj(Map.empty)), "ab")

  // --- standalone-line trimming ----------------------------------------------

  test("a standalone #if tag pair does not leave blank lines"):
    assertEquals(render("a\n{{#if x}}\nb\n{{/if}}\nc\n", Obj(Map("x" -> Str("y")))), "a\nb\nc\n")

  test("a standalone #if that evaluates false consumes its own lines"):
    assertEquals(render("a\n{{#if x}}\nb\n{{/if}}\nc\n", Obj(Map("x" -> Absent))), "a\nc\n")

  test("a standalone leading comment does not leave a blank first line"):
    assertEquals(render("{{!-- header --}}\n# Title\n", Obj(Map.empty)), "# Title\n")

  test("an interpolation tag is not treated as standalone"):
    assertEquals(render("a\n{{name}}\nb\n", Obj(Map("name" -> Str("X")))), "a\nX\nb\n")

  // --- parse / structural errors ---------------------------------------------

  test("an unclosed block is a Parse error"):
    renderErr("{{#if x}}body") match
      case RenderError.Parse(_) => ()
      case other => fail(s"expected Parse, got $other")

  test("a stray closing tag is a Parse error"):
    renderErr("body{{/if}}") match
      case RenderError.Parse(_) => ()
      case other => fail(s"expected Parse, got $other")

  test("a mismatched closing tag kind is a Parse error"):
    renderErr("{{#if x}}{{/each}}") match
      case RenderError.Parse(_) => ()
      case other => fail(s"expected Parse, got $other")

  test("an unterminated tag is a Parse error"):
    renderErr("prefix {{ name") match
      case RenderError.Parse(_) => ()
      case other => fail(s"expected Parse, got $other")

  test("an unterminated block comment is a Parse error"):
    renderErr("a {{!-- never closed") match
      case RenderError.Parse(_) => ()
      case other => fail(s"expected Parse, got $other")

  test("an unknown block helper is a Parse error"):
    renderErr("{{#wat}}{{/wat}}") match
      case RenderError.Parse(_) => ()
      case other => fail(s"expected Parse, got $other")
