#!/usr/bin/env bash
# Install M12's transcript watcher on an always-on Linux machine (docs/M12-AUDIO.md §16).
#
#   scp -r tools/m12 you@server:/tmp/inkstone-m12
#   ssh you@server 'sudo bash /tmp/inkstone-m12/deploy/install.sh'
#
# Idempotent: run it again to update the code. It never overwrites the credentials file.
#
# ⚠️ It does NOT ask for or store your passwords. It writes a template at /etc/inkstone-watcher.env
# with mode 0600 and stops, so you fill that in yourself with an editor. A script that prompted for
# an app password would put it in your shell history and in this script's memory for no benefit.

set -euo pipefail

SERVICE_USER=inkstone
INSTALL_DIR=/opt/inkstone-watcher
ENV_FILE=/etc/inkstone-watcher.env
UNIT=/etc/systemd/system/inkstone-watcher.service
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $EUID -ne 0 ]]; then
    echo "Run with sudo — it creates a system user, a unit file and /opt." >&2
    exit 1
fi

# ⚠️ 3.10 is a hard floor: the tools use PEP 604 unions (`dict | None`) in annotations that are
# evaluated at runtime, and there is no `from __future__ import annotations` anywhere under tools/.
# Checking here beats a SyntaxError in a journal nobody is watching.
python_version="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
if [[ "$(printf '%s\n3.10\n' "$python_version" | sort -V | head -1)" != "3.10" ]]; then
    echo "Python 3.10+ required; found $python_version." >&2
    exit 1
fi
echo "python3 $python_version — ok"

if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    # No login, no home, no shell. It runs four Python files.
    useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
    echo "created system user '$SERVICE_USER'"
fi

install -d -o root -g root -m 0755 "$INSTALL_DIR"
# ⚠️ Only the four modules the watcher imports. Not the tests, and emphatically not
# `*.local.properties` — the repo's credentials must not travel to a server by accident.
for f in watch.py nextcloud.py transcribe.py align.py; do
    install -o root -g root -m 0644 "$HERE/../$f" "$INSTALL_DIR/$f"
done
echo "installed code to $INSTALL_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
    cat > "$ENV_FILE" <<'ENV'
# Credentials for the Inkstone transcript watcher. Mode 0600, root-owned: systemd reads this
# before dropping to the service user, so the service user never needs access to it.

# The address you would type into a browser. HTTPS only.
INKSTONE_NC_URL=https://REPLACE-ME

# The account's uid, NOT its login name — every WebDAV path is built from it. The watcher prints
# the name the server actually reports on startup, so start it once and check the two agree.
INKSTONE_NC_UID=REPLACE-ME

# An APP PASSWORD from Nextcloud: Settings -> Security -> "Create new app password".
# Never the account password.
INKSTONE_NC_PASSWORD=REPLACE-ME

# From assemblyai.com. ⚠️ Whoever holds this can spend money; treat it like a card number.
ASSEMBLYAI_API_KEY=REPLACE-ME
ENV
    chown root:root "$ENV_FILE"
    chmod 0600 "$ENV_FILE"
    echo "wrote credential template $ENV_FILE (0600)"
else
    echo "kept existing $ENV_FILE"
fi

install -o root -g root -m 0644 "$HERE/inkstone-watcher.service" "$UNIT"
systemctl daemon-reload
echo "installed $UNIT"

if grep -q 'REPLACE-ME' "$ENV_FILE"; then
    cat <<EOF

NOT STARTED — $ENV_FILE still has REPLACE-ME in it.

  1. sudo nano $ENV_FILE
  2. sudo systemctl enable --now inkstone-watcher
  3. journalctl -u inkstone-watcher -f

Step 3 should print "connected as <your uid>". If it prints a 401 it stops rather than retrying,
on purpose: Nextcloud's brute-force protection counts failures by IP.
EOF
else
    systemctl enable --now inkstone-watcher
    echo
    echo "started. Follow it with:  journalctl -u inkstone-watcher -f"
fi
