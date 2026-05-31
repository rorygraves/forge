package io.forge.git.cli.fake

import cats.effect.IO
import io.forge.core.{BranchName, PrNumber}
import io.forge.git.cli.{GhClient, GhError}

import java.util.concurrent.atomic.AtomicInteger

/** Test-only [[GhClient]] stub. Build with the [[FakeGhClient.builder]] DSL and pass to suites that exercise
  * `BranchManager` / `PRWatcher` / decoder logic without spawning a real `gh` process.
  *
  * Each method falls back to an "unconfigured" `Transient` error when the builder didn't register a response — keeps a
  * suite from silently passing because it forgot to wire a stub.
  *
  * Example:
  * {{{
  * val gh = FakeGhClient.builder
  *   .prView(_ => IO.pure(Right(ujson.read(\"\"\"{"state":"OPEN"}\"\"\"))))
  *   .prCreate(Right(PrNumber(42)))
  *   .build
  * }}}
  */
final class FakeGhClient private (
    prViewFn: PrNumber => IO[Either[GhError, ujson.Value]],
    prCreateFn: (String, String, BranchName, BranchName) => IO[Either[GhError, PrNumber]],
    prUpdateBranchFn: PrNumber => IO[Either[GhError, Unit]],
    prDiffFn: PrNumber => IO[Either[GhError, String]],
    apiBranchProtectionFn: BranchName => IO[Either[GhError, Option[ujson.Value]]],
    prChecksFn: PrNumber => IO[Either[GhError, String]]
) extends GhClient:

  override def prView(pr: PrNumber, fields: Vector[String]): IO[Either[GhError, ujson.Value]] = prViewFn(pr)

  override def prCreate(
      title: String,
      body: String,
      base: BranchName,
      head: BranchName
  ): IO[Either[GhError, PrNumber]] = prCreateFn(title, body, base, head)

  override def prUpdateBranch(pr: PrNumber): IO[Either[GhError, Unit]] = prUpdateBranchFn(pr)

  override def prDiff(pr: PrNumber): IO[Either[GhError, String]] = prDiffFn(pr)

  override def apiBranchProtection(base: BranchName): IO[Either[GhError, Option[ujson.Value]]] =
    apiBranchProtectionFn(base)

  override def prChecks(pr: PrNumber): IO[Either[GhError, String]] = prChecksFn(pr)

object FakeGhClient:

  private def notConfigured[A](method: String): IO[Either[GhError, A]] =
    IO.pure(Left(GhError.Transient(-1, s"FakeGhClient.$method not configured")))

  final case class Builder private[FakeGhClient] (
      private val prViewFn: PrNumber => IO[Either[GhError, ujson.Value]] = (_: PrNumber) => notConfigured("prView"),
      private val prCreateFn: (String, String, BranchName, BranchName) => IO[Either[GhError, PrNumber]] =
        (_: String, _: String, _: BranchName, _: BranchName) => notConfigured("prCreate"),
      private val prUpdateBranchFn: PrNumber => IO[Either[GhError, Unit]] = (_: PrNumber) =>
        notConfigured("prUpdateBranch"),
      private val prDiffFn: PrNumber => IO[Either[GhError, String]] = (_: PrNumber) => notConfigured("prDiff"),
      private val apiBranchProtectionFn: BranchName => IO[Either[GhError, Option[ujson.Value]]] = (_: BranchName) =>
        notConfigured("apiBranchProtection"),
      private val prChecksFn: PrNumber => IO[Either[GhError, String]] = (_: PrNumber) => notConfigured("prChecks")
  ):
    def prView(fn: PrNumber => IO[Either[GhError, ujson.Value]]): Builder = copy(prViewFn = fn)
    def prView(response: Either[GhError, ujson.Value]): Builder = prView(_ => IO.pure(response))
    def prView(json: ujson.Value): Builder = prView(Right(json))

    /** Return responses in order; further calls past `responses.size` get an exhausted-sequence error. Useful for
      * `PRWatcher` polling tests where each poll should see a specific snapshot.
      */
    def prViewSequence(responses: Vector[Either[GhError, ujson.Value]]): Builder =
      val idx = new AtomicInteger(0)
      prView(_ =>
        IO.delay {
          val i = idx.getAndIncrement()
          if i < responses.size then responses(i)
          else Left(GhError.Transient(-1, s"prView: exhausted ${responses.size} responses"))
        }
      )

    def prCreate(fn: (String, String, BranchName, BranchName) => IO[Either[GhError, PrNumber]]): Builder =
      copy(prCreateFn = fn)
    def prCreate(response: Either[GhError, PrNumber]): Builder =
      prCreate((_, _, _, _) => IO.pure(response))
    def prCreate(pr: PrNumber): Builder = prCreate(Right(pr))

    def prUpdateBranch(fn: PrNumber => IO[Either[GhError, Unit]]): Builder = copy(prUpdateBranchFn = fn)
    def prUpdateBranch(response: Either[GhError, Unit]): Builder = prUpdateBranch(_ => IO.pure(response))

    def prDiff(fn: PrNumber => IO[Either[GhError, String]]): Builder = copy(prDiffFn = fn)
    def prDiff(diff: String): Builder = prDiff(_ => IO.pure(Right(diff)))

    def apiBranchProtection(fn: BranchName => IO[Either[GhError, Option[ujson.Value]]]): Builder =
      copy(apiBranchProtectionFn = fn)
    def apiBranchProtection(response: Either[GhError, Option[ujson.Value]]): Builder =
      apiBranchProtection(_ => IO.pure(response))

    def prChecks(fn: PrNumber => IO[Either[GhError, String]]): Builder = copy(prChecksFn = fn)
    def prChecks(report: String): Builder = prChecks(_ => IO.pure(Right(report)))

    def build: FakeGhClient =
      new FakeGhClient(prViewFn, prCreateFn, prUpdateBranchFn, prDiffFn, apiBranchProtectionFn, prChecksFn)

  def builder: Builder = Builder()
