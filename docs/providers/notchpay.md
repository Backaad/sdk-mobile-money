# NotchPay

Status in the SDK: **implemented** (collection, payout, status, webhooks), tested against a
WireMock server only. There is no sandbox test: NotchPay test keys (`pk_test_…`) work on the
production URL, and the project has no keys of its own.

## Sources (2026-10-10)

NotchPay's developer documentation (`https://developer.notchpay.co`) **could not be opened**
from the environment used to write this integration (the host did not resolve). What was used
instead: search-engine extracts of that documentation and of NotchPay's SDKs. Marked *docs
(extract)* below: paraphrased, to be checked against the documentation itself. The extracts are
not consistent about the response envelope (`transaction` or `data`), so the SDK accepts both.

## What the SDK does, and on what basis

| Topic | Behaviour in the SDK | Basis | Status |
|---|---|---|---|
| Base URL | `https://api.notchpay.co`; test and live modes come from the key | docs (extract) | to be confirmed |
| Authentication | `Authorization: <public key>` on every call; `X-Grant: <private key>` on transfers | docs (extract) | to be confirmed (is `X-Grant` also needed for the charge?) |
| Collect, step 1 | `POST /payments` with `amount` (number), `currency`, `reference` (= merchant reference), `description`, `phone`; answer `transaction.reference` (`trx.…`), `transaction.trxref`, `authorization_url` | docs (extract) | to be confirmed (customer fields: `phone`, `email` or `customer {…}`?) |
| Collect, step 2 | `POST /payments/{reference}` with `channel` (`cm.mtn` or `cm.orange`) and `data {phone, country: "CM"}` | docs (extract) | to be confirmed |
| Channel | Chosen from the number's prefix with the configurable prefix table. A number whose operator is unknown is refused before anything is sent | SDK choice | — |
| Number format | `237XXXXXXXXX`, as in NotchPay's examples | docs (extract) | to be confirmed |
| Status | `GET /payments/{reference}` (collections), `GET /transfers/{reference}` (payouts, with `X-Grant`) → `status`, `reference`, `trxref`, `amount`, `currency` | docs (extract) | to be confirmed (transfer fields) |
| Status values | `complete`, `completed`, `successful`, `success` → SUCCESSFUL; `failed`, `canceled`, `cancelled`, `rejected`, `expired` → FAILED; anything else → PENDING with the raw value kept | docs (extract) | to be confirmed (full list) |
| Payout | `POST /transfers` with `amount`, `currency`, `description`, `reference`, `channel`, `beneficiary {name, phone}`; answer `transfer.reference` | docs (extract) | **to be confirmed** (beneficiary or recipient object) |
| Errors | 401/403 → authentication error; 408/429 → not sent; other 4xx → refusal; 409 and 5xx → unknown outcome | SDK choice | status codes to be confirmed |
| Webhooks | POST, header `x-notch-signature` = hex HMAC-SHA256 of the raw body with the webhook secret, compared in constant time; body `{type: "payment.complete" / "payment.failed" / "transfer.…", data {reference, trxref, status, …}}`. The status is then read again | docs (extract) | to be confirmed (name of the secret in the dashboard, event names) |

## Design choices

- **Two calls, one key.** Step 1 only creates the payment; nothing is debited before step 2.
  A failure of step 1 after sending is still treated as an unknown outcome (the general rule),
  even though nothing can have been debited. A step 2 that certainly did not reach NotchPay is
  reported as "not sent"; an unknown outcome of step 2 keeps the NotchPay reference, so its
  status can be read.
- **No token**: NotchPay uses fixed keys, so the "401 then renewal" case of the test matrix does
  not apply; a 401 is an authentication error.
