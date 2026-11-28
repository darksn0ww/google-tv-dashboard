#!/usr/bin/env bash
# TV Dashboard setup for macOS and Linux: pair a Google TV over Wi-Fi, create settings,
# install the app and switch it on. Needs only bash and curl; it offers to fetch adb.
#
#   curl -fsSL <address of this script> | bash
#   bash setup.sh [--apk my-build.apk] [--config config.json]
#
# With a TV this computer is already paired with, it can run without questions:
#
#   bash setup.sh --tv <ip:port> [--apk my-build.apk] [--config config.json]
#   bash setup.sh --tv <ip:port> --enable-only      only switch the Home button back on
#   bash setup.sh --tv <ip:port> --config config.json --pull-favourites
#                                                   only copy favourites from the TV into the file
set -u

REPO=darksn0ww/google-tv-dashboard
RELEASE=https://github.com/$REPO/releases/latest/download
PKG=com.home.dashboard
SERVICE=$PKG/$PKG.HomeOverrideService
LISTENER=$PKG/$PKG.MediaListener
CACHE=${TVD_CACHE:-$HOME/.tv-dashboard}
# SHA-256 of the certificate that signs published releases (also in the README).
SIGNER=968e4cb1a2a7f0c2831ac7c7f014c34a99d87559e0bc61ff30a6a5aad8943a39
AUTO="" # set by --tv: ask nothing

die() { echo "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$1" >&2; }
say() { echo "$*" >&2; }

# ---------- Questions (read from the keyboard, even when the script is piped into bash) ----------

ask() { # question [default]
  local answer
  printf '%s%s: ' "$1" "${2:+ [$2]}" >&2
  IFS= read -r answer <&3 || die "
Stopped."
  printf '%s' "${answer:-${2:-}}"
}

secret() {
  local answer
  printf '%s (hidden): ' "$1" >&2
  IFS= read -rs answer <&3 || die "
Stopped."
  echo >&2
  printf '%s' "$answer"
}

yes_no() { # question [y|n]
  local answer hint="(Y/n)"
  [ "${2:-y}" = n ] && hint="(y/N)"
  answer=$(ask "$1 $hint") || exit 1
  answer=${answer:-${2:-y}}
  case $answer in [Yy]*) return 0 ;; *) return 1 ;; esac
}

# ---------- adb ----------

find_adb() {
  local c
  for c in "${ADB:-}" "$(command -v adb || true)" "${ANDROID_HOME:-/nowhere}/platform-tools/adb" "$CACHE/platform-tools/adb"; do
    if [ -n "$c" ] && [ -x "$c" ]; then ADB=$c; return; fi
  done
  local os
  case $(uname -s) in
    Darwin) os=darwin ;;
    Linux) os=linux
      [ "$(uname -m)" = x86_64 ] || die "Install adb with your package manager (e.g. sudo apt install adb), then run this again." ;;
    *) die "On Windows use setup.ps1 instead." ;;
  esac
  [ -z "$AUTO" ] || die "adb wasn't found. Install it, or run without --tv to be offered a download."
  say "adb (Google's Android tool) isn't installed."
  say "It can be downloaded from Google into $CACHE (licence: https://developer.android.com/studio/terms)."
  yes_no "Download it?" || die "Install adb, then run this again."
  command -v unzip >/dev/null || die "Please install unzip, then run this again."
  mkdir -p "$CACHE"
  curl -fL --progress-bar -o "$CACHE/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" || die "Download failed."
  unzip -oq "$CACHE/platform-tools.zip" -d "$CACHE" && rm "$CACHE/platform-tools.zip"
  ADB=$CACHE/platform-tools/adb
}

adb_() { "$ADB" "$@" </dev/null 3<&- 2>&1; }
tv() { adb_ -s "$SERIAL" shell "$@" | tr -d '\r'; }

ask_port() { # question [default]
  local p
  while :; do
    p=$(ask "$1" "${2:-}") || exit 1
    case $p in
      '' | *[!0-9]*) say "  A port is a number, e.g. 37123." ;;
      *) printf '%s' "$p"; return ;;
    esac
  done
}

