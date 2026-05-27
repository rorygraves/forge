package io.forge.git.cli.fake

import cats.effect.IO
import io.forge.core.{BranchName, Sha}
import io.forge.git.cli.{FastForwardResult, GitClient, GitError}

/** Test-only [[GitClient]] stub. Same shape as [[FakeGhClient]] — builder DSL, "unconfigured" default for every method.
  * Keeps `BranchManager` / `PRWatcher` suites independent of a real git binary; live `git` semantics are exercised in
  * `forge-it` (PR-G).
  */
final class FakeGitClient private (
    currentBranchFn: IO[Either[GitError, BranchName]],
    currentShaFn: IO[Either[GitError, Sha]],
    fetchFn: String => IO[Either[GitError, Unit]],
    fastForwardBaseFn: BranchName => IO[Either[GitError, FastForwardResult]],
    checkoutFn: (BranchName, Option[String]) => IO[Either[GitError, Unit]],
    pushFn: (BranchName, Boolean, Boolean) => IO[Either[GitError, Unit]],
    tagFn: (String, Sha) => IO[Either[GitError, Unit]],
    pushTagFn: String => IO[Either[GitError, Unit]],
    deleteRemoteTagFn: String => IO[Either[GitError, Unit]],
    deleteLocalTagFn: String => IO[Either[GitError, Unit]],
    listTagsFn: Option[String] => IO[Either[GitError, Vector[String]]],
    isWorktreeCleanFn: IO[Either[GitError, Boolean]],
    branchExistsLocalFn: BranchName => IO[Either[GitError, Boolean]],
    branchExistsRemoteFn: BranchName => IO[Either[GitError, Boolean]]
) extends GitClient:

  override def currentBranch: IO[Either[GitError, BranchName]] = currentBranchFn
  override def currentSha: IO[Either[GitError, Sha]] = currentShaFn
  override def fetch(remote: String): IO[Either[GitError, Unit]] = fetchFn(remote)
  override def fastForwardBase(base: BranchName): IO[Either[GitError, FastForwardResult]] =
    fastForwardBaseFn(base)
  override def checkout(branch: BranchName, startPoint: Option[String]): IO[Either[GitError, Unit]] =
    checkoutFn(branch, startPoint)
  override def push(branch: BranchName, force: Boolean, forceWithLease: Boolean): IO[Either[GitError, Unit]] =
    pushFn(branch, force, forceWithLease)
  override def tag(name: String, sha: Sha): IO[Either[GitError, Unit]] = tagFn(name, sha)
  override def pushTag(name: String): IO[Either[GitError, Unit]] = pushTagFn(name)
  override def deleteRemoteTag(name: String): IO[Either[GitError, Unit]] = deleteRemoteTagFn(name)
  override def deleteLocalTag(name: String): IO[Either[GitError, Unit]] = deleteLocalTagFn(name)
  override def listTags(pattern: Option[String]): IO[Either[GitError, Vector[String]]] = listTagsFn(pattern)
  override def isWorktreeClean: IO[Either[GitError, Boolean]] = isWorktreeCleanFn
  override def branchExistsLocal(name: BranchName): IO[Either[GitError, Boolean]] = branchExistsLocalFn(name)
  override def branchExistsRemote(name: BranchName): IO[Either[GitError, Boolean]] = branchExistsRemoteFn(name)

