#!/bin/sh
set -eu

VENV_DIR="${VENV_DIR:-/opt/venv}"
REQ_FILE="requirements.txt"
REQ_HASH_FILE="$VENV_DIR/.requirements.sha256"

hash_file() {
  target="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$target" | awk '{print $1}'
  else
    shasum -a 256 "$target" | awk '{print $1}'
  fi
}

mkdir -p "$VENV_DIR"
REQ_HASH="$(hash_file "$REQ_FILE")"
CURRENT_HASH=""
if [ -f "$REQ_HASH_FILE" ]; then
  CURRENT_HASH="$(cat "$REQ_HASH_FILE" || true)"
fi

if [ ! -x "$VENV_DIR/bin/python" ] || [ "$REQ_HASH" != "$CURRENT_HASH" ]; then
  python -m venv "$VENV_DIR"
  "$VENV_DIR/bin/pip" install --upgrade pip
  "$VENV_DIR/bin/pip" install -r "$REQ_FILE"
  echo "$REQ_HASH" > "$REQ_HASH_FILE"
fi

exec "$VENV_DIR/bin/uvicorn" main:app --host 0.0.0.0 --port 8000 --reload

