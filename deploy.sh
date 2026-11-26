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

if [[ -z $NO_INSTALL ]]; then
  # Homebrew's JDK 17 if JAVA_HOME isn't already set.
  [[ -z $JAVA_HOME && -d /opt/homebrew/opt/openjdk@17 ]] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17
  ./gradlew assembleRelease -q
  APK=app/build/outputs/apk/release/app-release.apk
fi
exec ./install.sh $S ${APK:--} $CONFIG
