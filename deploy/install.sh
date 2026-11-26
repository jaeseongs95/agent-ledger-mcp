#!/bin/sh
# deploy/install.sh: the installer of veridger for a Linux host (a Linux server). It stays thin on purpose: it runs
# only what Java cannot do (download verified release JARs when --jars is omitted, create the OS user, switch to it,
# install the least-privilege systemd updater unit, and enable/restart the systemd units) and delegates validation,
# directory setup, file modes, secrets (common, admin, mcp, tool), tokens, driver registration, and schema setup to
# `java -jar ledger-cli.jar`.
# Documentation it follows: Spring Boot, "Installing Spring Boot Applications" (a systemd unit in /etc/systemd/system,
# `systemctl enable`): https://docs.spring.io/spring-boot/how-to/deployment/installing.html ; systemd.exec(5) and
# systemd.path(5) (sandboxing and least-privilege file-triggered self-update).
#
# Non-interactive usage (fully backward-compatible):
#   sudo deploy/install.sh --jars=<dir> --driver-jar=<file> --driver-class=<class> --owner-account=<account>
#     --app-account=<account> --jdbc-url=<url> [--java=<path>] [--name=<instance>] [--stdin] [render-deploy options]
#
# One-stop convenience usage (auto-downloads & SHA-256 verifies release JARs when --jars is omitted, and prompts for
# missing required values when running in an interactive terminal without --stdin):
#   sudo sh install.sh [--version=latest|v4.2.0] [--repo=jaeseongs95/veridger]
#
# Passwords are never accepted on a command line: Java reads them hidden from the terminal or via --stdin.
# A re-run is an upgrade: jars, driver jar, and unit files are replaced while secrets, tokens, and data are preserved.
# An installation of an earlier major version is moved with the separate tool ledger-migrate-1x (see the README).
set -eu
umask 022
JVM_OPTS=-Duser.timezone=UTC

NAME=
JAVA=
JARS=
VERSION=latest
REPO=jaeseongs95/veridger
DRIVER_JAR=
DRIVER_CLASS=
OWNER=
APP_ACCOUNT=
JDBC_URL=
STDIN=
MCP_PORT_VAL=
ADMIN_PORT_VAL=
MCP_URL=
TMP_JARS=
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

die() { echo "install.sh: $*" >&2; exit 1; }
cleanup() { [ -z "$TMP_JARS" ] || rm -rf "$TMP_JARS"; }
trap cleanup EXIT INT TERM
# runuser: a Java process cannot become another user, so commands that touch secrets run as the service user.
as_svc() { runuser -u "$SVC" -- "$JAVA" $JVM_OPTS -jar "$LIB/ledger-cli.jar" --ledger.secrets.instance="$NAME" "$@"; }

prompt_if_empty() {
  var_name=$1
  label=$2
  default_val=$3
  eval "cur_val=\$$var_name"
  [ -n "$cur_val" ] && return 0
  if [ -n "$STDIN" ] || [ ! -t 0 ]; then
    return 0
  fi
  if [ -n "$default_val" ]; then
    printf "%s [%s]: " "$label" "$default_val"
  else
    printf "%s: " "$label"
  fi
  IFS= read -r answer
  [ -n "$answer" ] || answer=$default_val
  eval "$var_name=\$answer"
}

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
  actual=$(sha256sum "$file_path" | awk '{print tolower($1)}')
  [ "$actual" = "$expected" ] || die "SHA-256 verification failed for $file_name"
}

