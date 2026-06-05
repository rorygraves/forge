package io.forge.daemon

import scala.util.control.NonFatal

/** Minimal **JSON-RPC 2.0** message model + line codec for the daemon's status/control API (Phase-4 contract §6.3 / O2
  * — "JSON-RPC 2.0 over the Unix socket"). Slice-4.1 transport spike (Task 4.1.1).
  *
  * The wire framing is **newline-delimited JSON** (one JSON-RPC object per line), mirroring Forge's existing NDJSON log
  * idiom and letting [[DaemonSocketServer]] / [[DaemonClient]] split the byte stream with `fs2.text.lines`. `params`
  * and `result` are arbitrary `ujson.Value`s — method-specific payload shapes are decoded by the individual handlers,
  * not here, so this codec stays method-agnostic.
  *
  * Ids are numeric (a `Long`): the CLI/TUI clients always send an integer id. A response whose request could not be
  * parsed (so no id is recoverable) carries `id = None`, encoded as JSON `null` per the spec. Notifications (no id) are
  * not used in Slice 4.1.
  */
object JsonRpc:

  /** The protocol version string every message carries. */
  val Version: String = "2.0"

  /** A JSON-RPC request: a numeric `id`, a `method` name, and a `params` object (defaults to `{}`). */
  final case class Request(id: Long, method: String, params: ujson.Value = ujson.Obj())

  /** A JSON-RPC error object (`{code, message}`). The standard reserved codes are on the companion. */
  final case class RpcError(code: Int, message: String)
  object RpcError:
    /** Invalid JSON was received / the message was not a valid Request object. */
    val ParseError: Int = -32700

    /** The JSON was not a valid JSON-RPC request. */
    val InvalidRequest: Int = -32600

    /** The method does not exist. */
    val MethodNotFound: Int = -32601

    /** Invalid method parameters. */
    val InvalidParams: Int = -32602

    /** Internal JSON-RPC / handler error. */
    val InternalError: Int = -32603

    def methodNotFound(method: String): RpcError = RpcError(MethodNotFound, s"method not found: $method")
    def invalidParams(detail: String): RpcError = RpcError(InvalidParams, s"invalid params: $detail")
    def internal(detail: String): RpcError = RpcError(InternalError, s"internal error: $detail")

  /** A JSON-RPC response — either a `result` ([[Response.Success]]) or an `error` ([[Response.Failure]]). The `id`
    * echoes the request's id; it is `None` (encoded `null`) only when the request itself could not be parsed.
    */
  sealed trait Response:
    def id: Option[Long]
  object Response:
    final case class Success(id: Option[Long], result: ujson.Value) extends Response
    final case class Failure(id: Option[Long], error: RpcError) extends Response

    /** A success response echoing a known request id. */
    def ok(id: Long, result: ujson.Value): Response = Success(Some(id), result)

    /** A failure response against a known request id. */
    def fail(id: Long, error: RpcError): Response = Failure(Some(id), error)

  // --- line codec (one JSON object per line; the trailing '\n' is added by the transport) ---

  def encodeRequest(r: Request): String =
    ujson.write(
      ujson.Obj(
        "jsonrpc" -> Version,
        "id" -> ujson.Num(r.id.toDouble),
        "method" -> r.method,
        "params" -> r.params
      )
    )

  /** Decode one request line. A malformed line (bad JSON, missing `method`/`id`, wrong `jsonrpc`) yields a
    * [[RpcError.ParseError]] / [[RpcError.InvalidRequest]] on the left; the server answers it with a `null`-id failure.
    */
  def decodeRequest(line: String): Either[RpcError, Request] =
    try
      val o = ujson.read(line).obj
      val id = o.get("id") match
        case Some(j) if j != ujson.Null => j.num.toLong
        case _ => return Left(RpcError(RpcError.InvalidRequest, "missing or null request id"))
      val method = o.get("method") match
        case Some(m) => m.str
        case None => return Left(RpcError(RpcError.InvalidRequest, "missing method"))
      val params = o.getOrElse("params", ujson.Obj())
      Right(Request(id, method, params))
    catch case NonFatal(t) => Left(RpcError(RpcError.ParseError, s"unparseable request: ${t.getMessage}"))

  def encodeResponse(r: Response): String =
    val idJson: ujson.Value = r.id.map(i => ujson.Num(i.toDouble)).getOrElse(ujson.Null)
    r match
      case Response.Success(_, result) =>
        ujson.write(ujson.Obj("jsonrpc" -> Version, "id" -> idJson, "result" -> result))
      case Response.Failure(_, error) =>
        ujson.write(
          ujson.Obj(
            "jsonrpc" -> Version,
            "id" -> idJson,
            "error" -> ujson.Obj("code" -> ujson.Num(error.code.toDouble), "message" -> error.message)
          )
        )

  /** Decode one response line (client side). A malformed line yields a [[RpcError.ParseError]] on the left. */
  def decodeResponse(line: String): Either[RpcError, Response] =
    try
      val o = ujson.read(line).obj
      val id = o.get("id") match
        case Some(j) if j != ujson.Null => Some(j.num.toLong)
        case _ => None
      if o.contains("error") then
        val e = o("error").obj
        Right(Response.Failure(id, RpcError(e("code").num.toInt, e("message").str)))
      else Right(Response.Success(id, o.getOrElse("result", ujson.Null)))
    catch case NonFatal(t) => Left(RpcError(RpcError.ParseError, s"unparseable response: ${t.getMessage}"))
