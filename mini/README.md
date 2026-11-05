# mini/ — Mini grocery proxy (Phase 3)

LAN-only Python service on the Mac Mini. Holds the grocery Bearer token
and the dedupe + name-to-id logic. Never on the tablet.

- Reads: `GET /shopping-list?status=open`,
  `GET /analytics/spending-by-category?month=YYYY-MM` (see
  `docs/grocery-contract.md`).
- Writes (blocked until Simba approves the write account): `POST
  /shopping-list/items`, `PUT /shopping-list/items/{id}`,
  `DELETE /shopping-list/items/{id}`. Dedupe by client request id and
  re-list before any retry — add is not idempotent.
- The Home Status server has no grocery code today, so this proxy is new
  code. The mirror's GET-only grocery view is never touched by the APK
  path.

Phase 0 does not build this. Phase 3 only, after Simba's go-ahead.
