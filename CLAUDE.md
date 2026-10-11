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
  signature in code, tests, fixtures, docs or commit messages. The project has
  no keys of its own and the CI runs without any: each developer puts their own
  sandbox keys in a local `.env` (ignored by git), under the names listed in
  [`.env.example`](.env.example).
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
docs/                                     architecture, security, releasing, docs/providers/
scripts/set-version.sh                    sets every published version at once
.github/workflows/release.yml             publishes on a vX.Y.Z tag (docs/releasing.md)
```

## Commands

```sh
# Java (from java/): build, tests, coverage report
./mvnw verify

# Demo server (from java/, after ./mvnw install)
./mvnw -f ../examples/spring-boot-demo/pom.xml verify

# Flutter (from flutter/sdk_mobile_money/, then from examples/flutter-demo/)
flutter pub get
flutter analyze
flutter test

# Release checks, without publishing anything
./mvnw -Prelease -Dgpg.skip -DskipTests package   # from java/: sources and Javadoc jars
flutter pub publish --dry-run                     # from flutter/sdk_mobile_money/
```

CI (`.github/workflows/ci.yml`) runs the Java build on JDK 17 and 21 with a
Redis service, the demo server's tests, the release artifacts (JDK 21), a Docker
Compose smoke test of the demo image, `flutter analyze` + `flutter test` for the
package and the demo app, `flutter pub publish --dry-run`, and uploads coverage
to Codecov. Keep it green.

Releases follow [`docs/releasing.md`](docs/releasing.md): `scripts/set-version.sh`,
the CHANGELOG sections, then a `vX.Y.Z` tag. The release workflow checks that
they agree, then publishes to Maven Central (published by hand on the Central
Portal) and pub.dev. Its keys are GitHub secrets, never files.

The starter's Redis tests use `REDIS_URL`, or a local `redis-server`; they are
skipped when neither exists, except in CI (`MOBILE_MONEY_REQUIRE_REDIS=true`).

Sandbox tests read the provider keys from environment variables and are
skipped when they are missing: to run them, load your own `.env` first
(`set -a; . ./.env; set +a`).

## Java

- Java 17 minimum (`maven.compiler.release` 17). Maven group `io.github.backaad`,
  base package `io.github.backaad.mobilemoney`.
- **Core**: no Spring. Runtime dependencies are limited to the JDK HTTP client
  (`java.net.http`), Jackson and SLF4J. The Maven enforcer fails the build on
  any other runtime dependency; adding one needs the maintainer's approval.
- **Core JSON**: Jackson 3 (`tools.jackson`), the version line Spring Boot 4
  uses. Jackson stays an implementation detail: no Jackson type in the public
  API.
- **Starter**: Spring Boot 4 integration. Configuration under `mobile-money.*`,
  validated at startup with clear messages that name the property and never a
  value. Redis is optional (`spring-data-redis` is an optional dependency); the
  stores' Redis format is explicit JSON with a version field (`RedisCodec`).
- Dependency and plugin versions are managed in `java/pom.xml`.

## Core design (Java)

- `MobileMoney` is the entry point. It owns idempotency, status re-reads and
  webhook verification; each provider implements `provider.PaymentProvider`
  and only talks to its API.
- Provider adapters classify every failure. `ProviderUnavailableException`
  with `requestMayHaveBeenProcessed == false` only when the request certainly
  did not reach the provider (connection refused, token failure, 408/429).
  Everything else after sending (timeout, 5xx, unreadable answer, unexpected
  exception) is an unknown outcome: the facade returns a PENDING transaction
  without provider reference and never sends it again.
- Never retry a POST, except the single replay after a 401 with a renewed
  token. Every HTTP call has a timeout; redirects are not followed.
- Webhook events only carry what the provider's API returned; the claimed
  status is kept for diagnostics. Signatures go through `internal.ConstantTime`.
- Logs contain the method, path, status and duration of calls, never headers,
  query strings or bodies. Never chain Jackson or HTTP client exceptions:
  their messages can contain response content.
- Reference phone numbers and the default prefix table live in
  `testdata/phone-numbers.json`, read by both the Java and the Dart tests: a
  rule change goes in both languages and in that file.

## Flutter

- Package `sdk_mobile_money`. It never embeds a provider key and never calls a
  provider API: it only calls the merchant's server.
- Phone number normalization and provider detection follow exactly the same
  rules as the Java core, including which characters count as separators
  (Java's `Character.isWhitespace` and `isSpaceChar`, not Dart's `trim`). Both
  sides test against the same shared JSON file of reference numbers.
- Dependencies: `http` only. The client talks to the server contract of the
  demo server (`POST <payments>` with `Idempotency-Key`, `GET
  <payments>/<reference>`), documented in the package README.
- A retry sends the same `PaymentRequest`, hence the same idempotency key. A
  transient error or a timeout while following a payment never shows it as
  failed. The client logs nothing.
- The UI component must work in light and dark themes: colors come from the
  theme's `ColorScheme`, and the widget tests check the contrast of each state
  in both themes. Official operator logos are not bundled (trademarks).

## Payment domain rules

- Amounts are integers. Currency `XAF` first, `XOF` planned.
- Statuses are `PENDING`, `SUCCESSFUL` or `FAILED`; always keep the provider's
  raw status for diagnostics.
- Every operation that debits or disburses goes through an idempotency key
  valid for 24 hours: a repeated request returns the first result.
- Failover to another provider happens only if the merchant configured it, and
  the result says which provider actually processed the payment.
- The phone prefix table is configurable. Its default value must be checked
  against the numbering plan of ART (Agence de Régulation des
  Télécommunications, the Cameroon regulator; the brief says "ARTP", which is
  Senegal's). Blocks not confirmed by every source are left out of the
  default: the number is accepted, its operator is not guessed.
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
- Semantic versioning. Keep `CHANGELOG.md` up to date in every pull request
  (and `flutter/sdk_mobile_money/CHANGELOG.md` when the package changes, under
  its `## X.Y.Z-dev` section). First release: `0.1.0`.
- `README.md` and `README.fr.md` say the same thing: change both. The README
  animation (`flutter/sdk_mobile_money/screenshots/demo.gif`, also the pub.dev
  screenshot) is rendered from the demo app in the Flutter test engine, and
  says so; it is not a screen recording.
- Community files: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`
  (private reporting through GitHub), issue forms in `.github/ISSUE_TEMPLATE/`.
- Work step by step: one branch and one pull request per step. At the end of
  each step, give the maintainer a short report: what is done, what is
  verified and how, what remains to be confirmed, and what they must do.
- Before doing something significant, say so in one sentence.
- Propose any idea that is not in the brief, especially on the UI side, before
  coding it.

## Out of scope for now

Card payments, subscriptions, automatic refunds, a web dashboard, and other
languages (Node, PHP, Python). They may appear in the roadmap, not in the code.
