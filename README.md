# sdk-mobile-money

Collect and disburse Mobile Money payments in Cameroon through a single
interface, whatever the provider behind it.

[![CI](https://github.com/Backaad/sdk-mobile-money/actions/workflows/ci.yml/badge.svg)](https://github.com/Backaad/sdk-mobile-money/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/Backaad/sdk-mobile-money/graph/badge.svg)](https://codecov.io/gh/Backaad/sdk-mobile-money)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> **Work in progress, not released yet.** The Java core and the Campay provider
> exist (see [CHANGELOG](CHANGELOG.md)); the other providers, the Spring Boot
> starter and the Flutter package come next. The first release will be `0.1.0`.

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
```

## License

[MIT](LICENSE)
