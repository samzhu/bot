#!/bin/sh
set -e

if [ "$#" -ne 0 ]; then
    printf '%s\n' 'dev.sh accepts no arguments; use SPRING_AI_MODEL_CHAT to select a model.' >&2
    exit 2
fi

CDPATH= cd "$(dirname "$0")/backend"
export SPRING_AI_MODEL_CHAT="${SPRING_AI_MODEL_CHAT-google-genai}"
exec ./gradlew bootRun '--args=--spring.profiles.active=dev'
