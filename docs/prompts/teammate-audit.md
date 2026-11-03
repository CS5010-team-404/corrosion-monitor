Read CLAUDE.md and docs/CONTRACTS.md. I am Anela and I own the audit log. Change only the audit code.

1. Chain audit_outbox rows into audit_log in id order: hash = SHA-256 over the previous hash plus a canonical
   serialisation of the entry (fixed field order, UTC timestamps); set chained_at. Only this service writes audit_log.
2. GET /api/audit (paging, actor and action filters) returning the AuditEntry shape, and POST /api/audit/verify
   returning {intact, checked, firstBrokenId}.
3. Tests: an untouched chain verifies; editing any past row makes verification report that row; concurrent
   writers never break ordering.
4. Close my items in docs/GAPS.md and open a pull request.
