#!/bin/sh
# deploy/veridger-updater.sh: least-privilege Linux host updater invoked by systemd <name>-updater.path when
# ledger-admin-web stages release JARs and writes /etc/<name>/update/request.properties.
# Security model: never trusts SHA256SUMS from the service-user-writable update directory, never runs chown on
# service-writable paths, uses FD-pinned O_NOFOLLOW directory operations (eliminating symlink/FIFO/TOCTOU races),
# enforces a monotonic version floor against root-owned /opt/<name>/.installed-version, copies staged JARs into a
# root-owned 0700 temp directory before fetching authoritative SHA256SUMS over HTTPS-only redirects from the pinned
# repository and verifying every digest, installs 0644 root:root JARs atomically, checks both /healthz endpoints,
# and rolls back automatically on failure.
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
SVC=$NAME
ADMIN=$NAME-admin

if [ ! -L "$ETC" ] && [ ! -L "$UPD_DIR" ] && [ ! -L "$UPD_DIR/request.properties" ]; then
  :
else
  echo "veridger-updater.sh: refused symbolic link in update path" >&2
  exit 1
fi
[ -f "$UPD_DIR/request.properties" ] || exit 0

WORK=$(mktemp -d)
chmod 0700 "$WORK"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

# FD-pinned operations inside service-writable /etc/<name>/update (O_DIRECTORY|O_NOFOLLOW, O_NONBLOCK, S_ISREG).
fd_op() {
  python3 - "$ETC" "$@" <<'PY'
import errno, os, secrets, stat, sys, zipfile

etc_path = sys.argv[1]
op = sys.argv[2]

def open_dir(parent_fd, name):
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    return os.open(name, flags, dir_fd=parent_fd)

def open_update_dir():
    etc_fd = os.open(etc_path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        return open_dir(etc_fd, "update")
    finally:
        os.close(etc_fd)

def copy_regular(dir_fd, name, dest_path, max_bytes):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=dir_fd)
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise OSError(errno.EINVAL, f"non-regular file: {name}")
        if st.st_size > max_bytes:
            raise OSError(errno.EFBIG, f"file exceeds size cap: {name}")
        total = 0
        with os.fdopen(fd, "rb") as src, open(dest_path, "wb") as dst:
            fd = -1
            while True:
                chunk = src.read(65536)
                if not chunk:
                    break
                total += len(chunk)
                if total > max_bytes:
                    raise OSError(errno.EFBIG, f"file exceeded size cap while reading: {name}")
                dst.write(chunk)
        os.chmod(dest_path, 0o600)
    finally:
        if fd >= 0:
            os.close(fd)

if op == "copy-request":
    dest = sys.argv[3]
    upd_fd = open_update_dir()
    try:
        copy_regular(upd_fd, "request.properties", dest, 65536)
    finally:
        os.close(upd_fd)
elif op == "copy-staged":
    jar_name, dest = sys.argv[3], sys.argv[4]
    upd_fd = open_update_dir()
    try:
        staged_fd = open_dir(upd_fd, "staged")
        try:
            copy_regular(staged_fd, jar_name, dest, 256 * 1024 * 1024)
        finally:
            os.close(staged_fd)
    finally:
        os.close(upd_fd)
elif op == "write-status":
    payload = sys.argv[3].encode("utf-8")
    upd_fd = open_update_dir()
    try:
        tmp_name = f".status.{os.getpid()}.{secrets.token_hex(6)}.tmp"
        fd = os.open(tmp_name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644, dir_fd=upd_fd)
        try:
            os.fchmod(fd, 0o644)
            with os.fdopen(fd, "wb") as out:
                fd = -1
                out.write(payload)
                out.flush()
                os.fsync(out.fileno())
            os.replace(tmp_name, "status.properties", src_dir_fd=upd_fd, dst_dir_fd=upd_fd)
        except Exception:
            if fd >= 0:
                os.close(fd)
            try:
                os.unlink(tmp_name, dir_fd=upd_fd)
            except OSError:
                pass
            raise
    finally:
        os.close(upd_fd)
elif op == "unlink-request":
    upd_fd = open_update_dir()
    try:
        try:
            os.unlink("request.properties", dir_fd=upd_fd)
        except FileNotFoundError:
            pass
    finally:
        os.close(upd_fd)
elif op == "clean-staged":
    upd_fd = open_update_dir()
    try:
        try:
            staged_fd = open_dir(upd_fd, "staged")
        except FileNotFoundError:
            sys.exit(0)
        try:
            for name in sys.argv[3:]:
                try:
                    os.unlink(name, dir_fd=staged_fd)
                except FileNotFoundError:
                    pass
        finally:
            os.close(staged_fd)
    finally:
        os.close(upd_fd)
elif op == "installed-version":
    lib_dir = sys.argv[3]
    vf = os.path.join(lib_dir, ".installed-version")
    if os.path.isfile(vf) and not os.path.islink(vf):
        raw = open(vf, "r", encoding="utf-8").read().strip()
        if raw:
            print(raw)
            sys.exit(0)
    for jar in ("ledger-admin-web.jar", "ledger-cli.jar"):
        jp = os.path.join(lib_dir, jar)
        if os.path.isfile(jp) and not os.path.islink(jp):
            try:
                with zipfile.ZipFile(jp, "r") as zf:
                    mf = zf.read("META-INF/MANIFEST.MF").decode("utf-8", errors="ignore")
                for line in mf.splitlines():
                    if line.startswith("Implementation-Version:"):
                        print(line.split(":", 1)[1].strip())
                        sys.exit(0)
            except Exception:
                pass
elif op == "check-newer":
    installed, target = sys.argv[3], sys.argv[4]
    def parse_ver(v):
        parts = v.lstrip("v").split("-")[0].split(".")
        return tuple(int(p) for p in parts[:3])
    if parse_ver(target) <= parse_ver(installed):
        sys.exit(1)
else:
    sys.exit(2)
PY
}

