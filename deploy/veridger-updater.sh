#!/bin/sh
# deploy/veridger-updater.sh: least-privilege Linux host updater invoked by systemd <name>-updater.path when
# ledger-admin-web stages release JARs and writes /etc/<name>/update/request.properties.
# Security model: never trusts SHA256SUMS from the service-user-writable update directory, never runs chown on
# service-writable paths, rejects symlinks at every segment, copies staged JARs into a root-owned 0700 temp directory
# before fetching authoritative SHA256SUMS over HTTPS from the pinned repository and verifying every digest, installs
# 0644 root:root JARs atomically, checks both /healthz endpoints, and rolls back automatically on failure.
set -eu
umask 022

NAME=veridger
MCP_PORT=8765
ADMIN_PORT=8865
REPO=jaeseongs95/veridger

for arg in "$@"; do
  case $arg in
    --name=*) NAME=${arg#*=} ;;
    --mcp-port=*) MCP_PORT=${arg#*=} ;;
    --admin-port=*) ADMIN_PORT=${arg#*=} ;;
    --repo=*) REPO=${arg#*=} ;;
    *) echo "veridger-updater.sh: unknown option $arg" >&2; exit 1 ;;
  esac
done

case $NAME in
  ''|[!a-z]*|*[!a-z0-9-]*|*-|????????????????????????*) echo "veridger-updater.sh: invalid instance name" >&2; exit 1 ;;
esac
printf '%s\n' "$MCP_PORT" | grep -Eq '^[0-9]+$' || { echo "veridger-updater.sh: invalid mcp-port" >&2; exit 1; }
printf '%s\n' "$ADMIN_PORT" | grep -Eq '^[0-9]+$' || { echo "veridger-updater.sh: invalid admin-port" >&2; exit 1; }
printf '%s\n' "$REPO" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' || { echo "veridger-updater.sh: invalid repo" >&2; exit 1; }

LIB=/opt/$NAME
ETC=/etc/$NAME
UPD_DIR=$ETC/update
STAGED=$UPD_DIR/staged
REQ=$UPD_DIR/request.properties
STATUS=$UPD_DIR/status.properties
SVC=$NAME
ADMIN=$NAME-admin

# Abort immediately without touching service-writable paths if any path segment is a symbolic link.
for p in "$ETC" "$UPD_DIR" "$REQ"; do
  if [ -L "$p" ]; then
    echo "veridger-updater.sh: refused symbolic link: $p" >&2
    exit 1
  fi
done
[ -f "$REQ" ] || exit 0
if [ -L "$STAGED" ]; then
  echo "veridger-updater.sh: refused symbolic link: $STAGED" >&2
  exit 1
fi

WORK=$(mktemp -d)
chmod 0700 "$WORK"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

safe_remove_request() {
  if [ ! -L "$ETC" ] && [ ! -L "$UPD_DIR" ] && [ ! -L "$REQ" ]; then
    rm -f "$REQ" || true
  fi
}

write_status() {
  st=$1
  ver=$2
  raw_msg=$3
  # Never write into a symlinked update directory or symlinked status file, and never call chown.
  if [ -L "$ETC" ] || [ -L "$UPD_DIR" ] || [ -L "$STATUS" ]; then
    echo "veridger-updater.sh: refused symlink during status write" >&2
    return 1
  fi
  msg=$(printf '%s' "$raw_msg" | tr '\r\n' '  ' | sed 's/\\/\\\\/g')
  now=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  printf 'state=%s\ntargetVersion=%s\nupdatedAt=%s\ndetail=%s\n' "$st" "$ver" "$now" "$msg" > "$WORK/status.properties"
  chmod 0644 "$WORK/status.properties"
  mv -f "$WORK/status.properties" "$STATUS"
}

fail_and_cleanup() {
  ver=$1
  msg=$2
  write_status "FAILED" "$ver" "$msg" || true
  safe_remove_request
  exit 1
}

command -v curl >/dev/null 2>&1 || fail_and_cleanup "" "curl is required on the host for HTTPS SHA256SUMS and /healthz verification"
command -v sha256sum >/dev/null 2>&1 || fail_and_cleanup "" "sha256sum is required on the host"

TARGET_VER=$(awk -F= '$1=="targetVersion"{print $2; exit}' "$REQ" | tr -d '\r')
if ! printf '%s\n' "$TARGET_VER" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  fail_and_cleanup "" "invalid targetVersion in request.properties"
fi

