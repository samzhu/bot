#!/bin/sh
set -e

if [ "$#" -ne 0 ]; then
    printf '%s\n' 'dev.sh accepts no arguments; use SPRING_AI_MODEL_CHAT to select a model.' >&2
    exit 2
fi

CDPATH= cd "$(dirname "$0")"
exec node ./scripts/dev.mjs
