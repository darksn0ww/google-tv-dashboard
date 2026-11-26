# TV watchdog

Some TVs (TCL, for one) block apps from starting themselves, so after a restart the Google TV
home screen comes back. This container checks every minute and switches the dashboard's Home
override back on. It only acts while the TV is showing a home screen.

1. Set `TV_ADDRESSES` in `compose.yaml` to your TV (space-separated for several).
2. `docker compose up -d --build`, then `docker compose logs -f`.
3. On the TV, accept the debugging prompt and tick **Always allow**.

For managers that take a single pasted file (such as Arcane), use `arcane-compose.yaml`.

**Needs:** Wireless debugging left on, the Docker host able to reach the TV on TCP 5555, and a
fixed address for the TV.

**Security:** the `adb-keys` volume holds a key with full control of the TV. Run this only on a
host you trust.
