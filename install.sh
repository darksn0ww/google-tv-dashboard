#!/usr/bin/env bash
# Install the dashboard on a Google TV device over adb and switch it on. Needs only adb.
#
#   ./install.sh <tv-ip:port> <app.apk> [config.json]
#
# Use "-" instead of the APK to only re-apply settings and the Home override.
set -e
S=$1 APK=$2 CONFIG=$3
if [ -z "$S" ] || [ -z "$APK" ]; then
  echo "usage: ./install.sh <tv-ip:port> <app.apk|-> [config.json]" >&2
  exit 1
fi

PKG=com.home.dashboard
SVC=$PKG/$PKG.HomeOverrideService
LISTENER=$PKG/$PKG.MediaListener

tv() { adb -s "$S" shell "$@" </dev/null; }
start_app() { tv am start -n $PKG/.MainActivity >/dev/null; }
bound() { tv dumpsys accessibility | grep -q 'label=Home button override'; }

if [ "$APK" != - ]; then
  adb -s "$S" install -r "$APK"
  tv cmd package compile -m speed -f $PKG
  start_app # a freshly updated app's services won't bind until it has run once
  sleep 3
fi

if [ -n "$CONFIG" ]; then
  DEST=/sdcard/Android/data/$PKG/files
  tv mkdir -p $DEST
  adb -s "$S" push "$CONFIG" $DEST/config.json >/dev/null
  tv am force-stop $PKG # the app moves the file into private storage when it starts
  sleep 1
  start_app
  sleep 4
  if tv "test -e $DEST/config.json"; then echo "config NOT imported"; else echo "config imported"; fi
fi

# Turn the Home override on, keeping any other accessibility services you use.
others=$(tv settings get secure enabled_accessibility_services | tr -d '\r' | tr ':' '\n' |
  grep -v -e "^$SVC\$" -e '^null$' | grep . | paste -s -d: - || true)
for attempt in 1 2 3 4 5; do
  sleep 4
  if [ -n "$others" ]; then
    tv settings put secure enabled_accessibility_services "$others"
  else
    tv settings delete secure enabled_accessibility_services >/dev/null
  fi
  tv settings put secure accessibility_enabled 0
  sleep 2
  tv settings put secure enabled_accessibility_services "${others:+$others:}$SVC"
  tv settings put secure accessibility_enabled 1
  sleep 3
  if bound; then break; fi
done
start_app
sleep 2

# Restart the "Continue watching" listener, only if you've already allowed it.
if tv settings get secure enabled_notification_listeners | grep -q $LISTENER; then
  tv cmd notification disallow_listener $LISTENER
  sleep 1
  tv cmd notification allow_listener $LISTENER
fi

if bound; then echo "home override: bound"; else echo "home override: NOT bound"; fi