connect() {
  step "Turn on Wireless debugging on the TV"
  cat >&2 <<'TEXT'
  1. Settings > System > About: select "Android TV OS build" seven times.
     (On some TVs it's Settings > Device Preferences > About > Build.)
  2. Back in System, open Developer options and turn on "Wireless debugging"
     (some TVs call it "USB debugging" or "Network debugging").
  3. The TV and this computer must be on the same network.
TEXT
  local ip pair_port code result
  while :; do
    ip=$(ask "
TV IP address") || exit 1
    case $ip in
      '' | *[!A-Za-z0-9.-]*) say "  That doesn't look like an IP address." ;;
      *) break ;;
    esac
  done
  if yes_no "Does this computer still need pairing with the TV?"; then
    say '  On the TV, choose "Pair device with pairing code". Keep that box open.'
    while :; do
      pair_port=$(ask_port "  Port shown in the pairing box") || exit 1
      code=$(ask "  Six-digit pairing code") || exit 1
      result=$(adb_ pair "$ip:$pair_port" "$code")
      case $result in *"Successfully paired"*) say "  Paired."; break ;; esac
      say "  Pairing failed: $result"
      yes_no "  Try again?" || exit 1
    done
    say "
  Close the box. The Wireless debugging screen shows a DIFFERENT port for connecting."
  fi
  while :; do
    pair_port=$(ask_port "Connection port" 5555) || exit 1
    SERIAL=$ip:$pair_port
    result=$(adb_ connect "$SERIAL")
    local i state
    for i in 1 2 3 4 5 6 7 8 9 10; do
      state=$(adb_ -s "$SERIAL" get-state | tr -d '\r')
      if [ "$state" = device ]; then say "  Connected to $(tv getprop ro.product.model)."; return; fi
      case $state in *unauthorized*) say '  Choose "Allow" on the TV...' ;; esac
      sleep 2
    done
    say "  Couldn't connect: $result"
    yes_no "  Try another port?" || exit 1
  done
}

# ---------- Backup ----------

# Saves how the TV (and this folder) were before anything changes, with commands to put it back.
backup() { # [settings file in use]
  local dir item ns name value path f
  step "Backing up"
  dir=$PWD/tv-dashboard-backup-$(date +%Y%m%d-%H%M%S)
  (umask 077 && mkdir "$dir") || die "Couldn't create $dir; nothing was changed."
  {
    echo "# To put the TV back, connect with: adb connect $SERIAL"
    echo "# then run the lines below. The first removes the dashboard, its settings and sign-ins."
    echo "adb uninstall $PKG"
    for item in secure:enabled_accessibility_services secure:accessibility_enabled \
      secure:enabled_notification_listeners secure:screensaver_enabled system:screen_off_timeout; do
      ns=${item%%:*} name=${item#*:}
      value=$(tv settings get "$ns" "$name")
      case $value in
        '') ;;
        null) echo "adb shell settings delete $ns $name" ;;
        *) echo "adb shell \"settings put $ns $name '$value'\"" ;;
      esac
    done
  } >"$dir/restore.txt" || die "Couldn't write the backup; nothing was changed."
  tv pm list packages >"$dir/packages.txt"
  path=$(tv pm path $PKG | sed -n 's/^package://p' | head -1)
  if [ -n "$path" ]; then
    adb_ -s "$SERIAL" pull "$path" "$dir/previous-app.apk" >/dev/null || die "Couldn't back up the installed app; nothing was changed."
    echo "# The version you had is previous-app.apk: after uninstalling, adb install previous-app.apk" >>"$dir/restore.txt"
  fi
  for f in "$PWD/config.json" "$PWD/wallpaper.img" "${1:-}"; do
    [ -n "$f" ] && [ -f "$f" ] || continue
    name=$(basename "$f")
    # A second file of the same name, from somewhere else, gets its own.
    if [ -e "$dir/$name" ] && ! cmp -s "$f" "$dir/$name"; then name=settings-used-$name; fi
    cp "$f" "$dir/$name" || die "Couldn't copy $f; nothing was changed."
  done
  say "  Saved to $dir
  WARNING: it can hold your tokens and private links. Don't share or sync it, and delete
  backups you no longer need."
}

# ---------- App ----------

download_apk() { # folder
  step "Downloading the latest release"
  curl -fsSL -o "$1/tv-dashboard.apk" "$RELEASE/tv-dashboard.apk" &&
    curl -fsSL -o "$1/SHA256SUMS.txt" "$RELEASE/SHA256SUMS.txt" ||
    die "Download failed. Download the APK yourself and use --apk."
  grep -q "^$(sha256 <"$1/tv-dashboard.apk") .*tv-dashboard.apk" "$1/SHA256SUMS.txt" ||
    die "The download doesn't match its published checksum; nothing was installed."
  signed_by_us "$1/tv-dashboard.apk" || die "The download isn't signed with this project's key; nothing was installed."
  say "  Checksum and signature OK."
}