download_release_jars() {
  command -v curl >/dev/null 2>&1 || die "curl is required when --jars is omitted"
  command -v sha256sum >/dev/null 2>&1 || die "sha256sum is required to verify downloaded release JARs"
  printf '%s\n' "$REPO" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' || die "invalid --repo value"
  if [ "$VERSION" = "latest" ]; then
    BASE_URL="https://github.com/$REPO/releases/latest/download"
  elif printf '%s\n' "$VERSION" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
    BASE_URL="https://github.com/$REPO/releases/download/$VERSION"
  else
    die "invalid --version tag (expected latest or vX.Y.Z)"
  fi
  TMP_JARS=$(mktemp -d)
  echo "install.sh: downloading Veridger release artifacts from $BASE_URL ..."
  curl -fsSL --proto '=https' --tlsv1.2 "$BASE_URL/SHA256SUMS" -o "$TMP_JARS/SHA256SUMS" || die "failed to download SHA256SUMS from $BASE_URL"
  for asset in ledger-cli.jar ledger-mcp-server.jar ledger-admin-web.jar; do
    curl -fsSL --proto '=https' --tlsv1.2 "$BASE_URL/$asset" -o "$TMP_JARS/$asset" || die "failed to download $asset from $BASE_URL"
    verify_sha256_entry "$TMP_JARS/SHA256SUMS" "$TMP_JARS/$asset" "$asset"
  done
  if curl -fsSL --proto '=https' --tlsv1.2 "$BASE_URL/veridger-updater.sh" -o "$TMP_JARS/veridger-updater.sh"; then
    verify_sha256_entry "$TMP_JARS/SHA256SUMS" "$TMP_JARS/veridger-updater.sh" "veridger-updater.sh"
  else
    rm -f "$TMP_JARS/veridger-updater.sh"
  fi
  JARS=$TMP_JARS
}

for arg in "$@"; do
  shift
  case $arg in
    --name=*) NAME=${arg#*=}; set -- "$@" "$arg" ;;
    --java=*) JAVA=${arg#*=} ;;
    --jars=*) JARS=${arg#*=} ;;
    --version=*) VERSION=${arg#*=} ;;
    --repo=*) REPO=${arg#*=} ;;
    --driver-jar=*) DRIVER_JAR=${arg#*=} ;;
    --driver-class=*) DRIVER_CLASS=${arg#*=} ;;
    --owner-account=*) OWNER=${arg#*=} ;;
    --app-account=*) APP_ACCOUNT=${arg#*=}; set -- "$@" "$arg" ;;
    --jdbc-url=*) JDBC_URL=${arg#*=}; set -- "$@" "$arg" ;;
    --mcp-port=*) MCP_PORT_VAL=${arg#*=}; MCP_URL=" --url=http://127.0.0.1:${arg#*=}/mcp"; set -- "$@" "$arg" ;;
    --admin-port=*) ADMIN_PORT_VAL=${arg#*=}; set -- "$@" "$arg" ;;
    --stdin) STDIN=--stdin ;;
    *) set -- "$@" "$arg" ;;
  esac
done

[ -n "$JAVA" ] || JAVA=$(command -v java) || die "java is not installed: give --java=<path> of a Java 25 runtime"
[ -n "$JARS" ] || download_release_jars

if [ -t 0 ] && [ -z "$STDIN" ]; then
  prompt_if_empty JDBC_URL "JDBC URL (e.g. jdbc:postgresql://127.0.0.1:5432/veridger)" ""
  prompt_if_empty DRIVER_JAR "Path to JDBC driver JAR" ""
  prompt_if_empty DRIVER_CLASS "JDBC driver class" "org.postgresql.Driver"
  prompt_if_empty OWNER "Database DDL owner account" "ledger_owner"
  prompt_if_empty APP_ACCOUNT "Database application account" "ledger_app"
  case " $* " in
    *" --jdbc-url="*) ;;
    *) [ -n "$JDBC_URL" ] && set -- "$@" "--jdbc-url=$JDBC_URL" ;;
  esac
  case " $* " in
    *" --app-account="*) ;;
    *) [ -n "$APP_ACCOUNT" ] && set -- "$@" "--app-account=$APP_ACCOUNT" ;;
  esac
fi

for v in "$JARS" "$DRIVER_JAR" "$DRIVER_CLASS" "$OWNER" "$APP_ACCOUNT"; do
  [ -n "$v" ] || die "an option is missing: see the header of this file"
done

if [ -z "$NAME" ]; then
  NAME=$("$JAVA" $JVM_OPTS -jar "$JARS/ledger-cli.jar" --ledger.secrets.home=/nonexistent install-default instance-name) || die "install-default instance-name failed"
  case $NAME in
    ''|[!a-z]*|*[!a-z0-9-]*|*-|????????????????????????*) die "the install default instance-name is not an instance name" ;;
  esac
