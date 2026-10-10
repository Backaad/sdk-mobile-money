# Campay

Status in the SDK: **implemented** (collection, payout, status, webhooks), tested against a
WireMock server only. The sandbox test exists but has never been run by the project, which has
no keys of its own (see [Sandbox](#sandbox)).

## Sources checked (2026-10-10)

Campay's API reference is a Postman page
(`https://documenter.getpostman.com/view/2391374/T1LV8PVA`). It **could not be opened** from the
environment used to write this integration, so nothing below was read in it directly. What was
read instead, in order of trust:

1. **Code published by Campay**: the Python SDK `campay` 1.1.0 on PyPI (author "CamPay",
   info@campay.net), the Android SDK in the `CamPay` GitHub organization, and Campay's
   WooCommerce plugin. Marked *official code* below.
2. **Search-engine extracts of the Postman page**. Marked *docs (extract)*: paraphrased, to be
   checked against the page itself.
3. **Ten third-party client libraries** (Go, Node, PHP, Ruby), compared with each other. Marked
   *third-party*.

## What the SDK does, and on what basis

| Topic | Behaviour in the SDK | Basis | Status |
|---|---|---|---|
| Base URLs | Sandbox `https://demo.campay.net`, production `https://www.campay.net` | official code | confirmed |
| Paths | `/api/token/`, `/api/collect/`, `/api/withdraw/`, `/api/transaction/{reference}/`, with trailing slash | official code | confirmed |
| Token | `POST /api/token/` with JSON `{"username", "password"}`; response `token` | official code | confirmed |
| Token lifetime | `expires_in` read as seconds; kept until expiry minus 1 min (half the lifetime if shorter than 2 min); 5 min if missing | sample response `expires_in: 3600` (third-party stub, likely copied from the docs) | **to be confirmed** (unit and value) |
| Auth header | `Authorization: Token <token>` (not `Bearer`) | official code | confirmed |
| Expired or refused token | HTTP 401 → token renewed, request sent once more | third-party | **to be confirmed** (401 or 403?) |
| Wrong credentials | Token endpoint 400/401/403 → `ProviderAuthenticationException` | official plugin expects `{"non_field_errors": [...]}`; status code unknown | **to be confirmed** |
| Collect | `POST /api/collect/` with `amount` (text), `currency`, `from`, `description`, `external_reference` (= merchant reference) | official code | confirmed |
| Collect response | `reference` (required), `ussd_code`, `operator`; status PENDING | official code | confirmed |
| Payout | `POST /api/withdraw/` with `amount` (text), `currency`, `to`, `description`, `external_reference` | official Python code (Android omits `currency`) | confirmed |
| Payout response | `reference` (required); a `status` field is read if present | official Python code reads `reference`, but the official Android model has no such field; `status` only in a third-party stub | **to be confirmed** |
| Payout prerequisite | "API withdrawal" must be enabled in the application settings | official README | confirmed |
| Status | `GET /api/transaction/{reference}/` → `status`, `reference`, `external_reference`, `amount`, `currency`, `operator`, `code`, `operator_reference` | official README | confirmed |
| Status values | `SUCCESSFUL` → SUCCESSFUL, `FAILED` → FAILED, anything else (including `PENDING`) → PENDING with the raw value kept | official README lists PENDING, SUCCESSFUL, FAILED | confirmed for the 3 values; others (CANCELED, TIMEOUT?) **to be confirmed** |
| Amount in status | number (`5`, `1.0`) or text, kept if it is a whole number | official README (number), third-party (`1.0`, text) | confirmed for numbers |
| Unknown reference | HTTP 404 → `TransactionNotFoundException`, never FAILED | official README says "transaction not found"; status code unknown | **to be confirmed** |
| Errors | Body field `message`, kept (cleaned, 200 characters max); `error_code` read if present | official code (`message`) | `error_code` field name **to be confirmed** |
| Error codes | ER101 invalid number, ER102 unsupported operator, ER201 invalid amount (no decimals), ER301 insufficient balance | docs (extract) | **to be confirmed** |
| Phone format | `237` + 9 digits, no `+` (for example `237670123456`) | official READMEs | confirmed |
| Currency | XAF only | official material | confirmed; XOF refused before sending |
| Webhook delivery | GET with query parameters; POST (JSON or form) also accepted | docs (extract): "Webhook or Callback (GET)"; POST mentioned only by the brief | GET confirmed by extract; POST **to be confirmed** |
| Webhook parameters | `status`, `reference`, `amount`, `currency`, `operator`, `code`, `operator_reference`, `signature`, `external_reference`, (`endpoint`) | third-party (two independent Go SDKs) | **to be confirmed** |
| Webhook signature | `signature` is a JWT, HS256, signed with the application's webhook key (UTF-8 bytes) | four independent third-party implementations | **to be confirmed** |
| JWT claims | Unknown. The only sample has an empty payload `{}` | third-party test fixture | **to be confirmed** |
| Sandbox limits | Probably 25 XAF at most (several recent unofficial sources; one older post says 100) | third-party | **to be confirmed** |

## Differences from the brief

- **`redirect_url` is not sent on collect.** The brief lists it, but no code published by Campay
  sends it on `/api/collect/`: it belongs to the payment-link endpoint (`/api/get_payment_link/`),
  where it is a browser redirect. `PaymentRequest.callbackUrl` is therefore ignored by Campay;
  the webhook URL is set in the Campay dashboard.
- **Webhooks: GET is the documented method.** POST is accepted too, as the brief asks, but it is
  not confirmed that Campay ever sends one.
- **Expiry of the webhook JWT is checked only when the token has an `exp` claim.** The brief
  says expired tokens are refused, and they are, but the only known sample has no claims at all.
  Requiring `exp` could reject every real webhook. This is safe because a webhook confirms
  nothing by itself (see below). To be revisited once a real webhook has been captured.

## Security decisions

- **The status announced by a webhook is never trusted.** After the signature check, the status
  is read again with `GET /api/transaction/{reference}/`, and every field of the event (status,
  amount, currency, merchant reference) comes from that read. The JWT probably does not bind the
  transaction (empty sample payload), so a captured signature could be replayed with other
  parameters: the new read makes that harmless. This matters: Campay's own WooCommerce plugin
  had a payment bypass (CVE-2025-12883, fixed in its version 1.2.3) because it trusted the
  status in the query string of its payment redirect instead of asking Campay.
- A webhook is rejected if its reference or merchant reference differs from what Campay
  returns, if a parameter appears twice, if the JWT claims (when there are any) contradict the
  parameters, or if Campay does not know the reference.
- Only `HS256` is accepted; unsigned tokens (`alg: none`), other algorithms, `crit` headers,
  non-canonical encodings and tokens issued in the future are refused. Signatures are compared
  in constant time.
- No secret is logged: only the method, path, HTTP status and duration of each call. Error
  messages from Campay are cleaned, and the SDK's own credentials and token are removed from
  them if Campay ever repeats them.
- With GET webhooks, the signature travels in the query string, which web servers often log.
  Leave query strings out of the access logs for the webhook route.

## Error handling and idempotency

| Answer | Collect / payout | Status read |
|---|---|---|
| 200/201 with a reference | accepted | mapped |
| 200 without reference, empty or not JSON | outcome unknown (may have been processed) | unchanged (transient) |
| 400, 422 and other 4xx not listed here | `PaymentRejectedException` (nothing processed) | unchanged (transient) |
| 401 | token renewed, request sent once more; then `ProviderAuthenticationException` | same |
| 403 | `ProviderAuthenticationException` (for example payouts not enabled) | unchanged (transient) |
| 404 | rejection | `TransactionNotFoundException` |
| 408, 429 | `ProviderUnavailableException`, not processed | unchanged (transient) |
| 409 | outcome unknown (the transaction may already exist) | unchanged (transient) |
| 3xx, 5xx, timeout, connection reset | outcome unknown | unchanged (transient) |
| Connection refused | `ProviderUnavailableException`, not processed | unchanged (transient) |

"Outcome unknown" returns a PENDING transaction without provider reference, and it is never
sent again for the same idempotency key. Campay offers no lookup by `external_reference` in the
endpoints we know of, so the outcome is learned from the webhook, which carries the merchant
reference.

## To be confirmed

Each of these needs the Postman page, or a real exchange with the sandbox:

1. Unit and usual value of `expires_in`.
2. Status code and body for an expired token, wrong credentials, an unknown reference.
3. Name of the error-code field (`error_code`?) and the full list of codes.
4. Status values other than PENDING, SUCCESSFUL and FAILED.
5. The shape of the payout response: `reference` (the official Python SDK reads it, the official
   Android model has no such field) and `status`. Without a reference, a payout is treated as an
   unknown outcome: PENDING, never sent again.
6. Webhook: GET only or POST too; full parameter list; JWT claims (`exp`?); key encoding.
7. Whether Campay sends a webhook again after a non-2xx answer.
8. What `/api/collect/` does with `redirect_url` or a 9-digit number.
9. Sandbox limits and test numbers.
10. Whether Campay deduplicates on `external_reference`.

Campay also offers a permanent access token (APP KEYS section of the dashboard) instead of the
username and password. The SDK does not support it yet: an idea to discuss.

## Sandbox

`CampaySandboxTest` runs against `https://demo.campay.net` with your own sandbox keys:

```sh
# In .env: CAMPAY_USERNAME, CAMPAY_PASSWORD, and optionally CAMPAY_SANDBOX_PHONE_NUMBER
set -a; . ./.env; set +a
cd java && ./mvnw test -pl sdk-mobile-money-core -Dtest=CampaySandboxTest
```

Without the keys, the tests are reported as skipped. With a phone number, the test starts a
5 XAF collection: the owner of that number receives a payment prompt.
