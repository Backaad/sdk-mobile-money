# sdk-mobile-money

Collect and disburse Mobile Money payments in Cameroon through a single
interface, whatever the provider behind it.

[![CI](https://github.com/Backaad/sdk-mobile-money/actions/workflows/ci.yml/badge.svg)](https://github.com/Backaad/sdk-mobile-money/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/Backaad/sdk-mobile-money/graph/badge.svg)](https://codecov.io/gh/Backaad/sdk-mobile-money)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> **Work in progress, not released yet.** The Java core, the providers below and
> the [Spring Boot starter](docs/spring-boot.md) exist (see
> [CHANGELOG](CHANGELOG.md)); the Flutter package comes next. The first release
> will be `0.1.0`.

## Providers

| Provider | Collection | Payout | Status read | Webhooks | Verified against |
|---|---|---|---|---|---|
| [Campay](docs/providers/campay.md) | yes | yes | yes | JWT HS256, then status read | Campay's own SDKs; details to be confirmed |
| [MTN MoMo](docs/providers/mtn-momo.md) | yes | yes | yes | unsigned, status read | to be confirmed |
| [NotchPay](docs/providers/notchpay.md) | yes | yes | yes | HMAC-SHA256, then status read | to be confirmed |
| [Tara](docs/providers/tara.md) (experimental) | yes | no | no | single-use callback URL | to be confirmed |

No provider has been run against its sandbox by the project yet: it has no keys
of its own. Failover to another provider happens only if the merchant
configures it, and only when nothing reached the first provider.

## Layout

| Path | Content |
|---|---|
| `java/sdk-mobile-money-core` | Java 17+ server library, no Spring |
| `java/sdk-mobile-money-spring-boot-starter` | Spring Boot integration |
| `flutter/sdk_mobile_money` | Flutter package; talks only to the merchant's server |
| `examples/` | Demo server and demo app |
| `docs/` | Documentation |

## Build

```sh
# Java: build, tests and coverage report
(cd java && ./mvnw verify)

# Flutter
(cd flutter/sdk_mobile_money && flutter pub get && flutter analyze && flutter test)

# Demo server and Redis (simulation by default)
(cd examples/spring-boot-demo && docker compose up --build)
```

## License

[MIT](LICENSE)
