# Security

A Mobile Money integration moves real money on the strength of a few HTTP
calls. This page says what the SDK protects, how, and which tests hold each
rule. To report a vulnerability, see [SECURITY.md](../SECURITY.md).

## What can go wrong

| Risk | Where it comes from | What the SDK does |
|---|---|---|
| A provider key is stolen | a key shipped in an app, committed, or written to a log | keys stay on the server; the Flutter package never holds one; logs never contain one |
| A payment is confirmed without being paid | a forged or replayed webhook, a webhook that claims a status | signature or single-use URL, then the status is read again from the provider |
| A customer is charged twice | a double tap, a network retry, a lost answer | idempotency key per payment; a request that may have reached the provider is never sent again |
| A paid payment is shown as failed | a timeout or a 5xx while reading the status | a transient error leaves the payment pending |
| A payment goes to an unexpected provider | an automatic failover | failover only to the providers the merchant lists, only when nothing reached the first one, and the result names the provider |
| The application starts half configured | a missing key, a local callback URL in production, a Redis that may evict keys | startup fails with a message that names the property, never its value |

## The eleven rules

Each rule is backed by tests. Java test classes are under
`java/*/src/test/java`; Dart tests are under `flutter/sdk_mobile_money/test`.

| # | Rule | Tests |
|---|---|---|
| 1 | Never trust the status announced by a webhook. When the provider lets us read the status, read it before confirming. | `MobileMoneyWebhookTest`, `WebhookEndpointTest` |
| 2 | A provider's webhook can only confirm that provider's transactions. | `MobileMoneyWebhookTest` |
| 3 | Signatures are compared in constant time. | `ConstantTimeTest`; `CodeRulesTest` fails the build if a signature or token is compared another way |
| 4 | A transient error while reading a status (network, 5xx, timeout, empty response) leaves the payment pending. It never marks it as failed. | `MobileMoneyStatusTest`, `PendingTransactionCheckerTest`; Dart: `mobile_money_client_test.dart` ("transient errors never settle the payment"), `mobile_money_checkout_test.dart` ("a payment still pending is never shown as failed") |
| 5 | Access tokens are cached until they expire, then renewed, including after a 401. | `CampayTokenCacheTest`; the 401 cases of `CampayProviderTest`, `MtnMomoProviderTest`, `NotchPayProviderTest`, `TaraProviderTest` |
| 6 | Each provider's phone number format is centralized and tested. | `ProviderPhoneFormatTest`, `ModelTest`, `PhoneNumberReferenceTest`; Dart: `phone_number_reference_test.dart`, on the same reference numbers |
| 7 | No key, token, signature or body containing one ever ends up in the logs. | `NoSecretsLeakTest`, `NoSecretsLeakOtherProvidersTest`, `StarterNoSecretsLeakTest`; Dart: `mobile_money_client_test.dart` ("messages never contain the answer") |
| 8 | Simulation never confirms a payment. | `SimulationTest`; the demo server's `DemoApplicationTest` |
| 9 | Nothing is debited or disbursed twice for the same idempotency key. | `MobileMoneyIdempotencyTest`, `InMemoryIdempotencyStoreTest`, `RedisStoresTest`; Dart: `mobile_money_client_test.dart` ("a retry of the same request sends the same key") |
| 10 | An incomplete or dangerous configuration prevents startup, with a clear message. | `ConfigurationTest`, `ProviderFactoryTest`, `MobileMoneyAutoConfigurationTest` |
| 11 | No silent failover to another provider. | `NoSilentFailoverTest` |

## In more detail

- **Webhooks.** Campay webhooks are JWT HS256, checked with the webhook key;
  unsigned and expired tokens are refused. NotchPay webhooks carry an
  HMAC-SHA256 of the raw body. MTN MoMo does not sign its callbacks: the status
  is always read again. Tara neither signs its callbacks nor lets the status be
  read, so each payment gets its own callback URL with a 128-bit random token,
  single-use and expiring, and the callback must match the expected reference,
  amount and number; an IP allowlist can be added. See
  [providers/tara.md](providers/tara.md).
- **HTTP calls.** Every call has a timeout and redirects are not followed. A
  POST is never sent twice, except once after a `401` with a renewed token.
- **Logs.** They contain the method, path, status and duration of calls, never
  headers, query strings or bodies. Phone numbers are masked
  (`+2376******56`). Exceptions from the HTTP client or Jackson are never
  chained, since their messages can quote a response.
- **The Flutter package.** It holds no key and talks only to the merchant's
  server. It logs nothing, and its error messages contain neither the number
  nor the server's answer. A retry sends the same idempotency key.

## What the merchant must do

The SDK cannot do these for you:

- Keep the keys in environment variables or a secret store, never in a
  committed file. `.env` is for your sandbox keys, on your machine only.
- Serve your API over HTTPS, and authenticate the app's calls with your own
  session.
- Compute the amount on the server from the order. Never trust an amount sent
  by an app.
- Settle the order when the server receives `PaymentConfirmedEvent` (or reads
  the status), never because the app says the payment succeeded. Listeners may
  be called more than once for the same payment: record each outcome once.
- With several instances, use Redis for the stores, with
  `maxmemory-policy noeviction` and persistence (AOF): a lost idempotency
  record could let a payment through twice.
- Let the webhook path through your authentication and CSRF protection: the SDK
  authenticates webhooks itself. Behind a reverse proxy, forward the client
  address so that the Tara IP allowlist sees the provider's.
- If a key leaks, revoke it at the provider and replace it.