# ---------- Signature ----------

sha256() { if command -v shasum >/dev/null; then shasum -a 256; else sha256sum; fi | cut -d' ' -f1; }
number() { od -An -j "$2" -N "$3" -tu"$3" "$1" | tr -d ' \n'; } # file offset bytes: a little-endian number

# Fails unless the APK has a v2 signature (which every supported Android checks) with the published
# certificate, and any v3 or v3.1 entry names the same one. It reads the APK Signing Block; Android
# itself checks, at install, that the signature matches that certificate.
signed_by_us() { # apk
  local f=$1 size eocd cd block pos end len id signer signers_end cert_at digests v2=0 sum
  size=$(($(wc -c <"$f")))
  eocd=$((size - 22))
  [ "$eocd" -gt 0 ] && [ "$(number "$f" $eocd 4)" = 101010256 ] && [ "$(number "$f" $((eocd + 20)) 2)" = 0 ] || return 1
  cd=$(number "$f" $((eocd + 16)) 4)
  [ "$(tail -c +$((cd - 15)) "$f" | head -c 16)" = "APK Sig Block 42" ] || return 1
  len=$(number "$f" $((cd - 24)) 8)
  block=$((cd - len - 8))
  [ "$block" -ge 0 ] && [ "$(number "$f" $block 8)" = "$len" ] || return 1
  pos=$((block + 8))
  end=$((cd - 24))
  while [ "$pos" -lt "$end" ]; do
    len=$(number "$f" $pos 8)
    [ "$len" -ge 4 ] && [ "$len" -le $((end - pos)) ] || return 1
    id=$(number "$f" $((pos + 8)) 4)
    case $id in
      1896449818 | 4031998144 | 462663009) # signature schemes v2, v3 and v3.1
        signer=$((pos + 16))
        signers_end=$((signer + $(number "$f" $((pos + 12)) 4)))
        [ "$signers_end" -le $((pos + 8 + len)) ] || return 1
        while [ "$signer" -lt "$signers_end" ]; do
          digests=$(number "$f" $((signer + 8)) 4)
          cert_at=$((signer + 12 + digests + 4)) # past the digests and the certificate list's length
          sum=$(tail -c +$((cert_at + 5)) "$f" | head -c "$(number "$f" $cert_at 4)" | sha256)
          [ "$sum" = "$SIGNER" ] || return 1
          [ "$id" != 1896449818 ] || v2=$((v2 + 1))
          signer=$((signer + 4 + $(number "$f" $signer 4)))
        done ;;
    esac
    pos=$((pos + 8 + len))
  done
  [ "$v2" -gt 0 ]
}

# ---------- Settings ----------

