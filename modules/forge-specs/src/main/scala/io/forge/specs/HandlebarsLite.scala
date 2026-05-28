package io.forge.specs

import scala.collection.mutable.ArrayBuffer
import scala.util.boundary
import scala.util.boundary.break

/** Task 1.4.4 D2 — a deliberately small Handlebars-subset renderer.
  *
  * `forge-specs` renders one file (`decomposition.md`) from one template (`decomposition.md.hbs`). Rather than add a
  * third-party Handlebars dependency (the maintained option is the Java `com.github.jknack:handlebars`; the Scala
  * `handlebars.scala` is Scala-2-only and won't build on 3.5.2), this renders the small construct set the shipped
  * template actually uses, keeping the module at its `osLib` + `upickle` dependency floor. The decision is recorded in
  * `docs/design-rationale.md` §"PR body & templates" T2. If a future template needs constructs beyond this set, the
  * escape hatch is the same dependency that was rejected here.
  *
  * Supported constructs:
  *   - `{{ path }}` — interpolation; `path` is dot-separated (`feature.title`, `this.id`, or bare `this`). A missing
  *     key renders as the empty string (mustache-lenient), matching how Handlebars treats absent values.
  *   - `{{ helper path }}` — a two-token interpolation invokes a named helper from the `helpers` map on the resolved
  *     string argument. An unknown helper name is an [[RenderError.Eval]] (it's a template bug, not absent data).
  *   - `{{#if path}} … {{/if}}` — renders the body when `path` resolves to a truthy value (non-empty string / array /
  *     object; an absent value is falsy).
  *   - `{{#each path}} … {{/each}}` — iterates an array, rebinding `this` to each element for the body. A non-array /
  *     absent `path` renders nothing.
  *   - `{{!-- … --}}` and `{{! … }}` — comments, stripped.
  *
  * Standalone block/comment tags (a section open/close or comment alone on its line) consume their whole line per the
  * mustache standalone rule, so the rendered output doesn't carry a blank line for every control tag. Interpolation
  * tags are never standalone — they're expected to emit visible text mid-line.
  */
