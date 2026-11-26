# TV Dashboard

A calm, ad-free home screen for Google TV, written in Kotlin with Compose for TV.

![The home screen, shown with demo data](docs/images/home.png)

*Demo data. The calendar entries and heating readings are made up; [demo settings](docs/demo-config.json).*

## Features

- **Widgets:** clock, weather with a 5-day forecast, [tado°](https://www.tado.com) or
  [Google Nest](#google-nest-thermostat-experimental) heating and a merged calendar from any number of iCal / webcal links
- **Wallpaper:** random photos from Unsplash
- **Apps:** a favourites row, every other app, and physical inputs on TVs that have them
- **Cameras:** UniFi Protect (or any RTSP) stills on the home screen, live video full screen
- **Continue watching:** what you recently played on this device
- **Ambient mode:** select the clock to start Google's screensaver
- **Home button:** opens the dashboard instead of the Google TV home screen

Used on a Google TV Streamer and a TCL Google TV, both Android 14.

## Install a release (no build tools needed)

You need [`adb`](https://developer.android.com/tools/releases/platform-tools) on a Mac or Linux computer.

1. Download `tv-dashboard.apk`, `install.sh` and `config.example.json` from the
   [latest release](../../releases/latest).
2. On the TV, turn on Developer options and Wireless debugging, then `adb pair` and `adb connect`.
3. Copy `config.example.json` to `config.json`, edit it, and run:

       bash install.sh <tv-ip:port> tv-dashboard.apk config.json

To update, run the same command with the new APK and leave out `config.json`.
Installing the app switches on an accessibility service (see [Permissions](#permissions)).

## Requirements to build from source

- A Google TV / Android TV device (Android 9 or later) with Wireless debugging
- JDK 17, the Android SDK (platform 34) and `adb`. The deploy script is written for zsh.

## Build and install

1. Tell Gradle where the SDK is: set `ANDROID_HOME`, or add `sdk.dir=...` to `local.properties`.
2. On the TV, enable Developer options and Wireless debugging, then `adb pair` and `adb connect`.
3. Copy the example settings, edit them, and deploy:

       cp config.example.json config.json
       ./deploy.sh <tv-ip:port> --config config.json

4. For "Continue watching" (optional), allow notification access once:

       adb shell cmd notification allow_listener com.home.dashboard/com.home.dashboard.MediaListener

Builds are signed with your local Android debug key. Updating an installed app from another
computer needs the same key.

## Settings

`config.json` is imported into the app's private storage and is git-ignored. **Never commit it**:
it holds your API key and private links.

| Key | Meaning |
| --- | --- |
| `place`, `latitude`, `longitude` | Weather location |
| `unsplashKey` | [Unsplash access key](https://unsplash.com/developers); without it you get a plain background |
| `unsplashQueries`, `wallpaperMinutes` | Photo themes and how often they change |
| `calendars` | `webcal://` or `https://` .ics links |
| `cameras` | `name`, `url` (low quality, for tiles) and optional `hd` (full screen) |
| `snapshotSeconds` | How often camera stills refresh (default 15) |
| `favourites`, `hidden` | Package names to pin or hide |
| `tado`, `tadoZone` | Show the heating card, and which room |
| `nest` | Written by `tools/nest-auth.py`; replaces tado on the heating card |

**Heating:** select the card and approve the code in your browser. Without a tado subscription
the API allows few requests a day, so readings can be around 30 minutes old.

**Cameras:** UniFi Protect `rtsps://…:7441` links are converted to Protect's plain RTSP port
7447, so video is unencrypted on your local network.

## Google Nest thermostat (experimental)

> **Untested on real hardware.** Written from Google's documentation; reports and fixes are welcome.

Shows current temperature, target and eco mode. It is read-only, and Google's API provides no
schedule, so there is no "next change".

You need your own credentials:

1. Register for [Device Access](https://developers.google.com/nest/device-access/registration)
   with the Google account that owns the thermostat. Google charges a one-off US$5.
2. In [Google Cloud](https://console.cloud.google.com), create a project, enable the **Smart Device
   Management API**, and create an OAuth client of type **Web application** with the redirect URI
   `https://www.google.com`.
3. On the OAuth consent screen, add the `https://www.googleapis.com/auth/sdm.service` scope and
   **publish the app to production**. Left in testing, sign-ins can stop working after 7 days.
4. In the [Device Access console](https://console.nest.google.com/device-access), create a project
   using that OAuth client ID. Note the project ID.
5. On your computer, run `python3 tools/nest-auth.py config.json` and follow the prompts.
6. Deploy with the updated `config.json`. Set `nest.room` to choose between several thermostats.

See Google's [authorisation guide](https://developers.google.com/nest/device-access/authorize).
The secret and token are stored in `config.json` and the app's private storage: keep them private.

## Permissions

| Permission | Why |
| --- | --- |
| Accessibility service | Catches the Home button, and presses "Start now" after you select the clock. Android sends it all remote key presses; it acts only on Home and passes the rest through. |
| Notification access (optional) | Reads media sessions for "Continue watching". It opts out of receiving notifications. |

## Known limits

- Some TVs (TCL, for one) block auto-start, so after a restart Google TV's home returns. Run
  `NO_INSTALL=1 ./deploy.sh <tv-ip:port>`, or use the [watchdog container](watchdog/).
- "Continue watching" only knows what apps report. Some give no artwork or episode number, and
  selecting a card opens the app, not the episode.
- On modest TV graphics chips, keep full-screen blending to a minimum: it is why the wallpaper
  gradient is baked into the photo and camera tiles are stills.

## Extras

- [Debloating a TCL Google TV](docs/debloat-tcl.md)

## Credits

Weather data by [Open-Meteo](https://open-meteo.com) (CC BY 4.0). Photos from
[Unsplash](https://unsplash.com). Font: [Inter](https://github.com/rsms/inter), under the
[SIL Open Font License](licenses/Inter-OFL.txt).

Not affiliated with Google, Apple, TCL, Ubiquiti, Plex, tado° or Unsplash.

## Licence

[MIT](LICENSE)
