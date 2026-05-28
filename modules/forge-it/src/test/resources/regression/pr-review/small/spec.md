# Piece: structured Ask default + file↔question pairing

The `ChangeCollector.Ask` classification feeds the driver's `AskUserQuestion`
mechanism (§10.4). Two gaps make the orchestrator's job error-prone:

1. The emitted `Question` carries `options = ["Allow", "Deny"]` but nothing
   encodes the §10.1 "default option is Deny" rule, so a Q&A pane has no
   structured way to know the safe answer.
2. `Ask(questions, included)` drops the association between each question and
   the file it is about, forcing the orchestrator to reconstruct the mapping
   from input ordering or by parsing the path out of the question text.

## Acceptance criteria

- `forge-core` `Question` gains a trailing `defaultOption: Option[String] = None`.
  The default arg means zero changes to existing driver-originated call sites
  (they stay `None` — the wire shape has no default).
- The `ChangeCollector` `Ask` sets `defaultOption = Some("Deny")`.
- `Ask` changes to carry `asked: Vector[(FileChange, Question)]` so the answer
  maps straight back to the file it stages.
- A unit test asserts the file↔question pairing, ordering, and the default.
