#!/bin/zsh
# Build, install and switch on the dashboard on a Google TV device over adb.
#
#   ./deploy.sh <tv-ip:port>                         build + install
#   ./deploy.sh <tv-ip:port> --config config.json    ...and push settings
#   NO_INSTALL=1 ./deploy.sh <tv-ip:port>            only re-enable the Home override
#
# TV_ADDRESS can be used instead of the first argument.
set -e
cd "${0:h}"

S=${TV_ADDRESS:-}
CONFIG=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --config) CONFIG=${2:A}; shift 2 ;;
    *) S=$1; shift ;;
  esac
done
[[ -n $S ]] || { echo "usage: ./deploy.sh <tv-ip:port> [--config config.json]" >&2; exit 1; }

PKG=com.home.dashboard
SVC=$PKG/$PKG.HomeOverrideService
LISTENER=$PKG/$PKG.MediaListener

tv() { adb -s $S shell "$@" }
start_app() { tv am start -n $PKG/.MainActivity >/dev/null }
bound() { tv dumpsys accessibility | grep -q 'label=Home button override' }

if [[ -z $NO_INSTALL ]]; then
  # Homebrew's JDK 17 if JAVA_HOME isn't already set.
  [[ -z $JAVA_HOME && -d /opt/homebrew/opt/openjdk@17 ]] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17
  ./gradlew assembleRelease -q
  adb -s $S install -r app/build/outputs/apk/release/app-release.apk
  tv cmd package compile -m speed -f $PKG
  start_app  # a freshly updated app's services won't bind until it has run once
  sleep 3
fi

if [[ -n $CONFIG ]]; then
  DEST=/sdcard/Android/data/$PKG/files
  tv mkdir -p $DEST
  adb -s $S push "$CONFIG" $DEST/config.json >/dev/null
  tv am force-stop $PKG  # the app moves the file into private storage when it starts
  sleep 1
  start_app
  sleep 4
  tv "test -e $DEST/config.json" && echo "config NOT imported" || echo "config imported"
fi

# Turn the Home override on, keeping any other accessibility services you use.
others=$(tv settings get secure enabled_accessibility_services | tr -d '\r' | tr ':' '\n' |
  grep -v -e "^$SVC\$" -e '^null$' | grep . | paste -s -d: - || true)
for attempt in 1 2 3 4 5; do
  sleep 4
  if [[ -n $others ]]; then
    tv settings put secure enabled_accessibility_services "$others"
  else
    tv settings delete secure enabled_accessibility_services >/dev/null
  fi
  tv settings put secure accessibility_enabled 0
  sleep 2
  tv settings put secure enabled_accessibility_services "${others:+$others:}$SVC"
  tv settings put secure accessibility_enabled 1
  sleep 3
  bound && break
done
start_app
sleep 2

# Restart the "Continue watching" listener, only if you've already allowed it.
if tv settings get secure enabled_notification_listeners | grep -q $LISTENER; then
  tv cmd notification disallow_listener $LISTENER
  sleep 1
  tv cmd notification allow_listener $LISTENER
fi

bound && echo "home override: bound" || echo "home override: NOT bound"
