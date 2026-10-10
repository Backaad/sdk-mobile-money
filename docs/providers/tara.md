# Tara (experimental)

Status in the SDK: **experimental**, collection only, tested against a WireMock server only.

## Sources (2026-10-10)

No official Tara API documentation could be found or opened. What was used: search-engine
extracts about the `mobilepay` endpoint, and the README of a third-party PHP client
(`consolidis/tara-payment`), which shows the callback fields `status` (`SUCCESS`) and
`phoneNumber`. **Every field name below is to be confirmed.**

## What the SDK does

| Topic | Behaviour in the SDK | Status |
|---|---|---|
| Endpoint | `POST https://www.dklo.co/api/tara/mobilepay` | to be confirmed |
| Request | JSON `apiKey`, `businessId`, `productId` (= merchant reference), `productName`, `productDescription`, `productPrice` (integer), `phoneNumber` (`237XXXXXXXXX`), `webHookUrl` | to be confirmed |
| Answer | `status: SUCCESS` → PENDING (the customer has not paid yet); `FAILURE`, `FAILED`, `ERROR` → refusal; anything else, 5xx or an unreadable answer → unknown outcome | to be confirmed |
| Status query | None | per the brief |
| Payouts | Not supported | to be confirmed |
| Callback | POST JSON `productId`, `status` (`SUCCESS`, `FAILURE`), `amount` (or `productPrice`), `phoneNumber` | **to be confirmed** (amount field) |

## How callbacks are protected

Tara does not sign its callbacks and its status cannot be read, so, as the brief requires:

- `MobileMoney` gives each payment its own callback URL: the merchant's `callbackUrl` plus a
  `mm_token` parameter, a 128-bit random token (URL-safe Base64). Only its SHA-256 digest is
  stored, with the expected provider, reference, amount, currency and number.
- A callback is accepted only if its token is known, unexpired (24 hours by default,
  configurable) and created for Tara, and if its reference, amount and number match. A final
  status (`SUCCESS`, `FAILURE`) consumes the token: it works once. Other statuses leave it.
- An IP allowlist can be configured (`TARA_CALLBACK_ALLOWED_IPS`), compared with the address
  the merchant's server passes in `WebhookRequest.remoteAddress()`.
- The merchant reference is sent as `productId`, so a callback that arrives before the answer
  to the payment request can still be matched.
- The API key travels in the request body; request and response bodies are never logged.
- The callback should be answered fast (`200`); the Spring Boot starter will do it (step 4).

The default token store is in memory: tokens are lost when the server restarts. A server that
runs several instances, or restarts often, needs a shared `CallbackTokenStore`.
