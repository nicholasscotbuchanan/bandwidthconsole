#!/bin/sh
# Runs after files are removed, on both remove and upgrade.
set -e

is_upgrade=0
case "${1:-}" in
    upgrade|1) is_upgrade=1 ;;
esac

if command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload >/dev/null 2>&1 || true
fi

# The bwagent account and /etc/bwagent are left in place on purge as well as on
# remove. Reinstalling is common on test fleets, and silently deleting an
# operator's edited config is the worse failure of the two.
if [ "$is_upgrade" -eq 0 ]; then
    :
fi

exit 0
