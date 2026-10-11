# Contributing

Thank you for helping. Bug reports, documentation fixes, sandbox results and new
providers are all welcome. Issues can be written in English or French; code,
comments, documentation and commit messages are in English, except
`README.fr.md`.

By contributing, you agree to follow the [code of conduct](CODE_OF_CONDUCT.md),
and that your contribution is published under the [MIT license](LICENSE).

## Never share a secret

No key, token, signature or webhook body containing one, in code, tests,
fixtures, documentation, commit messages, issues or pull requests. Not even a
sandbox key. Put your own sandbox keys in a local `.env` file (ignored by git),
under the names listed in [`.env.example`](.env.example). Mask phone numbers in
logs you paste. A security problem is reported privately: see
[SECURITY.md](SECURITY.md).

## Set up

- JDK 17 or later; Maven comes with the wrapper (`./mvnw`).
- Flutter, stable channel.
- Docker, to run the demo server with Redis (optional).

```sh
(cd java && ./mvnw verify)                                                         # Java: build, tests, coverage
(cd java && ./mvnw install -DskipTests && ./mvnw -f ../examples/spring-boot-demo/pom.xml verify)   # demo server
(cd flutter/sdk_mobile_money && flutter pub get && flutter analyze && flutter test) # Flutter package
(cd examples/flutter-demo && flutter pub get && flutter analyze && flutter test)    # demo app
```

The starter's Redis tests use `REDIS_URL` or a local `redis-server`, and are
skipped when neither exists. Sandbox tests run only when your keys are loaded
(`set -a; . ./.env; set +a`); otherwise they are skipped, never failed.

## The rules

The durable rules of the project are in [CLAUDE.md](CLAUDE.md), and the
security rules, with their tests, in [docs/security.md](docs/security.md). The
ones reviews check first:

- **Never present as verified something that is not.** Anything not confirmed
  in a provider's official documentation stays marked "to be confirmed", in
  code comments, docs and the README provider table.
- **The core has no other runtime dependency** than the JDK HTTP client,
  Jackson and SLF4J. Adding one needs the maintainer's approval.
- **Every reliability or security rule has a test.** A change that touches one
  comes with its test.
- **Phone number rules** change in the Java core, the Dart package and
  `testdata/phone-numbers.json` together.
- **Logs** never contain headers, query strings or bodies.

## Pull requests

1. For anything larger than a fix, open an issue first, so that we agree on the
   approach.
2. One subject per pull request. Keep the CI green: Java on JDK 17 and 21, the
   demo server, the Docker image, the Flutter package and the demo app.
3. Update [CHANGELOG.md](CHANGELOG.md) (and the Flutter package's CHANGELOG
   when it changes), under `Unreleased`.
4. Say in the description what you verified and how, and what remains to be
   confirmed.

## Adding a provider

Open a **New provider** issue first. Then:

1. Read the provider's official documentation and write
   `docs/providers/<provider>.md`: the sources and their date, what was checked,
   what differs from what was expected, and every point still to be confirmed.
2. Implement `PaymentProvider` in `io.github.backaad.mobilemoney.provider.<provider>`:
   collection, payout and status read when the provider has them, webhook
   verification, and the capabilities that say which.
3. Classify every failure. `ProviderUnavailableException` with
   `requestMayHaveBeenProcessed == false` only when the request certainly did
   not reach the provider; anything after sending is an unknown outcome. Never
   retry a POST, except once after a `401` with a renewed token. Every call has
   a timeout; redirects are not followed.
4. Add the provider's number format to `ProviderPhoneFormat`, with its tests.
5. Test with WireMock, with answers taken from the documentation: success and
   refusal, a `401` followed by a token renewal, a `5xx`, an empty answer, an
   unknown status. Add the provider to the no-secret-in-logs tests. A sandbox
   test, if the provider has a sandbox, runs only when its keys are set.
6. Wire it in `ProviderFactory` (variables in `.env.example`) and in the
   starter's `mobile-money.<provider>.*` properties, checked at startup.
7. Add it to the provider tables of `README.md`, `README.fr.md` and
   `docs/providers/README.md`, with what was really verified.

## Releases

Releases are made by the maintainer: see [docs/releasing.md](docs/releasing.md).
