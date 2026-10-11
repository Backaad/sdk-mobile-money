# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Flutter package: an example (`example/example.md`) for the Example tab of
  pub.dev, the only pub points 0.1.1 missed (150/160).

### Changed

- README: the Flutter package is on pub.dev (badge). Development versions:
  `0.1.2-SNAPSHOT`, Flutter package `0.1.2-dev`.

## [0.1.1] - 2026-10-11

### Fixed

- Javadoc: the last undocumented public constructors and serialized fields are
  documented, and the release build now fails on any Javadoc warning.

### Changed

- README: Maven Central badge, and a status that no longer names a version;
  until the Flutter package is on pub.dev, it installs from the repository at
  the release tag. The package README no longer states whether it is
  published, since pub.dev shows it.
- `scripts/set-version.sh` also updates the installation snippets of the
  documentation for a release version.

## [0.1.0] - 2026-10-11

First release.

### Added

- Documentation: `README.md` rewritten as the project's front page (demo
  animation, why, providers, five-minute start on Spring Boot and Flutter,
  security, roadmap, contributing), and the same in French in `README.fr.md`;
  `docs/architecture.md` with Mermaid diagrams; `docs/security.md`, which maps
  each of the eleven rules to its tests; `docs/releasing.md`.
- `CONTRIBUTING.md` (with how to add a provider), `CODE_OF_CONDUCT.md`
  (Contributor Covenant 2.1), `SECURITY.md` (private reporting), and issue forms
  for bugs, new providers and questions.
- Release preparation: a Maven `release` profile (sources and Javadoc jars,
  signature, upload to the Central Portal), a release workflow that publishes
  on a `vX.Y.Z` tag after checking that the versions and CHANGELOG sections
  agree, and `scripts/set-version.sh`. The CI builds the release artifacts and
  runs `flutter pub publish --dry-run`.
- Flutter package: ready for pub.dev (topics, and the demo animation as
  screenshot).
- Demo server: `GET /api/config` gives the currency it charges in.
- Demo app: a 25 amount (the Campay sandbox probably refuses more, to be
  confirmed), the server's currency shown with the amount, and its own texts in
  French too.

### Fixed

- Demo app: with five amounts, the selected one wrapped on several lines on a
  phone screen.

- Flutter package (`flutter/sdk_mobile_money`), which only talks to the
  merchant's server:
  - phone numbers: validation, normalization and operator detection with the
    rules of the Java core, tested against `testdata/phone-numbers.json`;
  - `MobileMoneyClient`: starts a payment with an idempotency key (the same
    for every retry of a request), reads it, and follows it until it is
    settled, with a maximum waiting time and cancellation; a transient error
    or a timeout never shows a payment as failed;
  - `MobileMoneyCheckout`: optional payment form (number, detected operator,
    amount, pay button, "confirm on your phone" message), light and dark
    themes, English and French texts;
  - unit tests, widget tests in light and dark themes with a contrast check,
    and the server contract documented in the package README.
- Demo app (`examples/flutter-demo`, Android and iOS) that pays through the
  demo server; CI runs its analysis and tests.
- `testdata/phone-numbers.json`: more reference numbers on separators
  (tab, new line, narrow no-break space, ideographic space) and on characters
  that are not separators (U+0085, byte order mark), and an input longer than
  40 characters.
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
  URL of the provider that processes it), `transactionListener`,
  `MobileMoney.providerIds()`, and `handleWebhook(provider, request, onVerified)`,
  which records the outcome before a Tara single-use token is consumed.
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

[Unreleased]: https://github.com/Backaad/sdk-mobile-money/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/Backaad/sdk-mobile-money/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/Backaad/sdk-mobile-money/releases/tag/v0.1.0
