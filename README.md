# cloud-itonami-unspsc-27

Open UNSPSC Blueprint for **UNSPSC segment 27**: Tools and General
Machinery.

This repository designs a forkable OSS business for an independent tool
fleet rental and maintenance operator: a diagnostic robot performs
tool/machinery condition inspection under a governor-gated actor, so a
contractor-tool-library operator keeps auditable maintenance and
rental-safety records instead of renting a closed asset-management SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a diagnostic robot (vibration,
wear, calibration-drift sensing) performs the tool/machinery inspection
under an actor that proposes a maintenance/retire decision and an
independent **Tool Fleet Governor** that gates it. The governor never
dispatches hardware itself; `:high`/`:safety-critical` findings (e.g. a
tool defect that could injure a renter) require human sign-off before the
tool re-enters the rental pool.

## Core Contract

```text
tool/machinery registry + prior maintenance history
        |
        v
Condition Advisor -> Tool Fleet Governor -> return-to-service, or human sign-off
        |
        v
robot inspection actions (gated) + maintenance record + audit ledger
```

No automated assessment can return a tool to the rental pool the governor
would refuse, suppress a maintenance record, or downgrade a safety defect
without governor approval and audit evidence.

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
