#!/bin/sh
set -e

# Java 完整路径，避免 PATH 环境变量问题
JAVA_BIN="${JAVA_HOME:-/opt/java/openjdk}/bin/java"

APP_USER=music
APP_GROUP=music

if [ -n "$PUID" ] || [ -n "$PGID" ]; then
  if [ -z "$PUID" ] || [ -z "$PGID" ]; then
    echo "PUID and PGID must be set together" >&2
    exit 1
  fi

  GROUP_NAME="$(getent group "$PGID" | cut -d: -f1 || true)"
  if [ -z "$GROUP_NAME" ]; then
    GROUP_NAME="$APP_GROUP"
    groupadd -g "$PGID" "$GROUP_NAME" >/dev/null 2>&1 || addgroup --gid "$PGID" "$GROUP_NAME"
  fi

  EXISTING_USER_BY_UID="$(getent passwd "$PUID" | cut -d: -f1 || true)"
  if [ -n "$EXISTING_USER_BY_UID" ] && [ "$EXISTING_USER_BY_UID" != "$APP_USER" ]; then
    APP_USER="$EXISTING_USER_BY_UID"
  elif id -u "$APP_USER" >/dev/null 2>&1; then
    CURRENT_UID="$(id -u "$APP_USER")"
    if [ "$CURRENT_UID" != "$PUID" ]; then
      usermod -u "$PUID" -g "$GROUP_NAME" "$APP_USER" >/dev/null 2>&1 || true
    fi
  else
    useradd -u "$PUID" -g "$GROUP_NAME" -M -s /bin/sh "$APP_USER" >/dev/null 2>&1 \
      || adduser --uid "$PUID" --ingroup "$GROUP_NAME" --disabled-password --gecos "" "$APP_USER"
  fi

  for target in /app /music; do
    if [ -e "$target" ]; then
      chown -R "$PUID:$PGID" "$target" 2>/dev/null || true
    fi
  done

  if [ -n "$UMASK" ]; then
    umask "$UMASK"
  fi

  exec su -m -s /bin/sh -c "exec $JAVA_BIN $JAVA_OPTS -jar /app/app.jar" "$APP_USER"
fi

if [ -n "$UMASK" ]; then
  umask "$UMASK"
fi

exec $JAVA_BIN $JAVA_OPTS -jar /app/app.jar
