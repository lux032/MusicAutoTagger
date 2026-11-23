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

  # 仅调整应用自身数据目录属主，绝不对 /music 或输出媒体库执行全量 chown -R
  for target in /app/data /app/logs /app/.cover_cache /app/partial_files /app/failed_files /app/config.properties; do
    if [ -e "$target" ]; then
      chown -R "$PUID:$PGID" "$target" 2>/dev/null || true
    fi
  done

  if [ -n "$UMASK" ]; then
    umask "$UMASK"
  fi

  if command -v gosu >/dev/null 2>&1; then
    # shellcheck disable=SC2086
    exec gosu "$PUID:$PGID" "$JAVA_BIN" $JAVA_OPTS -jar /app/app.jar
  else
    # shellcheck disable=SC2086
    exec setpriv --reuid="$PUID" --regid="$PGID" --init-groups -- "$JAVA_BIN" $JAVA_OPTS -jar /app/app.jar
  fi
fi

if [ -n "$UMASK" ]; then
  umask "$UMASK"
fi

# shellcheck disable=SC2086
exec "$JAVA_BIN" $JAVA_OPTS -jar /app/app.jar
