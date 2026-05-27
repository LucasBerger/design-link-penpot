#!/usr/bin/env bash
# Run webhook URI probe inside penpot-devenv (same HTTP stack as Penpot webhooks).
set -euo pipefail
CONTAINER="${PENPOT_DEVENV_CONTAINER:-penpot-devenv-main}"
exec docker exec "$CONTAINER" bash -lc "
  cd /home/penpot/penpot/backend
  [ -f scripts/_env.local ] && source scripts/_env.local
  clojure -M:probe-webhook \"\$@\"
" bash "$@"
