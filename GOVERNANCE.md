# Governance

`cloud-itonami-unspsc-27` is an OSS open-business blueprint. Governance covers both code and
the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the advisor cannot directly dispatch a robot action or commit a public
  record.
- the Tool Fleet Governor remains independent of the advisor.
- hard safety/compliance violations cannot be overridden by human approval.
- every commit, hold and approval path is auditable.
- real resident/municipal data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review, INCLUDING proof of whatever municipal contract or public
licensing the operator's jurisdiction requires for tool fleet rental and maintenance work.

Certified operators can lose certification for:

- bypassing governor checks
- mishandling resident or municipal data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