object FakeGitClient:

  private def notConfigured[A](method: String): IO[Either[GitError, A]] =
    IO.pure(Left(GitError.Transient(-1, s"FakeGitClient.$method not configured")))

  final case class Builder private[FakeGitClient] (
      private val currentBranchFn: IO[Either[GitError, BranchName]] = notConfigured("currentBranch"),
      private val currentShaFn: IO[Either[GitError, Sha]] = notConfigured("currentSha"),
      private val fetchFn: String => IO[Either[GitError, Unit]] = (_: String) => notConfigured("fetch"),
      private val fastForwardBaseFn: BranchName => IO[Either[GitError, FastForwardResult]] = (_: BranchName) =>
        notConfigured("fastForwardBase"),
      private val checkoutFn: (BranchName, Option[String]) => IO[Either[GitError, Unit]] =
        (_: BranchName, _: Option[String]) => notConfigured("checkout"),
      private val pushFn: (BranchName, Boolean, Boolean) => IO[Either[GitError, Unit]] =
        (_: BranchName, _: Boolean, _: Boolean) => notConfigured("push"),
      private val tagFn: (String, Sha) => IO[Either[GitError, Unit]] = (_: String, _: Sha) => notConfigured("tag"),
      private val pushTagFn: String => IO[Either[GitError, Unit]] = (_: String) => notConfigured("pushTag"),
      private val deleteRemoteTagFn: String => IO[Either[GitError, Unit]] = (_: String) =>
        notConfigured("deleteRemoteTag"),
      private val deleteLocalTagFn: String => IO[Either[GitError, Unit]] = (_: String) =>
        notConfigured("deleteLocalTag"),
      private val listTagsFn: Option[String] => IO[Either[GitError, Vector[String]]] = (_: Option[String]) =>
        notConfigured("listTags"),
      private val isWorktreeCleanFn: IO[Either[GitError, Boolean]] = notConfigured("isWorktreeClean"),
      private val branchExistsLocalFn: BranchName => IO[Either[GitError, Boolean]] = (_: BranchName) =>
        notConfigured("branchExistsLocal"),
      private val branchExistsRemoteFn: BranchName => IO[Either[GitError, Boolean]] = (_: BranchName) =>
        notConfigured("branchExistsRemote")
  ):
    def currentBranch(response: Either[GitError, BranchName]): Builder = copy(currentBranchFn = IO.pure(response))
    def currentBranch(name: BranchName): Builder = currentBranch(Right(name))

    def currentSha(response: Either[GitError, Sha]): Builder = copy(currentShaFn = IO.pure(response))
    def currentSha(sha: Sha): Builder = currentSha(Right(sha))

    def fetch(fn: String => IO[Either[GitError, Unit]]): Builder = copy(fetchFn = fn)
    def fetch(response: Either[GitError, Unit]): Builder = fetch(_ => IO.pure(response))
    def fetchOk: Builder = fetch(Right(()))

    def fastForwardBase(fn: BranchName => IO[Either[GitError, FastForwardResult]]): Builder =
      copy(fastForwardBaseFn = fn)
    def fastForwardBase(response: Either[GitError, FastForwardResult]): Builder =
      fastForwardBase(_ => IO.pure(response))
    def fastForwardBase(result: FastForwardResult): Builder = fastForwardBase(Right(result))

    def checkout(fn: (BranchName, Option[String]) => IO[Either[GitError, Unit]]): Builder =
      copy(checkoutFn = fn)
    def checkout(response: Either[GitError, Unit]): Builder = checkout((_, _) => IO.pure(response))
    def checkoutOk: Builder = checkout(Right(()))

    def push(fn: (BranchName, Boolean, Boolean) => IO[Either[GitError, Unit]]): Builder = copy(pushFn = fn)
    def push(response: Either[GitError, Unit]): Builder = push((_, _, _) => IO.pure(response))
    def pushOk: Builder = push(Right(()))

    def tag(fn: (String, Sha) => IO[Either[GitError, Unit]]): Builder = copy(tagFn = fn)
    def tag(response: Either[GitError, Unit]): Builder = tag((_, _) => IO.pure(response))
    def tagOk: Builder = tag(Right(()))

    def pushTag(fn: String => IO[Either[GitError, Unit]]): Builder = copy(pushTagFn = fn)
    def pushTag(response: Either[GitError, Unit]): Builder = pushTag(_ => IO.pure(response))
    def pushTagOk: Builder = pushTag(Right(()))

    def deleteRemoteTag(fn: String => IO[Either[GitError, Unit]]): Builder = copy(deleteRemoteTagFn = fn)
    def deleteRemoteTag(response: Either[GitError, Unit]): Builder = deleteRemoteTag(_ => IO.pure(response))
    def deleteRemoteTagOk: Builder = deleteRemoteTag(Right(()))

    def deleteLocalTag(fn: String => IO[Either[GitError, Unit]]): Builder = copy(deleteLocalTagFn = fn)
    def deleteLocalTag(response: Either[GitError, Unit]): Builder = deleteLocalTag(_ => IO.pure(response))
    def deleteLocalTagOk: Builder = deleteLocalTag(Right(()))

    def listTags(fn: Option[String] => IO[Either[GitError, Vector[String]]]): Builder = copy(listTagsFn = fn)
    def listTags(response: Either[GitError, Vector[String]]): Builder = listTags(_ => IO.pure(response))
    def listTags(tags: Vector[String]): Builder = listTags(Right(tags))

    def isWorktreeClean(response: Either[GitError, Boolean]): Builder = copy(isWorktreeCleanFn = IO.pure(response))
    def isWorktreeClean(clean: Boolean): Builder = isWorktreeClean(Right(clean))

    def branchExistsLocal(fn: BranchName => IO[Either[GitError, Boolean]]): Builder =
      copy(branchExistsLocalFn = fn)
    def branchExistsLocal(response: Either[GitError, Boolean]): Builder =
      branchExistsLocal(_ => IO.pure(response))

    def branchExistsRemote(fn: BranchName => IO[Either[GitError, Boolean]]): Builder =
      copy(branchExistsRemoteFn = fn)
    def branchExistsRemote(response: Either[GitError, Boolean]): Builder =
      branchExistsRemote(_ => IO.pure(response))

    def build: FakeGitClient = new FakeGitClient(
      currentBranchFn,
      currentShaFn,
      fetchFn,
      fastForwardBaseFn,
      checkoutFn,
      pushFn,
      tagFn,
      pushTagFn,
      deleteRemoteTagFn,
      deleteLocalTagFn,
      listTagsFn,
      isWorktreeCleanFn,
      branchExistsLocalFn,
      branchExistsRemoteFn
    )

  def builder: Builder = Builder()
