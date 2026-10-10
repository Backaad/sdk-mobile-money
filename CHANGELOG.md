# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Java core (`sdk-mobile-money-core`):
  - `MobileMoney` entry point: collect, payout, status and webhook handling,
    whatever the provider.
  - Common model: payment and payout requests, transactions with the provider
    that processed them and its raw status, verified webhook events, provider
    capabilities, typed errors.
  - Phone numbers: normalization of the usual written forms, operator
    detection with a configurable prefix table, wire format per provider, and
    reference data in `testdata/phone-numbers.json`, to be read by the Dart
    tests as well when the Flutter package gets its features.
  - Idempotency: every collection and payout goes through a key valid for
    24 hours, with an in-memory store. A repeated request (double tap, retry)
    gets the first result; a request whose answer was lost is never sent again.
  - Campay provider: token cache with renewal after a 401, collect, payout,
    status, and webhooks verified as JWT HS256 (GET or POST). Several Campay
    details are still to be confirmed: see `docs/providers/campay.md`.
  - Simulation provider, which never confirms a payment.
  - Tests for the reliability and security rules, and a Campay sandbox test
    skipped when no keys are set.
- Spring Boot starter (`sdk-mobile-money-spring-boot-starter`, Spring Boot 4):
  - `mobile-money.*` properties, checked at startup with a report that names
    the property to fix (missing key or environment, simulation mixed with a
    provider, local callback URL in production, Redis that may evict keys...);
  - Redis stores for idempotency records, Tara callback tokens and pending
    payments, used when the application has Redis; in-memory otherwise;
  - webhook endpoint `/mobile-money/webhooks/{provider}` that verifies each
    webhook and publishes `PaymentConfirmedEvent` or `PaymentFailedEvent`;
  - optional task, off by default, that reads pending payments again.
  - See `docs/spring-boot.md`.
- Core: `MobileMoney.Builder.callbackBaseUrl` (each request gets the callback
  URL of the provider that processes it), `transactionListener`, and
  `MobileMoney.providerIds()`.
- Demo server (`examples/spring-boot-demo`): start a payment, read its status,
  receive webhooks; simulation by default; `docker-compose.yml` with Redis.
- CI: Redis service for the starter tests, demo build and tests, and a Docker
  Compose smoke test of the demo image.
- MTN MoMo provider: collection (`requesttopay`) and payout (disbursement
  `transfer`), one access token per product with renewal after a 401, status
  reads, unsigned callbacks confirmed by a status read. The transaction
  reference is chosen before sending, so a lost answer still gives a
  reference whose status can be read. Sandbox test skipped when no keys are set.
- NotchPay provider: two-call collection (initialize, then charge on the
  operator's channel), payout, status reads, and webhooks verified with
  HMAC-SHA256 compared in constant time.
- Tara provider (experimental): collection only, with a single-use,
  unpredictable, expiring callback URL per payment (128-bit token), callbacks
  matched on reference, amount and number, and an optional IP allowlist.
- Configurable failover (`MobileMoney.Builder.failover`): the next provider
  is tried only when nothing reached the previous one, and it is logged.
- `ProviderFactory`: builds the providers from the variables of
  `.env.example`; an incomplete configuration prevents startup. New variables:
  `CAMPAY_ENVIRONMENT`, `MTN_MOMO_ENVIRONMENT`, `MTN_MOMO_CURRENCY`,
  `TARA_CALLBACK_ALLOWED_IPS`.
- `ProviderUnavailableException.providerReference()`: an unknown outcome can
  carry the provider reference when it is known.
- `docs/providers/`: pages for Campay, MTN MoMo, NotchPay and Tara, with what
  was checked and what remains to be confirmed.
- Project skeleton: Maven build for the Java core (Jackson 3) and the Spring
  Boot 4 starter, empty Flutter package, CI on GitHub Actions (JDK 17 and 21,
  Flutter) with coverage sent to Codecov, `.env.example` and `CLAUDE.md`.
