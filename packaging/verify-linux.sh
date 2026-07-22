#!/usr/bin/env bash
# Installs the built packages into clean containers of each supported distro
# family and checks they actually work. Exits non-zero on any failure.
#
#   packaging/verify-linux.sh              # this host's architecture
#   packaging/verify-linux.sh arm64
#
# Checks per distro:
#   - console and agent packages install with dependencies resolved
#   - the bundled agent and the standalone agent both run
#   - the jlinked runtime contains the modules the app needs at runtime
#   - packages remove without leaving files behind
#
# Why this exists: every serious bug found while building these packages
# (missing java.net.http, an agent needing GLIBC_2.39, undeclared GTK) produced
# a package that built and installed perfectly and failed only once running.
# Checking that jpackage exited 0 proves nothing.

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$REPO_ROOT/packaging/dist"

ENGINE="${ENGINE:-}"
if [[ -z "$ENGINE" ]]; then
    command -v podman >/dev/null 2>&1 && ENGINE=podman || ENGINE=docker
fi

ARCH="${1:-}"
if [[ -z "$ARCH" ]]; then
    case "$(uname -m)" in
        arm64|aarch64) ARCH=arm64 ;;
        x86_64|amd64)  ARCH=amd64 ;;
    esac
fi
# Package filenames use each family's own arch spelling.
case "$ARCH" in
    arm64) DEB_ARCH=arm64; RPM_ARCH=aarch64 ;;
    amd64) DEB_ARCH=amd64; RPM_ARCH=x86_64 ;;
    *) echo "!! arch must be arm64 or amd64" >&2; exit 1 ;;
esac

[[ -d "$DIST_DIR" ]] || { echo "!! no packaging/dist — run packaging/build-linux.sh first" >&2; exit 1; }

failures=0
report() {
    if [[ "$1" == "0" ]]; then printf '   PASS  %s\n' "$2"
    else printf '   FAIL  %s\n' "$2"; failures=$((failures + 1)); fi
}

# The console package is a desktop app: its postinst runs xdg-desktop-menu,
# which needs menu directories a bare container image does not have. Creating
# them models a real desktop install rather than papering over a defect.
DESKTOP_SETUP='mkdir -p /usr/share/desktop-directories /usr/share/applications'

echo "== verifying $ARCH packages in $DIST_DIR"

