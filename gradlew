#!/bin/sh
GRADLE_HOME="$HOME/.gradle/wrapper/dists/gradle-8.6-bin"
if [ -d "$GRADLE_HOME" ]; then
  GRADLE_CMD=$(find "$GRADLE_HOME" -name "gradle" -type f | head -1)
fi
if [ -z "$GRADLE_CMD" ]; then
  GRADLE_CMD=$(which gradle)
fi
exec "$GRADLE_CMD" "$@"
