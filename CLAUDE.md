# CLAUDE.md

Durable rules for this repository. The full project brief, in French, is in
[`docs/BRIEF.md`](docs/BRIEF.md): read it before starting a new step.

## The project

An open-source SDK to **collect** and **disburse** Mobile Money payments in
Cameroon through a single interface, whatever the provider behind it: MTN MoMo,
Campay, NotchPay and Tara first. The architecture must allow adding other
countries and providers later.

## Core principles

- **Provider keys stay on the server.** A mobile app can be decompiled, so a key
  shipped in it ends up stolen. The core is a server-side Java library. The
  Flutter package only talks to the merchant's server, never to providers.
- **No secret in the repository or in conversations.** No key, token or
  signature in code, tests, fixtures, docs or commit messages. Real values live
  in a local `.env` (ignored by git) or in GitHub Actions secrets, under the
  names listed in [`.env.example`](.env.example).
- **Never present as verified something that is not.** Anything not confirmed
  in a provider's official documentation stays marked "to be confirmed", in
  code comments, docs and the README provider table.

## Repository layout

```
java/
  pom.xml                                 parent POM, Maven wrapper (./mvnw)
  sdk-mobile-money-core/                  Java 17+ library, no Spring
  sdk-mobile-money-spring-boot-starter/   Spring Boot integration
flutter/sdk_mobile_money/                 Dart package for pub.dev
examples/spring-boot-demo/                demo server (simulation by default)
examples/flutter-demo/                    demo app using the demo server
docs/                                     architecture, security, docs/providers/
```

## Commands

```sh
# Java (from java/): build, tests, coverage report
./mvnw verify

# Flutter (from flutter/sdk_mobile_money/)
flutter pub get
flutter analyze
flutter test
```

CI (`.github/workflows/ci.yml`) runs the Java build on JDK 17 and 21, and
`flutter analyze` + `flutter test`. Keep it green.

## Java

- Java 17 minimum (`maven.compiler.release` 17). Maven group `io.github.backaad`,
  base package `io.github.backaad.mobilemoney`.
- **Core**: no Spring. Runtime dependencies are limited to the JDK HTTP client
  (`java.net.http`), Jackson and SLF4J. The Maven enforcer fails the build on
  any other runtime dependency; adding one needs the maintainer's approval.
- **Starter**: Spring Boot integration. Configuration under `mobile-money.*`,
  validated at startup with clear messages.
- Dependency and plugin versions are managed in `java/pom.xml`.

## Flutter

- Package `sdk_mobile_money`. It never embeds a provider key and never calls a
  provider API: it only calls the merchant's server.
- Phone number normalization and provider detection follow exactly the same
  rules as the Java core. Both sides test against the same shared JSON file of
  reference numbers.
- The UI component must work in light and dark themes.

## Payment domain rules

- Amounts are integers. Currency `XAF` first, `XOF` planned.
- Statuses are `PENDING`, `SUCCESSFUL` or `FAILED`; always keep the provider's
  raw status for diagnostics.
- Every operation that debits or disburses goes through an idempotency key
  valid for 24 hours: a repeated request returns the first result.
- Failover to another provider happens only if the merchant configured it, and
  the result says which provider actually processed the payment.
- The phone prefix table is configurable. Its default value must be checked
  against ARTP data.
- Simulation mode talks to nobody and never confirms a payment. Full flows are
  tested against sandboxes.

## Reliability and security rules

Each rule must be backed by at least one test.

1. Never trust the status announced by a webhook. When the provider lets us
   read the status, read it before confirming.
2. A provider's webhook can only confirm that provider's transactions.
3. Signatures are compared in constant time.
4. A transient error while reading a status (network, 5xx, timeout, empty
   response) leaves the payment pending. It never marks it as failed.
5. Access tokens are cached until they expire, then renewed, including after a
   401.
6. Each provider's phone number format is centralized and tested.
7. No key, token, signature or body containing one ever ends up in the logs.
8. Simulation never confirms a payment.
9. Nothing is debited or disbursed twice for the same idempotency key.
10. An incomplete or dangerous configuration prevents startup, with a clear
    message.
11. No silent failover to another provider.

## Tests

- Provider tests use WireMock, with responses taken from the official
  documentation. They cover success and refusal, a 401 followed by token
  renewal, a 5xx and an empty response, and an unknown status.
- Sandbox tests (MTN first, then Campay) run only when the keys are present.
  Otherwise they are skipped cleanly, never failed.
- Flutter: unit tests, plus a widget rendering test in light and dark themes.

## Providers

- Check everything in the provider's official documentation before coding, and
  record what was checked and the differences in `docs/providers/<provider>.md`.
- Tara is experimental: no status query and unsigned webhooks, so each payment
  gets a unique, unpredictable, single-use, expiring callback URL (128-bit
  random token), the callback must match the expected reference, amount and
  number, and an IP allowlist is optional. Answer its callbacks fast (`200`).
- Orange Money direct is planned for later (it requires a partnership).

## Conventions

- Talk to the maintainer in French. Code, comments, documentation and commit
  messages are in English, except `README.fr.md`.
- Semantic versioning. Keep `CHANGELOG.md` up to date in every pull request.
  First release: `0.1.0`.
- Work step by step: one branch and one pull request per step. At the end of
  each step, give the maintainer a short report: what is done, what is
  verified and how, what remains to be confirmed, and what they must do.
- Before doing something significant, say so in one sentence.
- Propose any idea that is not in the brief, especially on the UI side, before
  coding it.

## Out of scope for now

Card payments, subscriptions, automatic refunds, a web dashboard, and other
languages (Node, PHP, Python). They may appear in the roadmap, not in the code.