# Fetch authoritative SHA256SUMS directly over HTTPS from the root-pinned GitHub repository.
SUMS_URL="https://github.com/$REPO/releases/download/v$TARGET_VER/SHA256SUMS"
curl -fsSL --proto '=https' --tlsv1.2 --max-time 20 "$SUMS_URL" -o "$WORK/SHA256SUMS" \
  || fail_and_cleanup "$TARGET_VER" "failed to fetch authoritative SHA256SUMS over HTTPS"

lookup_manifest_hash() {
  jar_name=$1
  awk -v target="$jar_name" '
    { sub(/\r$/, "") }
    $0 ~ /^[0-9a-fA-F]{64}[[:space:]]+\*?[^[:space:]\/\\]+$/ {
      fname = $2; sub(/^\*/, "", fname);
      if (fname == target) { count++; val = tolower($1) }
    }
    END { if (count == 1) print val }
  ' "$WORK/SHA256SUMS"
}

# Copy each staged JAR into the root-owned 0700 work directory first (eliminating TOCTOU), then verify SHA-256.
for jar in ledger-cli.jar ledger-mcp-server.jar ledger-admin-web.jar; do
  src="$STAGED/$jar"
  [ -f "$src" ] && [ ! -L "$src" ] || fail_and_cleanup "$TARGET_VER" "missing or symlinked staged jar: $jar"
  cp -P "$src" "$WORK/$jar" || fail_and_cleanup "$TARGET_VER" "failed to copy staged jar: $jar"
  [ -f "$WORK/$jar" ] && [ ! -L "$WORK/$jar" ] || fail_and_cleanup "$TARGET_VER" "non-regular staged artifact: $jar"
  manifest_hash=$(lookup_manifest_hash "$jar")
  [ -n "$manifest_hash" ] || fail_and_cleanup "$TARGET_VER" "SHA256SUMS missing entry for $jar"
  req_hash=$(awk -F= -v k="sha256.$jar" '$1==k{print tolower($2); exit}' "$REQ" | tr -d '\r')
  actual=$(sha256sum "$WORK/$jar" | awk '{print tolower($1)}')
  [ -n "$req_hash" ] && [ "$actual" = "$req_hash" ] && [ "$actual" = "$manifest_hash" ] \
    || fail_and_cleanup "$TARGET_VER" "SHA-256 mismatch for $jar"
done

BACKUP="$LIB/backup-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$BACKUP"
for jar in ledger-cli.jar ledger-mcp-server.jar ledger-admin-web.jar; do
  [ -f "$LIB/$jar" ] && cp -p "$LIB/$jar" "$BACKUP/$jar"
done

rollback() {
  reason=$1
  for jar in ledger-cli.jar ledger-mcp-server.jar ledger-admin-web.jar; do
    if [ -f "$BACKUP/$jar" ]; then
      install -m 0644 -o root -g root "$BACKUP/$jar" "$LIB/$jar.new" || true
      mv -f "$LIB/$jar.new" "$LIB/$jar" || true
    fi
  done
  systemctl restart "$SVC" "$ADMIN" || true
  write_status "ROLLED_BACK" "$TARGET_VER" "$reason" || true
  safe_remove_request
  exit 1
}

for jar in ledger-cli.jar ledger-mcp-server.jar ledger-admin-web.jar; do
  install -m 0644 -o root -g root "$WORK/$jar" "$LIB/$jar.new" || rollback "failed to stage $jar into $LIB"
  mv -f "$LIB/$jar.new" "$LIB/$jar" || rollback "failed to replace $jar"
done

systemctl restart "$SVC" "$ADMIN" || rollback "systemctl restart failed"

check_health() {
  port=$1
  i=0
  while [ "$i" -lt 30 ]; do
    body=$(curl -fsS --max-time 2 "http://127.0.0.1:$port/healthz" 2>/dev/null || true)
    case $body in
      *'"status":"UP"'*) return 0 ;;
    esac
    i=$((i + 1))
    sleep 1
  done
  return 1
}

check_health "$MCP_PORT" || rollback "MCP /healthz did not return UP within 30s"
check_health "$ADMIN_PORT" || rollback "Admin /healthz did not return UP within 30s"

write_status "SUCCEEDED" "$TARGET_VER" "Updated and verified both /healthz endpoints" || true
safe_remove_request
if [ ! -L "$ETC" ] && [ ! -L "$UPD_DIR" ] && [ ! -L "$STAGED" ] && [ -d "$STAGED" ]; then
  rm -f "$STAGED/ledger-cli.jar" "$STAGED/ledger-mcp-server.jar" "$STAGED/ledger-admin-web.jar" "$STAGED/SHA256SUMS" || true
fi
exit 0
