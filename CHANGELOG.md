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
- `docs/providers/campay.md`: what was checked and what remains to be confirmed.
- Project skeleton: Maven build for the Java core (Jackson 3) and the Spring
  Boot 4 starter, empty Flutter package, CI on GitHub Actions (JDK 17 and 21,
  Flutter) with coverage sent to Codecov, `.env.example` and `CLAUDE.md`.
