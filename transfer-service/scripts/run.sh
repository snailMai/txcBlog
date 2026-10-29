#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_DIR"
exec "${TRANSFER_PYTHON:-$PROJECT_DIR/.venv/bin/python}" -m transfer run "$@"
