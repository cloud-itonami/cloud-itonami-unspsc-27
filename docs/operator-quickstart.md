# Operator Quickstart — Independent Tool Fleet Rental & Maintenance Robotics

Shortest path for **UNSPSC segment 27** (`cloud-itonami-unspsc-27`).

## Prerequisites

- Git
- Optional: static file server for `docs/`

## 1. Clone

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-unspsc-27.git
cd cloud-itonami-unspsc-27
```

## 2. Open the product face

```bash
open docs/index.html
# or: python3 -m http.server -d docs 8080
```

Publish: GitHub Pages on `main` `/docs`.

## 3. Governor

- Blueprint governor key: `tool-fleet-governor`
- Domain: `equipment/tool-fleet-rental`

## 4. Claim / go-live

- Free claim: https://itonami.cloud/isco-1212/
- Paid path: https://itonami.cloud/docs/go-live.md
- Blueprint: `blueprint.edn`

## Constraints

- No invented users/revenue
- No secrets in repo
