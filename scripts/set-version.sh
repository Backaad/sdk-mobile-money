#!/usr/bin/env bash
# Sets the version of everything that is published, in one go (see docs/releasing.md):
#
#   scripts/set-version.sh 0.1.0            before a release
#   scripts/set-version.sh 0.1.1-SNAPSHOT   after it; the Flutter package then gets 0.1.1-dev
#
# It changes the Java POMs, the SDK version used by the demo server, and the version of the
# Flutter package. The CHANGELOG sections are written by hand.
set -euo pipefail

version="${1:-}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
  echo "usage: scripts/set-version.sh <version>, for example 0.1.0 or 0.1.1-SNAPSHOT" >&2
  exit 1
fi
dart_version="${version%-SNAPSHOT}"
if [[ "$version" == *-SNAPSHOT ]]; then
  dart_version="$dart_version-dev"
fi
root="$(cd "$(dirname "$0")/.." && pwd)"

# Java: the parent POM and its modules.
(cd "$root/java" && ./mvnw --quiet --batch-mode --no-transfer-progress \
  versions:set -DnewVersion="$version" -DgenerateBackupPoms=false)

# The demo server builds against the SDK of this repository.
demo_pom="$root/examples/spring-boot-demo/pom.xml"
sed -i.bak "s|<sdk-mobile-money.version>[^<]*</sdk-mobile-money.version>|<sdk-mobile-money.version>$version</sdk-mobile-money.version>|" "$demo_pom"
rm "$demo_pom.bak"

# Flutter package.
pubspec="$root/flutter/sdk_mobile_money/pubspec.yaml"
sed -i.bak "s|^version: .*|version: $dart_version|" "$pubspec"
rm "$pubspec.bak"

echo "Java $version, Flutter package $dart_version."
echo "Now write the CHANGELOG sections: '## [$version] - <date>' in CHANGELOG.md"
echo "and '## $dart_version' in flutter/sdk_mobile_money/CHANGELOG.md."
