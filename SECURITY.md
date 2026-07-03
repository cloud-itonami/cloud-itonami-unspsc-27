# Security Policy

This project handles tool fleet rental and maintenance workflows. Treat vulnerabilities as potentially
high impact even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real resident, municipal or site data exposure
- authorization bypass
- Tool Fleet Governor bypass
- audit-ledger tampering
- a path that lets a safety-critical action dispatch without human sign-off

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on public data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real resident/municipal data outside this repository.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
