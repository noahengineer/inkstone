#!/usr/bin/env bash
# Start a real Nextcloud for SPEC §13.2's merge matrix and write the suite's config.
#
#   bash tools/nextcloud-docker/up.sh
#   ./gradlew :app:testDebugUnitTest --tests '*MergeMatrix*'
#
# Idempotent: re-running against a live container re-mints the app password and rewrites
# the properties file. Tear down with
#   docker compose -f tools/nextcloud-docker/docker-compose.yml down -v
#
# ⚠️ This writes an app password to nextcloud-test.local.properties, which is gitignored.
# It is a throwaway credential for a throwaway container — but it is still a credential,
# so it goes in a file the repo cannot commit rather than into a shell history.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
compose="$here/docker-compose.yml"
out="$repo/nextcloud-test.local.properties"

user="${INKSTONE_NC_ADMIN:-inkstone}"
port="${INKSTONE_NC_PORT:-8080}"

if ! command -v docker >/dev/null 2>&1; then
  cat >&2 <<'EOF'
docker is not on PATH.

SPEC §13.2 asks for a real Nextcloud in Docker. If you cannot run one, the suite also
accepts any real instance — a NAS, a Pi, a hosted account:

  printf 'url=https://cloud.example\nlogin=you\nuid=you\nappPassword=xxxx\n' \
    > nextcloud-test.local.properties

The uid is `ocs.data.id` from GET /ocs/v2.php/cloud/user, NOT the login name (SPEC §0.1 #3).
What you must NOT do is point the suite at a mock: SPEC §13.2 exists because mocks do not
reproduce weak ETags, fileid churn on trash-restore, or brute-force lockout.
EOF
  exit 1
fi

echo "==> starting Nextcloud on http://localhost:$port"
docker compose -f "$compose" up -d

echo "==> waiting for the installer to finish (first run takes ~30 s)"
deadline=$(( $(date +%s) + 300 ))
until [ "$(docker inspect -f '{{.State.Health.Status}}' inkstone-nextcloud 2>/dev/null || echo starting)" = "healthy" ]; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "!! Nextcloud did not become healthy within 5 minutes. Last 40 log lines:" >&2
    docker compose -f "$compose" logs --tail 40 nextcloud >&2
    exit 1
  fi
  sleep 3
done

# SPEC §0.1 #3 — the WebDAV path segment is `ocs.data.id`, which is NOT the login name.
# For an admin created by the container's installer the two happen to match, and relying on
# that is exactly the bug SPEC calls the most likely in the project. So it is read back from
# the server rather than assumed.
echo "==> reading the uid from the server (SPEC §0.1 #3)"
uid="$("$here/occ" user:info --output=json "$user" | sed -n 's/.*"user_id" *: *"\([^"]*\)".*/\1/p' | head -1)"
uid="${uid:-$user}"

# `user:add-app-password` prints the generated password on its own line. An app password,
# never the admin password — SPEC §14: "Never store the user's real password."
echo "==> minting an app password for '$user'"
app_password="$("$here/occ" user:add-app-password "$user" --password-from-env <<<'' 2>/dev/null | tail -1 | tr -d '\r')" || true
if [ -z "${app_password:-}" ] || printf '%s' "$app_password" | grep -qi 'error\|exception'; then
  # Older builds of the command need the password interactively; fall back to passing the
  # admin password through the environment, which occ reads without it reaching argv.
  app_password="$(INKSTONE_PW="${INKSTONE_NC_ADMIN_PASSWORD:-inkstone-test-only}" \
    docker compose -f "$compose" exec -T -u www-data \
      -e OC_PASS="${INKSTONE_NC_ADMIN_PASSWORD:-inkstone-test-only}" \
      nextcloud php occ user:add-app-password "$user" --password-from-env \
      | tail -1 | tr -d '\r')"
fi

if [ -z "${app_password:-}" ]; then
  echo "!! could not mint an app password. Create one in the web UI at" >&2
  echo "   http://localhost:$port/settings/user/security and write $out yourself." >&2
  exit 1
fi

umask 077
cat > "$out" <<EOF
# Written by tools/nextcloud-docker/up.sh — gitignored, throwaway, safe to delete.
# SPEC §13.2's merge matrix reads this when the INKSTONE_NC_* env vars are absent.
url=http://localhost:$port
login=$user
uid=$uid
appPassword=$app_password
# Lets the suite drive `occ` for the two rows a healthy server cannot produce on demand:
# row 15's nc:data-fingerprint change (an admin restoring a backup).
occ=docker compose -f tools/nextcloud-docker/docker-compose.yml exec -T -u www-data nextcloud php occ
EOF

echo
echo "==> ready. Wrote $out (gitignored)"
echo "    server  http://localhost:$port"
echo "    login   $user"
echo "    uid     $uid"
echo
echo "Run the matrix:"
echo "  ./gradlew :app:testDebugUnitTest --tests '*MergeMatrix*'"
