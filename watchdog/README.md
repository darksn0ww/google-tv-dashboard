# TV watchdog

Some TVs (TCL, for one) block apps from starting themselves, so after a restart the Google TV
home screen comes back. This container checks every minute and switches the dashboard's Home
override back on. With dashboard 1.5.1 or later it does this in the background, whatever app is
open, without bringing anything to the front (just after a restart this needs dashboard 1.6.0 or
later). It opens the dashboard only while Google TV's home is showing. With older versions it only acts on a home screen. If a repair fails it tries less
and less often, down to once every half hour or so.

If you turn the Home override off on purpose, stop this container too, or it will turn it back on.

1. Set `TV_ADDRESSES` in `compose.yaml` to your TV (space-separated for several).
2. `docker compose up -d --build`, then `docker compose logs -f`.
3. On the TV, accept the debugging prompt and tick **Always allow**.

For managers that take a single pasted file (such as Arcane), use `arcane-compose.yaml`. The
script inside it is generated: after changing `watchdog.sh`, run `bash build-arcane.sh`.

**Needs:** Wireless debugging left on, the Docker host able to reach the TV on TCP 5555, and a
fixed address for the TV.

**Security:** the `adb-keys` volume holds a key with full control of the TV. Run this only on a
host you trust.