fi
[ -n "$MCP_PORT_VAL" ] || MCP_PORT_VAL=$("$JAVA" $JVM_OPTS -jar "$JARS/ledger-cli.jar" --ledger.secrets.home=/nonexistent install-default mcp-port) || die "install-default mcp-port failed"
[ -n "$ADMIN_PORT_VAL" ] || ADMIN_PORT_VAL=$("$JAVA" $JVM_OPTS -jar "$JARS/ledger-cli.jar" --ledger.secrets.home=/nonexistent install-default admin-port) || die "install-default admin-port failed"

SVC=$NAME
ADMIN=$NAME-admin
UPDATER=$NAME-updater
LIB=/opt/$NAME
ETC=/etc/$NAME

"$JAVA" $JVM_OPTS -jar "$JARS/ledger-cli.jar" --ledger.secrets.home=/nonexistent render-deploy --check --java="$JAVA" "$@"

# useradd: Java has no API to create an operating system user. Status 9 is "name in use": the user of an earlier run stays.
useradd --system --user-group --no-create-home --shell /usr/sbin/nologin "$SVC" || [ $? = 9 ]
# runuser: the service user must be able to execute Java; check right after creating the user and before creating anything else.
runuser -u "$SVC" -- "$JAVA" -version >/dev/null 2>&1 || die "the service user $SVC cannot run $JAVA: use a Java 25 runtime outside the home directories that every account can read and execute"
"$JAVA" $JVM_OPTS -jar "$JARS/ledger-cli.jar" --ledger.secrets.home=/nonexistent prepare-host --jars="$JARS" --driver-jar="$DRIVER_JAR" --java="$JAVA" "$@"

as_svc secret-set ledger.jdbc.password --if-absent $STDIN
as_svc token-generate ledger.admin.token --print
as_svc token-generate ledger.mcp.token --print --copy-to=ledger.admin.agent-token
as_svc driver-register --jar="$ETC/drivers/${DRIVER_JAR##*/}" --class="$DRIVER_CLASS"
as_svc setup --owner-account="$OWNER" --app-account="$APP_ACCOUNT" $STDIN

# Install least-privilege host updater triggered by /etc/$NAME/update/request.properties (Admin Web Update button).
UPDATER_SRC=
if [ -f "$JARS/veridger-updater.sh" ]; then
  UPDATER_SRC="$JARS/veridger-updater.sh"
elif [ -f "$SCRIPT_DIR/veridger-updater.sh" ]; then
  UPDATER_SRC="$SCRIPT_DIR/veridger-updater.sh"
fi
if [ -n "$UPDATER_SRC" ]; then
  [ ! -L "$ETC" ] && [ ! -L "$ETC/update" ] || die "refused symbolic link at $ETC/update"
  install -m 0755 -o root -g root "$UPDATER_SRC" "$LIB/veridger-updater.sh"
  install -d -m 0700 -o "$SVC" -g "$SVC" "$ETC/update"
  cat > "/etc/systemd/system/$UPDATER.service" <<EOF
[Unit]
Description=$NAME host updater (SHA-256 verified with automatic rollback)
After=network-online.target

[Service]
Type=oneshot
ExecStart=$LIB/veridger-updater.sh --name=$NAME --mcp-port=$MCP_PORT_VAL --admin-port=$ADMIN_PORT_VAL --repo=$REPO
EOF
  cat > "/etc/systemd/system/$UPDATER.path" <<EOF
[Unit]
Description=Watch $ETC/update/request.properties for Admin Web updates

[Path]
PathExists=$ETC/update/request.properties
Unit=$UPDATER.service

[Install]
WantedBy=multi-user.target
EOF
fi

# systemctl: the service manager of the host, Java does not talk to it.
systemctl daemon-reload
systemctl enable "$SVC" "$ADMIN"
[ -f "/etc/systemd/system/$UPDATER.path" ] && systemctl enable --now "$UPDATER.path"
systemctl restart "$SVC" "$ADMIN"
echo "install.sh: done. Check the MCP server (it listens on 127.0.0.1) as the service user, which alone reads its token: runuser -u $SVC -- $JAVA $JVM_OPTS -jar $LIB/ledger-cli.jar --ledger.secrets.instance=$NAME mcp-check$MCP_URL"