# ---------------------------------------------------------------- Debian ----
echo
echo "-- debian:12 (deb)"
deb_out=$("$ENGINE" run --rm --platform "linux/$ARCH" -v "$DIST_DIR:/pkg:ro" debian:12 bash -c "
set -e
$DESKTOP_SETUP
apt-get -qq update >/dev/null 2>&1
apt-get install -y /pkg/bwconsole_*_${DEB_ARCH}.deb >/tmp/ic.log 2>&1 && echo 'INSTALL_CONSOLE ok' || { echo '--- INSTALL_CONSOLE failed ---'; tail -25 /tmp/ic.log; }
apt-get install -y /pkg/bwagent_*_${DEB_ARCH}.deb  >/tmp/ia.log 2>&1 && echo 'INSTALL_AGENT ok' || { echo '--- INSTALL_AGENT failed ---'; tail -25 /tmp/ia.log; }
/opt/bwconsole/lib/app/bwagent --version >/tmp/ba.log 2>&1 && echo 'BUNDLED_AGENT ok' || { echo '--- BUNDLED_AGENT failed ---'; tail -5 /tmp/ba.log; }
/usr/bin/bwagent --version >/tmp/sa.log 2>&1 && echo 'STANDALONE_AGENT ok' || { echo '--- STANDALONE_AGENT failed ---'; tail -5 /tmp/sa.log; }
grep -q 'java.net.http' /opt/bwconsole/lib/runtime/release && echo 'MODULE_HTTP ok'
grep -q 'javafx.controls' /opt/bwconsole/lib/runtime/release && echo 'MODULE_JAVAFX ok'
# The app must carry its own JVM and never look for a system one. This image
# has no java at all, so if the app runs, the bundled runtime is doing the work.
command -v java >/dev/null 2>&1 || echo 'NO_SYSTEM_JAVA ok'
dpkg -s bwconsole 2>/dev/null | grep -i '^Depends' | grep -qiE 'jre|jdk|openjdk|java' || echo 'NO_JAVA_DEP ok'
[ -f /opt/bwconsole/lib/runtime/lib/server/libjvm.so ] && echo 'BUNDLED_JVM ok'
apt-get -qq purge -y bwconsole bwagent >/dev/null 2>&1
[ ! -e /opt/bwconsole ] && [ ! -e /usr/bin/bwagent ] && echo 'CLEAN_REMOVE ok'
" 2>&1) || true

for check in INSTALL_CONSOLE INSTALL_AGENT BUNDLED_AGENT STANDALONE_AGENT \
             MODULE_HTTP MODULE_JAVAFX NO_SYSTEM_JAVA NO_JAVA_DEP BUNDLED_JVM CLEAN_REMOVE; do
    grep -q "^$check ok" <<<"$deb_out" && report 0 "debian12 $check" || report 1 "debian12 $check"
done

# ------------------------------------------------------------------ RHEL ----
echo
echo "-- rockylinux:9 (rpm)"
rpm_out=$("$ENGINE" run --rm --platform "linux/$ARCH" -v "$DIST_DIR:/pkg:ro" rockylinux/rockylinux:9 bash -c "
set -e
$DESKTOP_SETUP
dnf -y install /pkg/bwconsole-*.${RPM_ARCH}.rpm >/tmp/ic.log 2>&1 && echo 'INSTALL_CONSOLE ok' || { echo '--- INSTALL_CONSOLE failed ---'; tail -25 /tmp/ic.log; }
dnf -y install /pkg/bwagent-*.${RPM_ARCH}.rpm  >/tmp/ia.log 2>&1 && echo 'INSTALL_AGENT ok' || { echo '--- INSTALL_AGENT failed ---'; tail -25 /tmp/ia.log; }
/opt/bwconsole/lib/app/bwagent --version >/tmp/ba.log 2>&1 && echo 'BUNDLED_AGENT ok' || { echo '--- BUNDLED_AGENT failed ---'; tail -5 /tmp/ba.log; }
/usr/bin/bwagent --version >/tmp/sa.log 2>&1 && echo 'STANDALONE_AGENT ok' || { echo '--- STANDALONE_AGENT failed ---'; tail -5 /tmp/sa.log; }
grep -q 'java.net.http' /opt/bwconsole/lib/runtime/release && echo 'MODULE_HTTP ok'
# Test -f first: 'ldd missing-file | grep -q not-found' fails the grep and
# would report a pass for a launcher that was never installed.
[ -f '/opt/bwconsole/bin/Bandwidth Console' ] && \
  ! ldd '/opt/bwconsole/bin/Bandwidth Console' 2>&1 | grep -q 'not found' && echo 'LAUNCHER_LIBS ok'
command -v java >/dev/null 2>&1 || echo 'NO_SYSTEM_JAVA ok'
rpm -qR bwconsole 2>/dev/null | grep -qiE 'jre|jdk|openjdk|^java' || echo 'NO_JAVA_DEP ok'
[ -f /opt/bwconsole/lib/runtime/lib/server/libjvm.so ] && echo 'BUNDLED_JVM ok'
dnf -q -y remove bwconsole bwagent >/dev/null 2>&1
[ ! -e /opt/bwconsole ] && [ ! -e /usr/bin/bwagent ] && echo 'CLEAN_REMOVE ok'
" 2>&1) || true

for check in INSTALL_CONSOLE INSTALL_AGENT BUNDLED_AGENT STANDALONE_AGENT MODULE_HTTP \
             LAUNCHER_LIBS NO_SYSTEM_JAVA NO_JAVA_DEP BUNDLED_JVM CLEAN_REMOVE; do
    grep -q "^$check ok" <<<"$rpm_out" && report 0 "rocky9 $check" || report 1 "rocky9 $check"
done

echo
if [[ "$failures" -eq 0 ]]; then
    echo "== all checks passed"
else
    echo "== $failures check(s) FAILED"
    echo "--- debian output ---"; echo "$deb_out" | tail -20
    echo "--- rocky output ---";  echo "$rpm_out" | tail -20
    exit 1
fi
