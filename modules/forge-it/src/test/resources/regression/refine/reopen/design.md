# Design: `ChangeCollector` strict-mode filtering

## Goal

Classify staged file changes as Allow / Deny / Ask per §10.1. In strict mode
(`requireExplicitAllow: true`), the design originally stated that paths not
matching `allowPatterns` are **silently filtered out** of the stage.

## Pieces

1. **p1 — `ChangeCollector.classify` with the five §10.1 rules.** (just merged)
2. **p2 — orchestrator wires the classification into the stage plan.** (pending)

## Just-merged piece (p1)

Implemented the classifier. While writing the strict-mode path it became clear
the design's "silently filter" instruction directly contradicts v1.2 §10.1,
which says an unmatched path in strict mode must go to **Ask** — surfacing
every borderline path through the driver's question mechanism rather than
dropping it. Silently filtering would mean a file the operator expected to be
staged just vanishes with no signal, which is exactly the failure §10.1's Ask
path exists to prevent.

The piece was implemented to the **spec** (Ask), not the design doc
(filter), because shipping the filter behaviour would be a security-relevant
deviation from the contract.

## Refine question

The merged piece deviates from the design doc's stated strict-mode behaviour
because the design contradicts the §10.1 spec. The design document's strict-mode
section is now wrong and will mislead the next reader and the p2 implementation.
The design needs to be reopened and corrected to match the spec before p2
proceeds.