json() { printf '"%s"' "$(printf '%s' "$1" | tr -d '\000-\037' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"; }
add() { # object-so-far key raw-json-value
  printf '%s%s%s:%s' "$1" "${1:+,}" "$(json "$2")" "$3"
}
field() { sed -n "s/.*\"$1\": *\"\\([^\"]*\\)\".*/\\1/p"; } # a string value from one line of JSON
urlencode() {
  local s=$1 i c out=""
  local LC_ALL=C
  for ((i = 0; i < ${#s}; i++)); do
    c=${s:i:1}
    case $c in [A-Za-z0-9._~-]) out+=$c ;; *) out+=$(printf '%%%02X' "'$c") ;; esac
  done
  printf '%s' "$out"
}

location() {
  local town results n choice line
  say "
Weather location
  1. A town you choose
  2. Automatic, from your internet connection (the TV's public IP address goes to geojs.io)"
  choice=$(ask "Which?" 1) || exit 1
  while :; do
    town=""
    if [ "$choice" != 2 ]; then town=$(ask "  Town (blank for automatic)") || exit 1; fi
    if [ -z "$town" ]; then C=$(add "$C" location '"auto"'); return 0; fi
    results=$(curl -fsS -m 10 "https://geocoding-api.open-meteo.com/v1/search?count=5&name=$(urlencode "$town")" |
      awk '{gsub(/\{"id":/, "\n"); print}' | grep '"latitude"')
    if [ -z "$results" ]; then say "  Nothing found; try a nearby town."; continue; fi
    n=0
    while IFS= read -r line; do
      n=$((n + 1))
      say "  $n. $(echo "$line" | field name), $(echo "$line" | field admin1), $(echo "$line" | field country)"
    done <<<"$results"
    choice=$(ask "  Which one?" 1) || exit 1
    case $choice in '' | *[!0-9]*) continue ;; esac
    line=$(sed -n "${choice}p" <<<"$results")
    [ -n "$line" ] || continue
    C=$(add "$C" place "$(json "$(echo "$line" | field name)")")
    C=$(add "$C" latitude "$(echo "$line" | sed -n 's/.*"latitude": *\([-0-9.]*\).*/\1/p')")
    C=$(add "$C" longitude "$(echo "$line" | sed -n 's/.*"longitude": *\([-0-9.]*\).*/\1/p')")
    return 0
  done
}

wallpaper() { # folder the settings are saved in
  local choice v
  say "
Wallpaper
  1. Random photos from Unsplash (needs a free access key)
  2. Your own picture: a file on this computer, or a web link
  3. Plain background"
  choice=$(ask "Which?" 1) || exit 1
  case $choice in
    1) v=$(secret "  Unsplash access key (blank for a plain background)") || exit 1
       [ -n "$v" ] && C=$(add "$C" unsplashKey "$(json "$v")") ;;
    2) while :; do
         v=$(ask "  File path or https:// link (blank to skip)") || exit 1
         [ -n "$v" ] || return 0
         case $v in http://* | https://*) C=$(add "$C" wallpaper "$(json "$v")"); return 0 ;; esac
         # Dragging a file onto the terminal adds quotes or backslashes.
         v=${v%"${v##*[![:space:]]}"}
         v=${v#[\'\"]}; v=${v%[\'\"]}
         [ -f "$v" ] || v=$(printf '%s' "$v" | sed 's/\\\(.\)/\1/g')
         case $v in "~/"*) v=$HOME/${v#??} ;; esac
         case $v in
           *.[Jj][Pp][Gg] | *.[Jj][Pp][Ee][Gg] | *.[Pp][Nn][Gg] | *.[Ww][Ee][Bb][Pp]) ;;
           *) say "  Use a .jpg, .png or .webp picture."; continue ;;
         esac
         if [ -f "$v" ] && cp "$v" "$1/wallpaper.img"; then C=$(add "$C" wallpaper '"file"'); return 0; fi
         say "  Can't read that file."
       done ;;
  esac
  return 0
}

# Numbered menu of entity IDs; prints the chosen ones, one per line.
pick() { # title ids [several]
  local title=$1 ids=$2 answer n
  [ -n "$ids" ] || return 0
  say "
  $title"
  awk '{printf "   %2d. %s\n", NR, $0}' <<<"$ids" >&2
  if [ -n "${3:-}" ]; then answer=$(ask "  Numbers separated by commas, or blank for none") || exit 1
  else answer=$(ask "  Number, or blank for none") || exit 1; fi
  for n in $(printf '%s' "$answer" | tr -c '0-9' ' '); do
    sed -n "${n}p" <<<"$ids"
    [ -n "${3:-}" ] || break
  done
}

ha_get() { # url token; the token goes through stdin so it isn't visible in the process list
  printf 'header = "Authorization: Bearer %s"\n' "$2" | curl -fsS -m 10 -K - "$1"
}

