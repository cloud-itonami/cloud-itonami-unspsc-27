# cloud-itonami-unspsc-27

Open Business Blueprint (implemented actor) for **UNSPSC segment 27**:
Tools and General Machinery.

This repository publishes a forkable OSS business for an independent tool
fleet rental and maintenance operator: a diagnostic robot performs
tool/machinery condition inspection under a governor-gated actor, so a
contractor-tool-library operator keeps auditable maintenance and
rental-safety records instead of renting a closed asset-management SaaS.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime -- the same actor pattern as
[`robotaxi-actor`](https://github.com/com-junkawasaki/robotaxi-actor)
(AR1 ⊣ SafetyGovernor), [`cloud-itonami-6310`](https://github.com/cloud-itonami/cloud-itonami-6310)
(HR-LLM ⊣ PolicyGovernor) and [`cloud-itonami-isic-6910`](https://github.com/cloud-itonami/cloud-itonami-isic-6910)
(Registrar-LLM ⊣ RegistrarGovernor, whose structure this actor ports).
Here it is **ToolFleet-LLM ⊣ ToolFleetGovernor**.

> **Why an actor layer at all?** An LLM is great at drafting an inspection
> checklist, normalizing intake and flagging a worn part -- but it has
> **no notion of which safety standards are official, no authority to
> clear a tool as rentable, and no business being the one that decides a
> real tool re-enters the rental pool today**. Letting it enroll or
> return-to-service directly invites fabricated safety clearances,
> laundering an unsafe tool back into circulation, and silent liability
> for whoever runs it. This project seals the ToolFleet-LLM into a single
> node and wraps it with an independent **ToolFleetGovernor**, a human
> **approval workflow**, a **sensor-data grounding** layer, and an
> immutable **audit ledger**.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a diagnostic robot (vibration,
wear, calibration-drift sensing) performs the tool/machinery inspection
under an actor that proposes a maintenance/retire decision and an
independent **Tool Fleet Governor** that gates it. The governor never
dispatches hardware itself; `:high`/`:safety-critical` findings (e.g. a
tool defect that could injure a renter) require human sign-off before the
tool re-enters the rental pool. Every safety/condition claim must cite
measured sensor readings (`formation.telemetry`).

## Core Contract

```text
tool/machinery registry + prior maintenance history + sensor readings
        |
        v
ToolFleet-LLM -> Tool Fleet Governor -> return-to-service, or human sign-off
        |
        v
gated fleet actions (enroll / return-to-service / retire / checkout) + audit ledger
```

No automated assessment can return a tool to the rental pool the governor
would refuse, suppress a maintenance record, downgrade a safety defect,
or assert a condition claim with no measured sensor basis.

## Implementation

Portable `.cljc` namespaces under `src/formation/`:

- `registry` -- 20-char fleet-id issuance with ISO 7064 MOD 97-10 check
  digit + append-only enrollment / return-to-service / retire records.
- `facts` -- per-tool-class safety spec-basis catalog (OSHA / ANSI / ASME),
  reported honestly via `coverage`.
- `telemetry` -- sensor-reading ingestion + the pure grounding logic that
  ties a safety/condition claim to measured readings (robotics:true only;
  no counterpart in the company-formation reference).
- `store` -- SSoT behind a `Store` protocol (`MemStore` ‖ `DatomicStore`
  via `langchain.db`); sensor readings, inspection verdicts and the audit
  ledger all live here.
- `toolfleetllm` -- the contained intelligence node; returns proposals only.
- `governor` -- the independent ToolFleetGovernor (hard + soft checks).
- `phase` -- 0→3 staged rollout; no fleet actuation is ever auto-committed.
- `operation` -- the StateGraph (1 run = 1 operation); `sim` drives the demo.

`clojure -M:dev:test` (64 tests, 290 assertions) and `clojure -M:lint`
(clj-kondo, 0 errors). See [`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md)
for the full design and the 6910 addenda translations.

## Capability layer

Resolves via [`kotoba-lang/unspsc`](https://github.com/kotoba-lang/unspsc)
(UNSPSC segment `27`). Required capabilities:

- :robotics
- :telemetry
- :optimization
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
