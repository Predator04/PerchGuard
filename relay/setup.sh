#!/usr/bin/env bash
# PerchGuard relay installer.
# Run as the user who will own the relay — NOT as root. The script never writes
# outside this user's home. It's idempotent: safe to re-run.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PY="${PY:-python3}"

banner() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn()   { printf '\033[1;33m!!  %s\033[0m\n' "$*" >&2; }
die()    { printf '\033[1;31m!!  %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 1. python + aiohttp ----------

banner "Checking Python"
if ! command -v "$PY" >/dev/null 2>&1; then
    die "python3 not found. Install it first (e.g. apt install python3 python3-venv)."
fi
pyver="$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
echo "  found $(command -v "$PY") ($pyver)"

VENV="$HERE/.venv"
PYBIN="$VENV/bin/python"
if [ ! -x "$PYBIN" ]; then
    banner "Creating virtualenv at $VENV"
    if ! "$PY" -m venv "$VENV" 2>/dev/null; then
        warn "venv unavailable — falling back to --user install."
        PYBIN="$PY"
        USER_FLAG="--user"
    fi
fi

banner "Installing aiohttp"
if [ -x "$PYBIN" ] && [ "$PYBIN" != "$PY" ]; then
    "$PYBIN" -m pip install --quiet --upgrade pip
    "$PYBIN" -m pip install --quiet aiohttp
else
    "$PY" -m pip install --quiet ${USER_FLAG:---user} aiohttp
fi

# ---------- 2. token + env file ----------

ENV_FILE="$HERE/.env"
if [ -f "$ENV_FILE" ] && grep -q '^KIDWATCH_TOKEN=' "$ENV_FILE"; then
    banner "Reusing existing token from $ENV_FILE"
    # shellcheck disable=SC1090
    . "$ENV_FILE"
else
    banner "Generating a fresh access token"
    TOKEN="$("$PYBIN" -c 'import secrets; print(secrets.token_urlsafe(32))')"
    cat >"$ENV_FILE" <<EOF
# PerchGuard relay environment. Keep this file private.
KIDWATCH_TOKEN=$TOKEN
KIDWATCH_HOST=0.0.0.0
KIDWATCH_PORT=8899
EOF
    chmod 600 "$ENV_FILE"
    export KIDWATCH_TOKEN="$TOKEN"
    export KIDWATCH_HOST=0.0.0.0
    export KIDWATCH_PORT=8899
fi

# ---------- 3. launcher ----------

LAUNCHER="$HERE/run.sh"
cat >"$LAUNCHER" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1090
[ -f "$HERE/.env" ] && . "$HERE/.env"
PYBIN="$HERE/.venv/bin/python"
[ -x "$PYBIN" ] || PYBIN="$(command -v python3)"
exec "$PYBIN" "$HERE/relay.py"
EOF
chmod +x "$LAUNCHER"

# ---------- 4. systemd user unit, if available ----------

SYSTEMD_OK=0
if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
    UNIT_DIR="$HOME/.config/systemd/user"
    mkdir -p "$UNIT_DIR"
    cat >"$UNIT_DIR/perchguard-relay.service" <<EOF
[Unit]
Description=PerchGuard relay
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$HERE
EnvironmentFile=$HERE/.env
ExecStart=$LAUNCHER
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable --now perchguard-relay.service >/dev/null 2>&1 || true
    if systemctl --user is-active --quiet perchguard-relay.service; then
        SYSTEMD_OK=1
        banner "Relay running under systemd (user service)"
    fi
fi

if [ "$SYSTEMD_OK" -eq 0 ]; then
    # Fallback: nohup. If an older instance is running, stop it first.
    if pgrep -f "python.*relay.py" >/dev/null 2>&1; then
        pkill -f "python.*relay.py" || true
        sleep 1
    fi
    banner "Starting relay with nohup (no systemd user session)"
    nohup "$LAUNCHER" >"$HERE/relay.log" 2>&1 &
    echo "  pid=$! logs=$HERE/relay.log"
    sleep 1
fi

# ---------- 5. figure out the LAN IP and print instructions ----------

# shellcheck disable=SC1090
. "$ENV_FILE"

LAN_IP="$(
    { hostname -I 2>/dev/null | awk '{print $1}'; } \
    || { ip -4 route get 1.1.1.1 2>/dev/null | awk '/src/ {for (i=1;i<=NF;i++) if ($i=="src") print $(i+1)}'; } \
    || echo ""
)"
LAN_IP="${LAN_IP:-your-server-ip}"
PORT="${KIDWATCH_PORT:-8899}"

GREEN=$'\033[1;32m'; DIM=$'\033[0m'
printf "\n%s================= PerchGuard relay is up =================%s\n\n" "$GREEN" "$DIM"
cat <<EOF
  Child phone: paste this into the app (step 4 of the setup wizard):

      ws://$LAN_IP:$PORT/kid/stream

  Parent dashboard (open on your laptop or phone):

      http://$LAN_IP:$PORT/?token=$KIDWATCH_TOKEN

  Access token (keep this private):

      $KIDWATCH_TOKEN

  Logs:        $HERE/relay.log
  Env file:    $HERE/.env
  Stop:        systemctl --user stop perchguard-relay   (or kill the python)
  Start again: systemctl --user start perchguard-relay  (or ./run.sh)

  Note: the address above only works while the child phone is on the same
  Wi-Fi. For off-network access see issue #1 (Cloudflare Tunnel).
EOF
printf "\n%s===========================================================%s\n\n" "$GREEN" "$DIM"