home_assistant() {
  say "
  In Home Assistant, make a user for the TV with Administrator off, sign in as it,
  and create a token under Security > Long-lived access tokens."
  local url token states all
  while :; do
    url=$(ask "  Home Assistant address, e.g. http://192.168.1.10:8123") || exit 1
    url=${url%/}
    token=$(secret "  Long-lived access token") || exit 1
    states=$(ha_get "$url/api/states" "$token") && break
    say "  Couldn't read Home Assistant. Check the address and token."
    yes_no "  Try again?" || return 0
  done
  all=$(echo "$states" | grep -o '"entity_id": *"[^"]*"' | sed 's/.*: *"//; s/"$//' | sort)
  of() { grep -E "^($1)\." <<<"$all" | grep -E "${2:-.}"; }

  local h="" v list d devices="" maps=""
  h=$(add "$h" url "$(json "$url")")
  h=$(add "$h" token "$(json "$token")")
  v=$(pick "Thermostat for the heating card" "$(of climate)")
  if [ -n "$v" ]; then
    h=$(add "$h" climate "$(json "$v")")
    v=$(pick "Room temperature sensor (optional)" "$(of sensor temperature)")
    [ -n "$v" ] && h=$(add "$h" temperature "$(json "$v")")
    v=$(pick "Humidity sensor (optional)" "$(of sensor humidity)")
    [ -n "$v" ] && h=$(add "$h" humidity "$(json "$v")")
  fi
  v=$(pick "Robot vacuum for the smart home panel" "$(of vacuum)")
  if [ -n "$v" ]; then
    h=$(add "$h" vacuum "$(json "$v")")
    v=$(pick "Its battery sensor (optional)" "$(of sensor battery)")
    [ -n "$v" ] && h=$(add "$h" battery "$(json "$v")")
    list=$(pick "Vacuum maps to show, one per floor (optional)" "$(of image)" several)
    for d in $list; do maps="$maps${maps:+,}$(json "$d")"; done
    if [ -n "$maps" ]; then
      h=$(add "$h" maps "[$maps]")
      v=$(pick "Which one names the floor in use? (optional)" "$(of select selected_map)")
      [ -n "$v" ] && h=$(add "$h" mapSelect "$(json "$v")")
      case $maps in *,*)
        v=$(pick "Only one dock? Pick its floor, shown while docked (blank if each floor has a dock)" "$list")
        [ -n "$v" ] && h=$(add "$h" dockMap "$(json "$v")") ;;
      esac
    fi
  fi
  list=$(pick "Plugs and lights the TV may switch" "$(of 'light|switch|fan|input_boolean')" several)
  for d in $list; do
    v=$(ask "  Name for $d" "$(echo "${d#*.}" | tr _ ' ')") || exit 1
    devices="$devices${devices:+,}{$(add "$(add "" name "$(json "$v")")" entity "$(json "$d")")}"
  done
  [ -n "$devices" ] && h=$(add "$h" devices "[$devices]")
  C=$(add "$C" homeAssistant "{$h}")
}

nest() {
  say "
  Nest is experimental. You need your own Google Device Access project (see the README)."
  local project client key query pasted code tokens refresh access
  project=$(ask "  Device Access project ID") || exit 1
  client=$(ask "  OAuth client ID") || exit 1
  key=$(secret "  OAuth client secret") || exit 1
  query="redirect_uri=https%3A%2F%2Fwww.google.com&access_type=offline&prompt=consent&client_id=$(urlencode "$client")&response_type=code&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fsdm.service"
  say "
  Open this, choose your home and thermostat, and allow access:

  https://nestservices.google.com/partnerconnections/$project/auth?$query
"
  pasted=$(ask "  Paste the address of the google.com page you end up on") || exit 1
  code=$(printf '%s' "$pasted" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p' | sed 's/%2F/\//g')
  [ -n "$code" ] || { say "  No code in that address; Nest not added."; return 0; }
  tokens=$(printf 'data-urlencode = "client_secret=%s"\n' "$key" | curl -fsS -m 15 -K - \
    --data-urlencode "client_id=$client" --data-urlencode "code=$code" \
    --data-urlencode "grant_type=authorization_code" --data-urlencode "redirect_uri=https://www.google.com" \
    https://www.googleapis.com/oauth2/v4/token | tr -d '\n') || { say "  Google refused the code; Nest not added."; return 0; }
  refresh=$(echo "$tokens" | field refresh_token)
  access=$(echo "$tokens" | field access_token)
  [ -n "$refresh" ] || { say "  Google sent no refresh token; Nest not added."; return 0; }
  # Google only finishes linking after the device list has been requested once.
  ha_get "https://smartdevicemanagement.googleapis.com/v1/enterprises/$project/devices" "$access" >/dev/null || true
  local n=""
  n=$(add "$n" projectId "$(json "$project")")
  n=$(add "$n" clientId "$(json "$client")")
  n=$(add "$n" clientSecret "$(json "$key")")
  n=$(add "$n" refreshToken "$(json "$refresh")")
  C=$(add "$C" nest "{$n}")
}

create_config() { # path
  step "Settings (leave anything blank to skip it)"
  C=""
  location
  local v name url hd calendars="" cameras="" camera
  wallpaper "$(dirname "$1")"
  say "
Calendar widget
  1. A plain calendar showing this month
  2. Upcoming events from your web calendars"
  v=$(ask "Which?" 1) || exit 1
  while [ "$v" != 1 ]; do
    v=$(ask "  Calendar link, webcal:// or https:// .ics (blank when done)") || exit 1
    [ -n "$v" ] || break
    calendars="$calendars${calendars:+,}$(json "$v")"
    v=more
  done
  C=$(add "$C" calendars "[$calendars]")
  while :; do
    name=$(ask "Camera name (blank when done)") || exit 1
    [ -n "$name" ] || break
    url=$(ask "  Low quality RTSP link (for the tile)") || exit 1
    hd=$(ask "  High quality link for full screen (optional)") || exit 1
    [ -n "$url" ] || continue
    camera=$(add "$(add "" name "$(json "$name")")" url "$(json "$url")")
    [ -n "$hd" ] && camera=$(add "$camera" hd "$(json "$hd")")
    cameras="$cameras${cameras:+,}{$camera}"
  done
  C=$(add "$C" cameras "[$cameras]")
  say "
Heating and smart home
  1. None
  2. Through Home Assistant (HomeKit, Hive or other thermostats; vacuum, plugs, lights)
  3. Straight from tado (no server needed)
  4. Straight from Google Nest (no server needed, experimental)"
  case $(ask "Which?" 1) in
    2) home_assistant ;;
    3) C=$(add "$C" tado true)
       C=$(add "$C" tadoZone "$(json "$(ask "  Room name (blank for the first)")")")
       say "  After installing, select the heating card on the TV to sign in." ;;
    4) nest ;;
  esac
  say ""
  yes_no "Install new releases automatically while the screensaver is on?" n && C=$(add "$C" autoUpdate true)
  (umask 077 && printf '{%s}\n' "$C" >"$1") || die "Couldn't write $1"
  chmod 600 "$1"
  say "  Saved to $1. It holds private links and keys: don't share it."
}

