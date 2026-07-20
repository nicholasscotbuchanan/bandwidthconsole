#!/bin/sh
# Runs on both deb and rpm, on install and on upgrade.
set -e

# Dedicated unprivileged account. The agent needs no privileges for the kernel
# data path; DSCP marking and socket options all work as a normal user.
if ! getent passwd bwagent >/dev/null 2>&1; then
    useradd --system --no-create-home --home-dir /nonexistent \
            --shell /usr/sbin/nologin --user-group bwagent 2>/dev/null \
        || useradd --system --no-create-home --home-dir /nonexistent \
                   --shell /sbin/nologin --user-group bwagent
fi

chown -R bwagent:bwagent /etc/bwagent 2>/dev/null || true
chmod 640 /etc/bwagent/bwagent.env 2>/dev/null || true

if command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload >/dev/null 2>&1 || true
    # Deliberately not enabled or started: the shipped config points at
    # 127.0.0.1, so auto-starting would register a misconfigured agent against
    # a console that probably is not there. The operator edits, then enables.
    if [ "${1:-}" = "configure" ] || [ "${1:-0}" -ge 2 ] 2>/dev/null; then
        systemctl try-restart bwagent.service >/dev/null 2>&1 || true
    fi
fi

cat <<'EOF'

bwagent installed. Before starting it:

  1. edit /etc/bwagent/bwagent.env    (set BW_CONSOLE)
  2. systemctl enable --now bwagent

EOF

exit 0
