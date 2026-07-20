#!/usr/bin/env bash
# Build a native installer for the Bandwidth Console with jpackage.
#
# jpackage produces an installer for the OS it runs on, so run this on each
# target platform (macOS -> .dmg, Windows -> .msi, Linux -> .deb/.rpm). It needs
# the JavaFX *jmods* for that platform; point JAVAFX_JMODS at them:
#
#   # download the matching "JavaFX jmods" bundle from https://openjfx.io
#   export JAVAFX_JMODS=/path/to/javafx-jmods-23.0.1
#   packaging/build-console.sh
#
set -euo pipefail
cd "$(dirname "$0")/../console"

: "${JAVAFX_JMODS:?set JAVAFX_JMODS to the extracted JavaFX jmods directory}"
command -v jpackage >/dev/null 2>&1 || { echo "jpackage not found (needs JDK 17+)"; exit 1; }

echo ">> mvn package"
mvn -q package

JAR=$(ls target/bwconsole-*.jar | grep -v original | head -1)
echo ">> app jar: $JAR"

case "$(uname -s)" in
  Darwin) TYPE=dmg ;;
  Linux)  TYPE=deb ;;
  MINGW*|MSYS*|CYGWIN*) TYPE=msi ;;
  *) TYPE=app-image ;;
esac
echo ">> installer type: $TYPE"

rm -rf target/installer && mkdir -p target/installer target/jpkg-input
cp "$JAR" target/jpkg-input/

jpackage \
  --type "$TYPE" \
  --name "Bandwidth Console" \
  --app-version "0.1.0" \
  --vendor "bwtest" \
  --description "Control plane and report nexus for bandwidth testing" \
  --input target/jpkg-input \
  --main-jar "$(basename "$JAR")" \
  --main-class com.bwtest.console.Launcher \
  --module-path "$JAVAFX_JMODS" \
  --add-modules javafx.controls,javafx.graphics,jdk.unsupported \
  --java-options "-Dprism.lcdtext=false" \
  --dest target/installer

echo ">> installer written to console/target/installer/"
ls -la target/installer/
