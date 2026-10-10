# MTN MoMo (direct API)

Status in the SDK: **implemented** (collection, payout, status, callbacks), tested against a
WireMock server only. The sandbox test exists but has never been run by the project, which has
no keys of its own (see [Sandbox](#sandbox)).

## Sources (2026-10-10)

The MoMo developer portal (`https://momodeveloper.mtn.com`) was **not opened** while this
integration was written. The behaviour below follows the MoMo Open API as it is widely
documented (portal pages, MTN's API reference and common client libraries), as known to the
author. **Every row is therefore "to be confirmed"** against the portal before a production
release; the most likely points of difference are called out.

## What the SDK does, and on what basis

| Topic | Behaviour in the SDK | Status |
|---|---|---|
| Base URLs | Sandbox `https://sandbox.momodeveloper.mtn.com`, production `https://proxy.momoapi.mtn.com` | to be confirmed |
| Products | Collection and disbursement, each with its own subscription key (`Ocp-Apim-Subscription-Key`), API user and API key. A product without credentials is not offered | to be confirmed |
| Target environment | `X-Target-Environment: sandbox` in the sandbox; in production the value given by MTN for the country, which must be configured (`mtncameroon` for Cameroon: to be confirmed). `sandbox` is refused in production | to be confirmed |
| Token | `POST /{product}/token/` with `Authorization: Basic base64(apiUser:apiKey)`; response `access_token`, `expires_in` (seconds). Kept until expiry minus 1 min, one token per product | to be confirmed |
| Expired token | HTTP 401 → token renewed, same request (same `X-Reference-Id`) sent once more | to be confirmed |
| Collect | `POST /collection/v1_0/requesttopay`, `X-Reference-Id` = a random UUID v4 chosen by the SDK, body `amount` (text), `currency`, `externalId` (= merchant reference), `payer {partyIdType: MSISDN, partyId}`, `payerMessage`, `payeeNote`; answer `202 Accepted` without body | to be confirmed |
| Payout | `POST /disbursement/v1_0/transfer`, same headers, body with `payee` instead of `payer`; answer `202` | to be confirmed |
| Number format | MSISDN without `+`: `237XXXXXXXXX` | to be confirmed |
| Status | `GET /collection/v1_0/requesttopay/{referenceId}` or `GET /disbursement/v1_0/transfer/{referenceId}` → `status`, `externalId`, `amount`, `currency`, `financialTransactionId`, `reason` | to be confirmed |
| Status values | `SUCCESSFUL` → SUCCESSFUL, `FAILED` → FAILED, anything else (`PENDING`, unknown values) → PENDING with the raw value kept | to be confirmed (full list of values) |
| Errors | 4xx with `{code, message}` → refusal carrying `code`; 403 → authentication error; 408/429 → not sent; 409 (reference already used) and 5xx → unknown outcome **with the reference** | to be confirmed (codes and statuses) |
| Callbacks | MTN calls `X-Callback-Url` (POST or PUT) with the same body as the status read. They are **not signed**. The SDK adds `mm_reference` and `mm_type` to the callback URL, and always reads the status again before confirming (rule 1) | to be confirmed (method, body; MTN may require the callback host to match the one registered for the API user) |
| Currency | `XAF` by default; the public sandbox only accepts `EUR`, so the currency is configurable | to be confirmed |

## Design choices

- **The reference is known before sending.** `X-Reference-Id` is chosen by the SDK, so even
  when the answer is lost (timeout, 5xx, broken connection), the PENDING transaction returned
  by `MobileMoney` carries the reference and its status can be read. It is never sent again.
- **Unsigned callbacks.** The query parameters added to the callback URL only tell the SDK which
  transaction to read. A forged callback cannot confirm anything: the status comes from MTN's
  API, through this product's endpoint, with our token (rules 1 and 2).
- **Account holder checks** (`/accountholder/msisdn/.../active`) and balance reads are not used.

## Sandbox

`MtnMomoSandboxTest` runs only when `MTN_MOMO_COLLECTION_SUBSCRIPTION_KEY`,
`MTN_MOMO_COLLECTION_API_USER` and `MTN_MOMO_COLLECTION_API_KEY` are set (see `.env.example`). It
uses `EUR` unless `MTN_MOMO_CURRENCY` says otherwise. The API user and key are created in the
sandbox with the provisioning API (`POST /v1_0/apiuser`, then `POST /v1_0/apiuser/{id}/apikey`),
which the SDK does not do. **It has never been run by the project.**
