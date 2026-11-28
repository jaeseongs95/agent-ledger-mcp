#!/bin/sh
# deploy/install-hook.sh: one-stop Veridger client hook installer for Codex, Claude Code, and Gemini/Antigravity on
# Linux/macOS. Downloads ledger-client-hook.jar and ledger-hook-resident.jar from GitHub Releases (or uses --jars=<dir>),
# verifies every artifact explicitly against SHA256SUMS, installs into a versioned directory (preserving prior versions
# as rollback backup), stops any running resident hook, safely merges PreToolUse entries without clobbering unrelated
# hooks/settings, and runs a live smoke test on mcp__veridger__ledger_ledger_whoami.
#
# Usage:
#   sh install-hook.sh [--version=latest|v5.0.0] [--jars=<local-dir>] [--hosts=codex,claude,antigravity]
#                      [--java=<path>] [--repo=jaeseongs95/veridger]
set -eu
umask 077

VERSION=latest
REPO=jaeseongs95/veridger
JARS=
HOSTS=codex,claude,antigravity
JAVA=
TMP_DIR=

die() { echo "install-hook.sh: $*" >&2; exit 1; }
cleanup() { [ -z "$TMP_DIR" ] || rm -rf "$TMP_DIR"; }
trap cleanup EXIT INT TERM

for arg in "$@"; do
  case $arg in
    --version=*) VERSION=${arg#*=} ;;
    --repo=*) REPO=${arg#*=} ;;
    --jars=*) JARS=${arg#*=} ;;
    --hosts=*) HOSTS=${arg#*=} ;;
    --java=*) JAVA=${arg#*=} ;;
    -h|--help)
      echo "Usage: sh install-hook.sh [--version=latest|v5.0.0] [--jars=<dir>] [--hosts=codex,claude,antigravity] [--java=<path>]"
      exit 0 ;;
    *) die "unknown option: $arg" ;;
  esac
done

[ -n "$JAVA" ] || JAVA=$(command -v java) || die "Java 25 is required (give --java=<path>)"
command -v python3 >/dev/null 2>&1 || die "python3 is required to merge JSON hook configs safely"

if [ "$VERSION" = "latest" ]; then
  INSTALL_TAG="latest"
elif printf '%s\n' "$VERSION" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
  INSTALL_TAG="${VERSION#v}"
else
  die "invalid --version tag (expected latest or vX.Y.Z)"
fi

