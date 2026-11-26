#!/usr/bin/env bash
# Rebuilds arcane-compose.yaml from watchdog.sh, so the two can't drift.
#   bash build-arcane.sh          write it
#   bash build-arcane.sh --check  fail if it is out of date
set -eu
cd "$(dirname "$0")"
build() {
  sed -n '1,/^    content: |$/p' arcane-compose.yaml
  sed -e 's/\$/$$/g' -e 's/^/      /' -e 's/^ *$//' watchdog.sh # compose reads $$ as a literal $
}
if [ "${1:-}" = --check ]; then
  build | diff -u arcane-compose.yaml - && echo "arcane-compose.yaml is up to date"
else
  new=$(build)
  printf '%s\n' "$new" >arcane-compose.yaml
fi
