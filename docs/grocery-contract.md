# Grocery API contract (from PRD v2.2)

Source: `projects/jarvis-grocery-api-contract.md` (from
`projects/jarvis-grocery-api-extraction-prompt.md`, extraction ran
read-only on 5 Nov 2026). This file is the repo's copy of the contract as
stated in the PRD; the Mini's contract file is authoritative if they
differ.

## Base URL

`extended.npalakurla.com`

> Flag: earlier notes had the grocery app at `extended.npalakurla.net`.
> The contract says `.com`. Verify against the contract file before
> building the proxy.

## Auth

`Authorization: Bearer <token>` from a service account. Service accounts
are read-only (403) unless `allow_write` is set, and can be limited to
the shopping-list scope.

- Token lives on the Mini proxy and/or in HA `secrets.yaml` — never on
  the tablet.
- Writes are blocked: no write-enabled service account until Simba says
  yes. Reads may ship once a read-only token exists.

## Endpoint table

| Operation | Call | Notes |
|---|---|---|
| List | `GET /shopping-list?status=open` | One household list, active session, no list id, no paging |
| Add | `POST /shopping-list/items` `{name, quantity, category}` | Merges with an open item of the same name and category and adds to its quantity — a retry doubles the quantity unless a client request id is sent first |
| Check off | `PUT /shopping-list/items/{id}` `{status: "purchased"}` | Statuses: open, purchased, skipped, out_of_stock |
| Remove | `DELETE /shopping-list/items/{id}` | Hard delete; get the id from a list call first |
| Spend | `GET /analytics/spending-by-category?month=YYYY-MM` | Feeds the spend marquee |

## Rules the proxy must follow

- Dedupe by client request id and re-list before any retry, because add
  is not idempotent.
- Path B (HA `rest_command` direct to the API) must still go through
  `propose_action` → confirm, and replays the pre-write list check to
  avoid a duplicate write if the first call's response was lost.
- Write routes come from the app's source on main and have not been
  probed live.

## What lives where

- The Home Status server has **no grocery code today**, so the Mini proxy
  (`mini/`, LAN only) is new code. It holds the dedupe and name-to-id
  logic; the token is on the Mini.
- The mirror has a GET-only grocery view at
  `localocr/database/grocery.py` (lists and queries). The APK path never
  touches the mirror.