object HandlebarsLite:

  /** Render context. Optionals map to [[Value.Absent]] (falsy, renders empty) rather than an empty string, so `{{#if}}`
    * can distinguish "field is None" from "field is the empty string".
    */
  enum Value:
    case Str(value: String)
    case Arr(items: Vector[Value])
    case Obj(fields: Map[String, Value])
    case Absent

  /** Failure channel. Parse failures are template-shape errors (unbalanced/unknown tags); eval failures are
    * resolve-time errors (unknown helper, a non-scalar where a scalar was needed). `FileDocSync` maps these to
    * `DocSyncError.TemplateMalformed` and `DocSyncError.RenderFailure` respectively.
    */
  enum RenderError:
    case Parse(message: String)
    case Eval(message: String)

  def render(
      template: String,
      root: Value.Obj,
      helpers: Map[String, String => String] = Map.empty
  ): Either[RenderError, String] =
    for
      tokens <- tokenize(template)
      trimmed = applyStandalone(tokens)
      nodes <- parseNodes(trimmed)
      out <- evalNodes(nodes, root, root, helpers)
    yield out

  // --- tokens / nodes --------------------------------------------------------

  private enum BlockKind:
    case If, Each

  private enum Tok:
    case Text(s: String)
    case Var(expr: String)
    case Open(kind: BlockKind, arg: String)
    case Close(kind: BlockKind)
    case Comment

  private enum Node:
    case Text(s: String)
    case Var(expr: String)
    case If(arg: String, body: Vector[Node])
    case Each(arg: String, body: Vector[Node])

  // --- tokenizer -------------------------------------------------------------

  private def tokenize(template: String): Either[RenderError, Vector[Tok]] = boundary:
    val toks = ArrayBuffer.empty[Tok]
    val n = template.length
    var i = 0
    var textStart = 0

    def flushText(end: Int): Unit =
      if end > textStart then toks.append(Tok.Text(template.substring(textStart, end)))

    while i < n do
      if i + 1 < n && template(i) == '{' && template(i + 1) == '{' then
        flushText(i)
        if template.startsWith("{{!--", i) then
          val close = template.indexOf("--}}", i + 5)
          if close < 0 then break(Left(RenderError.Parse("unterminated {{!-- comment")))
          toks.append(Tok.Comment)
          i = close + 4
        else if template.startsWith("{{!", i) then
          val close = template.indexOf("}}", i + 3)
          if close < 0 then break(Left(RenderError.Parse("unterminated {{! comment")))
          toks.append(Tok.Comment)
          i = close + 2
        else
          val close = template.indexOf("}}", i + 2)
          if close < 0 then break(Left(RenderError.Parse("unterminated {{ tag")))
          val inner = template.substring(i + 2, close).trim
          if inner.startsWith("#") then
            val (head, arg) = splitFirstWord(inner.drop(1).trim)
            head match
              case "if" => toks.append(Tok.Open(BlockKind.If, arg))
              case "each" => toks.append(Tok.Open(BlockKind.Each, arg))
              case other => break(Left(RenderError.Parse(s"unknown block helper '#$other'")))
          else if inner.startsWith("/") then
            inner.drop(1).trim match
              case "if" => toks.append(Tok.Close(BlockKind.If))
              case "each" => toks.append(Tok.Close(BlockKind.Each))
              case other => break(Left(RenderError.Parse(s"unknown closing tag '/$other'")))
          else toks.append(Tok.Var(inner))
          i = close + 2
        textStart = i
      else i += 1

    flushText(n)
    Right(toks.toVector)

  private def splitFirstWord(s: String): (String, String) =
    s.split("\\s+", 2) match
      case Array(head, rest) => (head, rest.trim)
      case Array(head) => (head, "")
      case _ => ("", "")

  // --- standalone-line trimming ---------------------------------------------

  private def isLineBlank(s: String): Boolean = s.forall(c => c == ' ' || c == '\t' || c == '\r')

  private def applyStandalone(toks: Vector[Tok]): Vector[Tok] =
    val arr = toks.toArray
    def blockOrComment(t: Tok): Boolean = t match
      case _: Tok.Open | _: Tok.Close | Tok.Comment => true
      case _ => false

    val standalone = Array.fill(arr.length)(false)
    for k <- arr.indices if blockOrComment(arr(k)) do
      // A tag is standalone only when it is alone on its source line. A neighbouring text without a newline means the
      // line continues into another token (so NOT standalone) — except at the true start/end of input.
      val leftOk = k == 0 || (arr(k - 1) match
        case Tok.Text(t) =>
          if t.indexOf('\n') >= 0 then isLineBlank(t.substring(t.lastIndexOf('\n') + 1))
          else k - 1 == 0 && isLineBlank(t)
        case _ => false
      )
      val rightOk = k == arr.length - 1 || (arr(k + 1) match
        case Tok.Text(t) =>
          val nl = t.indexOf('\n')
          if nl >= 0 then isLineBlank(t.substring(0, nl))
          else k + 1 == arr.length - 1 && isLineBlank(t)
        case _ => false
      )
      standalone(k) = leftOk && rightOk

    for k <- arr.indices if standalone(k) do
      if k > 0 then
        arr(k - 1) match
          case Tok.Text(t) => arr(k - 1) = Tok.Text(t.substring(0, t.lastIndexOf('\n') + 1))
          case _ => ()
      if k < arr.length - 1 then
        arr(k + 1) match
          case Tok.Text(t) =>
            val nl = t.indexOf('\n')
            arr(k + 1) = Tok.Text(if nl < 0 then "" else t.substring(nl + 1))
          case _ => ()

    arr.toVector

  // --- parser ----------------------------------------------------------------

  private def parseNodes(toks: Vector[Tok]): Either[RenderError, Vector[Node]] = boundary:
    var i = 0

    def parseUntil(closer: Option[BlockKind]): Vector[Node] =
      val nodes = ArrayBuffer.empty[Node]
      while i < toks.length do
        toks(i) match
          case Tok.Text(s) =>
            nodes.append(Node.Text(s)); i += 1
          case Tok.Var(e) =>
            nodes.append(Node.Var(e)); i += 1
          case Tok.Comment =>
            i += 1
          case Tok.Open(kind, arg) =>
            i += 1
            val body = parseUntil(Some(kind))
            nodes.append(if kind == BlockKind.If then Node.If(arg, body) else Node.Each(arg, body))
          case Tok.Close(kind) =>
            closer match
              case Some(expected) if expected == kind =>
                i += 1
                return nodes.toVector
              case _ => break(Left(RenderError.Parse(s"unexpected closing tag {{/${kind.toString.toLowerCase}}}")))
      if closer.isDefined then break(Left(RenderError.Parse("unclosed block — missing closing tag")))
      nodes.toVector

    Right(parseUntil(None))

  // --- evaluator -------------------------------------------------------------

  private def evalNodes(
      nodes: Vector[Node],
      root: Value.Obj,
      scope: Value,
      helpers: Map[String, String => String]
  ): Either[RenderError, String] =
    nodes.foldLeft[Either[RenderError, String]](Right("")): (acc, node) =>
      acc.flatMap(prefix => evalNode(node, root, scope, helpers).map(prefix + _))

  private def evalNode(
      node: Node,
      root: Value.Obj,
      scope: Value,
      helpers: Map[String, String => String]
  ): Either[RenderError, String] = node match
    case Node.Text(s) => Right(s)
    case Node.Var(expr) => evalExpr(expr, root, scope, helpers)
    case Node.If(arg, body) =>
      if truthy(resolve(arg, root, scope)) then evalNodes(body, root, scope, helpers) else Right("")
    case Node.Each(arg, body) =>
      resolve(arg, root, scope) match
        case Value.Arr(items) =>
          items.foldLeft[Either[RenderError, String]](Right("")): (acc, item) =>
            acc.flatMap(prefix => evalNodes(body, root, item, helpers).map(prefix + _))
        case _ => Right("")

  private def evalExpr(
      expr: String,
      root: Value.Obj,
      scope: Value,
      helpers: Map[String, String => String]
  ): Either[RenderError, String] =
    expr.split("\\s+").toVector.filter(_.nonEmpty) match
      case Vector(path) => renderScalar(resolve(path, root, scope), expr)
      case Vector(name, argPath) =>
        helpers.get(name) match
          case Some(fn) => renderScalar(resolve(argPath, root, scope), argPath).map(fn)
          case None => Left(RenderError.Eval(s"unknown helper '$name'"))
      case _ => Left(RenderError.Eval(s"unsupported expression '$expr'"))

  private def renderScalar(v: Value, expr: String): Either[RenderError, String] = v match
    case Value.Str(s) => Right(s)
    case Value.Absent => Right("")
    case _ => Left(RenderError.Eval(s"cannot render non-scalar value for '$expr'"))

  private def resolve(path: String, root: Value.Obj, scope: Value): Value =
    path.split("\\.").toVector.filter(_.nonEmpty) match
      case Vector("this") => scope
      case "this" +: rest => descend(scope, rest)
      case segs =>
        descend(scope, segs) match
          case Value.Absent if !(scope eq root) => descend(root, segs)
          case other => other

  private def descend(v: Value, segs: Vector[String]): Value =
    segs.foldLeft(v): (cur, key) =>
      cur match
        case Value.Obj(fields) => fields.getOrElse(key, Value.Absent)
        case _ => Value.Absent

  private def truthy(v: Value): Boolean = v match
    case Value.Str(s) => s.nonEmpty
    case Value.Arr(items) => items.nonEmpty
    case Value.Obj(fields) => fields.nonEmpty
    case Value.Absent => false
