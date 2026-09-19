#!/bin/bash
# Downloads the FET sources this project is built against and checks them against the published SHA256.
# The sources are not in git: they are 354 MB and belong to FET's authors.
#
# Usage: ./scripts/fetch-fet.sh
set -euo pipefail

VERSION="7.10.4"
SHA256="6882fd384e0ff074de14d4349d8a3857641d086fcb74caa69f24c91cdf0e4a3d"
ARCHIVE="fet-$VERSION.tar.xz"
URL="https://www.lalescu.ro/liviu/fet/download/$ARCHIVE"

cd "$(dirname "$0")/.."
mkdir -p _upstream
cd _upstream

if [ -d "fet-$VERSION" ]; then
  echo "FET $VERSION is already here: $(pwd)/fet-$VERSION"
  exit 0
fi

echo "Downloading $URL"
curl -fL --retry 3 -o "$ARCHIVE" "$URL"

echo "Checking the SHA256 against the one published on the FET download page"
if command -v shasum >/dev/null; then
  echo "$SHA256  $ARCHIVE" | shasum -a 256 -c -
else
  echo "$SHA256  $ARCHIVE" | sha256sum -c -
fi

tar xf "$ARCHIVE"
echo
echo "Done: $(pwd)/fet-$VERSION"
echo "You can now run ./gradlew test, and ./gradlew schemagen to rebuild the constraint schema."