# ---------- Screensaver ----------

screensaver() {
  local on ms now choice minutes
  on=$(tv settings get secure screensaver_enabled)
  ms=$(tv settings get system screen_off_timeout)
  case $ms in '' | *[!0-9]*) now="after an unknown time" ;; *) now="after $((ms / 60000)) minutes" ;; esac
  [ "$on" = 0 ] && now="never (it's off)"
  step "Screensaver"
  say "  The TV's ambient screensaver currently starts $now.
  1. Leave it as it is
  2. Change how long it waits
  3. Turn it off (the TV may switch its screen off instead)"
  choice=$(ask "Which?" 1) || exit 1
  case $choice in
    2) while :; do
         minutes=$(ask "  Minutes before it starts" 10) || exit 1
         case $minutes in *[!0-9]* | '' | 0* | ?????*) say "  A whole number of minutes, from 1." ;; *) break ;; esac
       done
       tv settings put system screen_off_timeout $((minutes * 60000))
       tv settings put secure screensaver_enabled 1
       say "  Set to $minutes minutes." ;;
    3) tv settings put secure screensaver_enabled 0
       say "  Off. Run this again and choose 2 to turn it back on." ;;
  esac
}

# ---------- Updates ----------

wants_auto_update() { [ -n "${1:-}" ] && grep -Eq '"autoUpdate"[[:space:]]*:[[:space:]]*true' "$1"; }
may_install() { tv cmd appops get $PKG REQUEST_INSTALL_PACKAGES | grep -q 'REQUEST_INSTALL_PACKAGES: allow'; }
ALLOW_HINT="cmd appops set $PKG REQUEST_INSTALL_PACKAGES allow"

# Only ever with a typed yes: it lets the app install software on the TV.
allow_updates() { # settings file
  wants_auto_update "${1:-}" || return 0
  if [ "$(tv getprop ro.build.version.sdk)" -lt 31 ] 2>/dev/null; then
    step "Automatic updates"
    say "  This TV's Android is too old to update apps by itself. It will just say when there's one."
    return 0
  fi
  may_install && return 0
  step "Automatic updates"
  say "  To update itself, the dashboard needs Android's permission to install apps. Android only
  accepts an update signed with the same key as the installed app."
  if [ "$(tv getprop ro.product.manufacturer)" = TCL ]; then
    say "
  WARNING: TCL TVs don't restart the Home button after an update, leaving you on Google TV's
  home screen. Only allow this if you run the watchdog container (see the watchdog folder)."
  fi
  if yes_no "Allow it on this TV?" n; then
    tv cmd appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null
    if may_install; then say "  Allowed."; else say "  That didn't work. Allow it on the TV when the dashboard asks."; fi
  else
    say "  Not allowed. The dashboard will just say when there's an update."
  fi
}

