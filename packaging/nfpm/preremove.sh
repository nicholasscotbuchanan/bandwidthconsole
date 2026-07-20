#!/bin/sh
# Stop the service before the binary disappears underneath it. Runs on remove
# and on the remove half of an upgrade, hence the guards: on rpm upgrades $1 is
# 1 and we must leave the running service alone for postinstall to restart.
set -e

is_upgrade=0
case "${1:-}" in
    upgrade|1) is_upgrade=1 ;;
esac

if [ "$is_upgrade" -eq 0 ] && command -v systemctl >/dev/null 2>&1; then
    systemctl --no-reload disable --now bwagent.service >/dev/null 2>&1 || true
fi

exit 0