safe_remove_request() {
  fd_op unlink-request >/dev/null 2>&1 || true
}

write_status() {
  st=$1
  ver=$2
  raw_msg=$3
  msg=$(printf '%s' "$raw_msg" | tr '\r\n' '  ' | sed 's/\\/\\\\/g')
  now=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  payload=$(printf 'state=%s\ntargetVersion=%s\nupdatedAt=%s\ndetail=%s\n' "$st" "$ver" "$now" "$msg")
  fd_op write-status "$payload"
}

fail_and_cleanup() {
  ver=$1
  msg=$2
  write_status "FAILED" "$ver" "$msg" || true
  safe_remove_request
  exit 1
}

command -v python3 >/dev/null 2>&1 || { echo "veridger-updater.sh: python3 is required for FD-pinned safe file operations" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || fail_and_cleanup "" "curl is required on the host for HTTPS SHA256SUMS and /healthz verification"
command -v sha256sum >/dev/null 2>&1 || fail_and_cleanup "" "sha256sum is required on the host"

REQ_COPY="$WORK/request.properties"
fd_op copy-request "$REQ_COPY" || fail_and_cleanup "" "refused unsafe or missing request.properties"

TARGET_VER=$(awk -F= '$1=="targetVersion"{print $2; exit}' "$REQ_COPY" | tr -d '\r')
if ! printf '%s\n' "$TARGET_VER" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  fail_and_cleanup "" "invalid targetVersion in request.properties"
fi

# Enforce monotonic version floor against root-owned installed version (blocks downgrade/rollback attacks).
INSTALLED_VER=$(fd_op installed-version "$LIB" 2>/dev/null || true)
if [ -n "$INSTALLED_VER" ] && printf '%s\n' "$INSTALLED_VER" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  fd_op check-newer "$INSTALLED_VER" "$TARGET_VER" \
    || fail_and_cleanup "$TARGET_VER" "targetVersion $TARGET_VER must be newer than installed version $INSTALLED_VER"
fi

# Fetch authoritative SHA256SUMS directly over HTTPS-only redirects from the root-pinned GitHub repository.
SUMS_URL="https://github.com/$REPO/releases/download/v$TARGET_VER/SHA256SUMS"
curl -fsSL --proto '=https' --proto-redir '=https' --tlsv1.2 --max-time 20 "$SUMS_URL" -o "$WORK/SHA256SUMS" \
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

# Copy each staged JAR into the root-owned 0700 work directory via FD-pinned O_NOFOLLOW open, then verify SHA-256.
for jar in ledger-cli.jar ledger-mcp-server.jar ledger-admin-web.jar; do
  fd_op copy-staged "$jar" "$WORK/$jar" || fail_and_cleanup "$TARGET_VER" "missing, oversized, or unsafe staged jar: $jar"
  manifest_hash=$(lookup_manifest_hash "$jar")
  [ -n "$manifest_hash" ] || fail_and_cleanup "$TARGET_VER" "SHA256SUMS missing entry for $jar"
  req_hash=$(awk -F= -v k="sha256.$jar" '$1==k{print tolower($2); exit}' "$REQ_COPY" | tr -d '\r')
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

printf '%s\n' "$TARGET_VER" > "$WORK/.installed-version"
install -m 0644 -o root -g root "$WORK/.installed-version" "$LIB/.installed-version.new"
mv -f "$LIB/.installed-version.new" "$LIB/.installed-version"

write_status "SUCCEEDED" "$TARGET_VER" "Updated and verified both /healthz endpoints" || true
safe_remove_request
fd_op clean-staged ledger-cli.jar ledger-mcp-server.jar ledger-admin-web.jar SHA256SUMS >/dev/null 2>&1 || true
exit 0