# ---------- Install ----------

start_app() { tv am start -n $PKG/.MainActivity >/dev/null; }
bound() { tv dumpsys accessibility | grep -q 'label=Home button override'; }

# The part between the brackets of "key": [ ... ] in JSON with its spaces removed.
array_of() { printf '%s' "$1" | sed -n "s/.*\"$2\":\[\([^]]*\)\].*/\1/p"; }

# Writes favourites rearranged on the TV back into a settings file, unless the file's own list
# has been changed since: then the file wins, as it does on the TV.
pull_favourites() { # config file
  local saved base list mine updated
  saved=$(tv cat /sdcard/Android/data/$PKG/files/favourites.json | tr -d '\n\r') # written without spaces
  case $saved in *'"list":['*) ;; *) return 0 ;; esac
  base=$(array_of "$saved" config)
  list=$(array_of "$saved" list)
  mine=$(array_of "$(tr -d ' \n\r\t' <"$1")" favourites)
  [ "$mine" = "$base" ] && [ "$list" != "$base" ] || return 0
  # It comes from the TV, so accept package names and nothing else.
  printf '%s\n' "$list" | grep -Eq '^("[A-Za-z0-9_.]+"(,"[A-Za-z0-9_.]+")*)?$' || return 0
  updated=$(awk -v list="$list" '
    { text = text $0 "\n" }
    END {
      if (match(text, /"favourites"[ \t\n]*:[ \t\n]*\[[^]]*\]/))
        text = substr(text, 1, RSTART - 1) "\"favourites\": [" list "]" substr(text, RSTART + RLENGTH)
      else if (match(text, /\{/))
        text = substr(text, 1, RSTART) "\n  \"favourites\": [" list "]," substr(text, RSTART + 1)
      printf "%s", text
    }' "$1")
  printf '%s\n' "$updated" >"$1" && say "  Copied your favourites from the TV into $1"
}

push_config() {
  pull_favourites "$1"
  local dest=/sdcard/Android/data/$PKG/files
  tv mkdir -p $dest >/dev/null
  if grep -q '"wallpaper": *"file"' "$1"; then
    local picture
    picture=$(dirname "$1")/wallpaper.img
    if [ -f "$picture" ]; then adb_ -s "$SERIAL" push "$picture" $dest/wallpaper >/dev/null || say "  Couldn't copy the wallpaper."
    else say "  No wallpaper.img next to the settings; keeping what's on the TV."; fi
  fi
  adb_ -s "$SERIAL" push "$1" $dest/config.json >/dev/null || die "Couldn't copy the settings to the TV."
  tv am force-stop $PKG >/dev/null # the app moves the file into private storage when it starts
  sleep 1
  start_app
  sleep 4
  if tv ls $dest | grep -q config.json; then say "  Settings NOT imported: check config.json for mistakes. The TV keeps its old ones."
  else say "  Settings imported."; fi
}

enable_home_button() { # keeps any other accessibility services you use
  local current others attempt
  current=$(tv settings get secure enabled_accessibility_services)
  # "null" or a list of services; anything else means the read failed, so change nothing.
  printf '%s' "$current" | grep -Eq '^[A-Za-z0-9_.$/:]+$' || return 1
  others=$(printf '%s' "$current" | tr ':' '\n' | grep -v -e "^$SERVICE\$" -e '^null$' | grep . | paste -s -d: - || true)
  for attempt in 1 2 3 4 5; do
    sleep 4
    if [ -n "$others" ]; then tv settings put secure enabled_accessibility_services "$others"
    else tv settings delete secure enabled_accessibility_services >/dev/null; fi
    tv settings put secure accessibility_enabled 0
    sleep 2
    tv settings put secure enabled_accessibility_services "${others:+$others:}$SERVICE"
    tv settings put secure accessibility_enabled 1
    sleep 3
    bound && return 0
  done
  return 1
}

fetch_apk() { # sets apk to a checked download
  WORK=$(mktemp -d) || die "Couldn't make a temporary folder."
  trap 'rm -rf "$WORK"' EXIT
  download_apk "$WORK"
  apk=$WORK/tv-dashboard.apk
}

install_app() { # apk
  step "Installing"
  local result
  result=$(adb_ -s "$SERIAL" install -r "$1")
  case $result in
    *Success*) ;;
    *UPDATE_INCOMPATIBLE*) die "$result
