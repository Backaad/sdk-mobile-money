# Releasing

One tag, `vX.Y.Z`, publishes a version:

- the Java libraries (`sdk-mobile-money-parent`, `sdk-mobile-money-core`,
  `sdk-mobile-money-spring-boot-starter`) to **Maven Central**, through the
  Central Portal;
- the Flutter package `sdk_mobile_money` to **pub.dev**.

The [release workflow](../.github/workflows/release.yml) does both. It first
checks that the tag, the Java version, the Flutter package version and both
CHANGELOG files agree, and stops otherwise. No key is in the repository: they
are GitHub secrets.

## What was checked, and how

The documentation sites of Sonatype (`central.sonatype.org`), Maven
(`maven.apache.org`) and Dart (`dart.dev`) could not be reached from the
environment where this setup was written, so it was checked against primary
sources instead:

| Point | Source | Status |
|---|---|---|
| `central-publishing-maven-plugin` 0.11.0: goal `publish` in the `deploy` phase, `publishingServerId` (default `central`), `autoPublish` (default `false`), `waitUntil` (default `VALIDATED`); it hooks into the build through a lifecycle participant, hence `<extensions>true</extensions>` | the plugin's descriptor (`META-INF/maven/plugin.xml`) and classes, from Maven Central | checked |
| `maven-gpg-plugin` 3.2.8: `signer=bc` signs in pure Java, with the key in `MAVEN_GPG_KEY` and the passphrase in `MAVEN_GPG_PASSPHRASE` | the plugin's descriptor | checked; a full signed build was run with a throwaway key |
| `setup-java@v6`: `server-id`, `server-username-env-var`, `server-password-env-var` write a `<server>` to `settings.xml` | the action's `action.yml` | checked |
| pub.dev: the first version of a package must be published by hand; automated publishing is then enabled on the package's admin page, with the repository and a tag pattern containing `{{version}}`; only a workflow triggered by a tag push can publish | the source of the dart.dev page "Automated publishing" (`dart-lang/site-www`) | checked |
| `dart-lang/setup-dart/.github/workflows/publish.yml@v1`: sets up Dart and Flutter, runs `dart pub publish --dry-run` then `dart pub publish -f` in `working-directory` | the workflow's source | checked |
| Central Portal: the namespace `io.github.backaad` is registered and shown as **Verified**, and a user token exists | the maintainer's Central Portal account (2026-10-11) | checked |
| Central Portal: the POMs have every required field, and the signatures are accepted with the public key sent to `keyserver.ubuntu.com` and `keys.openpgp.org` | the validation of the 0.1.0 deployment (2026-10-11) | checked |
| Central Portal: which key server is actually used | the Sonatype documentation | to be confirmed |

The sources and Javadoc jars are built on every pull request (CI, JDK 21), and
`flutter pub publish --dry-run` runs on every pull request too.

## Once: Maven Central

1. Create an account on [central.sonatype.com](https://central.sonatype.com)
   and register the namespace `io.github.backaad`. Done: the namespace is
   verified. Namespaces are tied to the sign-in method: always sign in the
   same way.
2. Generate a **user token** in the Central Portal (done). Add its two parts
   as repository secrets (Settings > Secrets and variables > Actions):
   `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`. The password of the
   account itself is never used. Then keep the token only in a password
   manager, never in a file next to the repository.
3. Create a GPG key for signing, and publish its public part on a key server
   (Central checks the signatures against public keys; to be confirmed which
   servers):

   ```sh
   gpg --quick-gen-key "Your Name <you@example.com>" rsa4096 sign 2y
   gpg --list-secret-keys --keyid-format long      # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>        # the value of MAVEN_GPG_KEY
   ```

   Add the secrets `MAVEN_GPG_KEY` (the armored private key, with its
   `-----BEGIN` and `-----END` lines) and `MAVEN_GPG_PASSPHRASE`. Never paste
   them anywhere else.

## Once: pub.dev

1. Sign in to [pub.dev](https://pub.dev) with a Google account.
2. The first version is published by hand, from a machine with the Flutter SDK
   (no emulator needed), on the release tag:

   ```sh
   git checkout v0.1.0
   cd flutter/sdk_mobile_money
   flutter pub publish
   ```

3. On `pub.dev/packages/sdk_mobile_money/admin`, enable **automated publishing
   from GitHub Actions**: repository `Backaad/sdk-mobile-money`, tag pattern
   `v{{version}}`.
4. Add the repository **variable** (not secret) `PUB_DEV_AUTOMATED_PUBLISHING`
   with the value `true`: from then on, the release workflow publishes the
   package. Until then, its pub.dev job is skipped.

Optional hardening, recommended by the pub.dev documentation: a GitHub
deployment environment required on pub.dev, and a tag protection rule on `v*`,
since anyone who can push such a tag can publish.

## Each release

1. On a branch, set the version and write the CHANGELOG sections:

   ```sh
   scripts/set-version.sh 0.1.0
   ```

   For a release version, the script also updates the installation snippets
   of `README.md`, `README.fr.md`, `docs/spring-boot.md` and the Flutter
   package README (pub.dev version and git `ref`).

   - `CHANGELOG.md`: rename `## [Unreleased]` to `## [0.1.0] - <date>` (the
     day of the tag), add a new empty `## [Unreleased]` above it, and update
     the comparison links at the bottom of the file;
   - `flutter/sdk_mobile_money/CHANGELOG.md`: rename `## 0.1.0-dev` to
     `## 0.1.0`.

   Open the pull request, wait for the CI, merge.
2. Tag the merge commit on `main` and push the tag:

   ```sh
   git checkout main && git pull
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. Follow the **Release** workflow. When its Maven Central job is green, the
   deployment is validated but not published: open **Deployments** on
   central.sonatype.com, check it, and click **Publish**. It then takes a while
   to appear on Maven Central.
4. pub.dev: published by the workflow, or by hand for the first version (see
   above).
5. Back to development versions, in a new pull request:

   ```sh
   scripts/set-version.sh 0.1.1-SNAPSHOT     # Flutter package: 0.1.1-dev
   ```

   with `## 0.1.1-dev` at the top of the Flutter package CHANGELOG.

The README of the Flutter package is the pub.dev page of the version: it must
not say whether the package is published, since that changes after the tag.

Once the package is on pub.dev, the README can get its badge (the Maven Central
one is there since 0.1.0):

```md
[![Maven Central](https://img.shields.io/maven-central/v/io.github.backaad/sdk-mobile-money-core)](https://central.sonatype.com/artifact/io.github.backaad/sdk-mobile-money-core)
[![pub.dev](https://img.shields.io/pub/v/sdk_mobile_money)](https://pub.dev/packages/sdk_mobile_money)
```
