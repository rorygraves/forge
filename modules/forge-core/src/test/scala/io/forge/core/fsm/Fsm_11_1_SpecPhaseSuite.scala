package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*

/** §11.1 — Spec phase. Drafting → InteractiveSpec (on spec-driver spawn), InteractiveSpec → DesignReviewing(1) on
  * settle clean, NHI on settle timeout / adapter error / turn budget breach.
  */
class Fsm_11_1_SpecPhaseSuite extends munit.FunSuite:

  test("Drafting + SessionSpawned(driver, sid, None) → InteractiveSpec, designSessionId set"):
    val f = featureIn(FsmState.Drafting)
    val (out, drafts) = Fsm.transition(f, FsmEvent.SessionSpawned("claude", "driver", "sess-1", None))
    assertEquals(out.state, FsmState.InteractiveSpec)
    assertEquals(out.designSessionId, Some("sess-1"))
    // gap #7: the spec spawn emits BOTH the fsm.transition (seq-0 record) and the `<actor>.spawn` durability entry
    // so `designSessionId` survives a cold rebuild from the log (a `forge run` restart after `forge spec`).
    assertEquals(drafts.size, 2)
    assertEquals(drafts.head.kind, "fsm.transition")
    assertEquals(drafts.head.payload("from").str, "Drafting")
    assertEquals(drafts.head.payload("to").str, "InteractiveSpec")
    val spawn = drafts(1)
    assertEquals(spawn.kind, "claude.spawn")
    assertEquals(spawn.actor, Some("claude"))
    assertEquals(
      spawn.piece,
      None,
      "design spawn carries no piece (projects designSessionId, not currentPieceSessionId)"
    )
    assertEquals(spawn.payload("sessionId").str, "sess-1")

  test("InteractiveSpec + Settled(Spec, Clean) → DesignReviewing(1)"):
    val f = featureIn(FsmState.InteractiveSpec, designSessionId = Some("sess-1"))
    val (out, drafts) = Fsm.transition(f, FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.Clean))
    assertEquals(out.state, FsmState.DesignReviewing(round = 1))
    assertEquals(out.designSessionId, Some("sess-1"), "designSessionId persists through to DesignReviewing")
    assertEquals(drafts.size, 1)

  test("InteractiveSpec + UserCommand.Done → DesignReviewing(1) (defensive alternate trigger)"):
    val f = featureIn(FsmState.InteractiveSpec, designSessionId = Some("sess-1"))
    val (out, _) = Fsm.transition(f, FsmEvent.UserCommandReceived(UserCommand.Done))
    assertEquals(out.state, FsmState.DesignReviewing(round = 1))

  test("InteractiveSpec + SettleTimeout(Spec) → NHI(AbortOrAbandon)"):
    val f = featureIn(FsmState.InteractiveSpec, designSessionId = Some("sess-1"))
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.Spec, "bound exceeded"))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.AbortOrAbandon) => ()
      case other => fail(s"expected NHI(AbortOrAbandon), got $other")

  test("InteractiveSpec + TurnBudgetBreached(Spec) → NHI"):
    val f = featureIn(FsmState.InteractiveSpec, designSessionId = Some("sess-1"))
    val (out, _) = Fsm.transition(f, FsmEvent.TurnBudgetBreached(SessionPhase.Spec, "$2.05 > $2.00"))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])

  test("InteractiveSpec + Settled(Spec, AdapterError) → NHI(AbortOrAbandon)"):
    val f = featureIn(FsmState.InteractiveSpec, designSessionId = Some("sess-1"))
    val (out, _) = Fsm.transition(f, FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.AdapterError("boom")))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.AbortOrAbandon) => ()
      case other => fail(s"expected NHI(AbortOrAbandon), got $other")