verify_sha256_entry() {
  sums_file=$1
  file_path=$2
  file_name=$3
  expected=$(awk -v target="$file_name" '
    { sub(/\r$/, "") }
    $0 ~ /^[0-9a-fA-F]{64}[[:space:]]+\*?[^[:space:]\/\\]+$/ {
      fname = $2; sub(/^\*/, "", fname);
      if (fname == target) { count++; val = tolower($1) }
    }
    END { if (count == 1) print val }
  ' "$sums_file")
  [ -n "$expected" ] || die "SHA256SUMS is missing a unique entry for $file_name"
  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$file_path" | awk '{print tolower($1)}')
  elif command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$file_path" | awk '{print tolower($1)}')
  else
    die "sha256sum or shasum is required to verify SHA256SUMS"
  fi
  [ "$actual" = "$expected" ] || die "SHA-256 verification failed for $file_name"
}

if [ -z "$JARS" ]; then
  command -v curl >/dev/null 2>&1 || die "curl is required when --jars is omitted"
  printf '%s\n' "$REPO" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' || die "invalid --repo value"
  if [ "$VERSION" = "latest" ]; then
    BASE_URL="https://github.com/$REPO/releases/latest/download"
  else
    BASE_URL="https://github.com/$REPO/releases/download/$VERSION"
  fi
  TMP_DIR=$(mktemp -d)
  echo "install-hook.sh: downloading Veridger hook JARs from $BASE_URL ..."
  curl -fsSL --proto '=https' --proto-redir '=https' --tlsv1.2 "$BASE_URL/SHA256SUMS" -o "$TMP_DIR/SHA256SUMS" || die "failed to download SHA256SUMS"
  for f in ledger-client-hook.jar ledger-hook-resident.jar; do
    curl -fsSL --proto '=https' --proto-redir '=https' --tlsv1.2 "$BASE_URL/$f" -o "$TMP_DIR/$f" || die "failed to download $f"
    verify_sha256_entry "$TMP_DIR/SHA256SUMS" "$TMP_DIR/$f" "$f"
  done
  JARS=$TMP_DIR
else
  [ "$INSTALL_TAG" != "latest" ] || INSTALL_TAG="local"
fi

[ -f "$JARS/ledger-client-hook.jar" ] || die "ledger-client-hook.jar not found in $JARS"
[ -f "$JARS/ledger-hook-resident.jar" ] || die "ledger-hook-resident.jar not found in $JARS"

DEST_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/veridger-hook"
DEST="$DEST_ROOT/$INSTALL_TAG"
mkdir -p "$DEST"
cp -f "$JARS/ledger-client-hook.jar" "$DEST/ledger-client-hook.jar"
cp -f "$JARS/ledger-hook-resident.jar" "$DEST/ledger-hook-resident.jar"
chmod 0644 "$DEST/ledger-client-hook.jar" "$DEST/ledger-hook-resident.jar"

# Stop any running resident process so the new build starts cleanly.
"$JAVA" -jar "$DEST/ledger-client-hook.jar" stop >/dev/null 2>&1 || true

python3 - "$JAVA" "$DEST/ledger-client-hook.jar" "$HOSTS" <<'PY'
import json, pathlib, shutil, sys

java_bin, jar_path, hosts_csv = sys.argv[1], sys.argv[2], sys.argv[3]
hosts = [h.strip().lower() for h in hosts_csv.split(",") if h.strip()]
home = pathlib.Path.home()
jvm = "-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable"
flat_matcher = "^mcp__[A-Za-z0-9_-]+__(?:[A-Za-z0-9_-]+_)?(?:ledger_(issue_save|spec_save|task_save|criterion_save|transition|record_deliverable|record_case|link|correct|batch|review|review_correct|whoami)|artifact_(begin|chunk|finish|status|read))$"
agy_matcher = "^(call_mcp_tool|mcp:[A-Za-z0-9._-]+[:/_](?:[A-Za-z0-9._-]+[:/_])?(?:ledger_(issue_save|spec_save|task_save|criterion_save|transition|record_deliverable|record_case|link|correct|batch|review|review_correct|whoami)|artifact_(begin|chunk|finish|status|read)))$"

def backup(path: pathlib.Path):
    if path.exists():
        shutil.copy2(path, path.with_suffix(path.suffix + ".bak"))

def is_veridger_entry(entry) -> bool:
    if not isinstance(entry, dict):
        return False
    for h in (entry.get("hooks") or []):
        if isinstance(h, dict) and "ledger-client-hook.jar" in str(h.get("command", "")):
            return True
    return False

def load_json_object(path: pathlib.Path) -> dict:
    if not path.exists():
        return {}
    loaded = json.loads(path.read_text(encoding="utf-8-sig"))
    return loaded if isinstance(loaded, dict) else {}

def merge_pre_tool_use(path: pathlib.Path, client: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = load_json_object(path)
    backup(path)
    hooks = data.get("hooks")
    if not isinstance(hooks, dict):
        hooks = {}
        data["hooks"] = hooks
    raw_entries = hooks.get("PreToolUse")
    if not isinstance(raw_entries, list):
        raw_entries = []
    entries = [e for e in raw_entries if not is_veridger_entry(e)]
    entries.append({
        "matcher": flat_matcher,
        "hooks": [{"type": "command", "command": f'"{java_bin}" {jvm} -jar "{jar_path}" {client}', "timeout": 10}]
    })
    hooks["PreToolUse"] = entries
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Updated {client} hook config: {path}")

if "codex" in hosts:
    merge_pre_tool_use(home / ".codex" / "hooks.json", "codex")
if "claude" in hosts:
    merge_pre_tool_use(home / ".claude" / "settings.json", "claude")
if "antigravity" in hosts or "gemini" in hosts:
    p = home / ".gemini" / "config" / "hooks.json"
    p.parent.mkdir(parents=True, exist_ok=True)
    data = load_json_object(p)
    backup(p)
    data.pop("pg-ledger-host-attestation", None)
    data.pop("agent-ledger-host-attestation", None)
    data["veridger-host-attestation"] = {
        "enabled": True,
        "PreToolUse": [{
            "matcher": agy_matcher,
            "hooks": [{"type": "command", "command": f'"{java_bin}" {jvm} -jar "{jar_path}" antigravity', "timeout": 10}]
        }]
    }
    p.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Updated antigravity hook config: {p}")
PY

SMOKE=$(printf '{"hook_event_name":"PreToolUse","session_id":"install-smoke","tool_name":"mcp__veridger__ledger_ledger_whoami","tool_input":{}}' \
  | "$JAVA" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar "$DEST/ledger-client-hook.jar" codex)
case $SMOKE in
  *'"_hostAttestation":"codex:install-smoke"'*)
    echo "install-hook.sh: verified ($DEST/ledger-client-hook.jar)."
    echo "install-hook.sh: if you use Codex, run /hooks in Codex to review and trust the updated hook." ;;
  *)
    die "smoke test did not stamp _hostAttestation: $SMOKE" ;;
esac