The installed copy was signed with a different key. Uninstall it first (this removes its
settings and sign-ins): adb uninstall $PKG" ;;
    *) die "Install failed: $result" ;;
  esac
  tv cmd package compile -m speed -f $PKG >/dev/null
  start_app # a freshly installed app's services won't bind until it has run once
  sleep 3
}

# Restarts "Continue watching" if it was already allowed; fails if it never was.
restart_listener() {
  tv settings get secure enabled_notification_listeners | grep -q $LISTENER || return 1
  tv cmd notification disallow_listener $LISTENER >/dev/null
  sleep 1
  tv cmd notification allow_listener $LISTENER >/dev/null
}

unattended() { # apk config enable-only pull-only
  local apk=$1
  find_adb
  adb_ connect "$SERIAL" >/dev/null
  [ "$(adb_ -s "$SERIAL" get-state | tr -d '\r')" = device ] || die "Can't connect to $SERIAL. Run without --tv to pair."
  if [ -n "$4" ]; then
    pull_favourites "$2"
    return
  fi
  if [ -z "$3" ]; then
    [ -n "$apk" ] || fetch_apk
    install_app "$apk"
  fi
  [ -z "$2" ] || push_config "$2"
  enable_home_button || die "home override: NOT bound"
  restart_listener || true
  start_app
  say "home override: bound"
  if wants_auto_update "$2" && ! may_install; then
    say "autoUpdate is on but installing isn't allowed. Allow it on the TV, or run: adb -s $SERIAL shell $ALLOW_HINT"
  fi
}

main() {
  local apk="" config="" tv_address="" enable_only="" pull_only=""
  local usage="usage: setup.sh [--apk file.apk] [--config config.json] [--tv ip:port [--enable-only | --pull-favourites]]"
  while [ $# -gt 0 ]; do
    case $1 in
      --apk | --config | --tv)
        [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value. $usage"
        case $1 in --apk) apk=$2 ;; --config) config=$2 ;; --tv) tv_address=$2 ;; esac
        shift 2 ;;
      --enable-only) enable_only=1; shift ;;
      --pull-favourites) pull_only=1; shift ;;
      *) die "$usage" ;;
    esac
  done
  [ -z "$apk" ] || [ -f "$apk" ] || die "No such file: $apk"
  [ -z "$config" ] || [ -f "$config" ] || die "No such file: $config"
  [ -z "$enable_only" ] || [ -n "$tv_address" ] || die "$usage"
  [ -z "$pull_only" ] || { [ -n "$tv_address" ] && [ -n "$config" ]; } || die "$usage"
  command -v curl >/dev/null || die "Please install curl first."

  if [ -n "$tv_address" ]; then
    AUTO=1
    SERIAL=$tv_address
    unattended "$apk" "$config" "$enable_only" "$pull_only"
    return
  fi

  { exec 3<"${TVD_INPUT:-/dev/tty}"; } 2>/dev/null || die "Run this in a terminal: it needs to ask you questions."
  find_adb
  connect
  backup "$config"
  [ -n "$apk" ] || fetch_apk

  if [ -z "$config" ]; then
    step "Settings"
    if [ -f config.json ] && yes_no "Use the config.json in this folder?"; then
      config=config.json
    elif yes_no "Create settings now? (No keeps whatever is already on the TV)"; then
      if [ -e config.json ]; then yes_no "Overwrite the existing config.json?" n || die "Nothing changed."; fi
      create_config "$PWD/config.json"
      config=$PWD/config.json
    fi
  fi

  install_app "$apk"
  [ -z "$config" ] || push_config "$config"

  step "Home button"
  say "  This switches on an accessibility service so Home opens the dashboard. Android sends it
  every remote key press; it only acts on Home."
  if yes_no "Switch it on?"; then
    if enable_home_button; then say "  On."; else say "  It didn't start. Turn it on in Settings > System > Accessibility."; fi
  fi

  step "Continue watching"
  if restart_listener; then say "  Already allowed."
  elif yes_no "Show what you were watching? (needs notification access; notifications aren't read)"; then
    tv cmd notification allow_listener $LISTENER >/dev/null
  fi

  allow_updates "$config"
  screensaver

  start_app
  say "
Done. Turn Wireless debugging off on the TV if you don't need it again."
}

main "$@" # last line, so a half-downloaded script does nothing
