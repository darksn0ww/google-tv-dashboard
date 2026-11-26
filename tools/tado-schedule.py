#!/usr/bin/env python3
"""Publish a tado heating schedule for Home Assistant, asking tado at most once every 7 days.

    python3 tado-schedule.py login          # once: approve the code in your browser
    python3 tado-schedule.py                # prints the schedule as JSON (cached)
    python3 tado-schedule.py --force        # fetch now, after changing your schedule

Run it from a Home Assistant command_line sensor (see the README). Standard library only.
Its sign-in and cache are kept next to the script: keep that folder private.
EXPERIMENTAL: for tado V2/V3 zones; tado X is not supported.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

CLIENT = "1bb50063-6b0c-4d11-bd99-387f4a91cc46"  # tado's published public client id
LOGIN = "https://login.tado.com/oauth2"
API = "https://my.tado.com/api/v2"
WEEK = 7 * 86400
RETRY = 86400  # after a failure, leave tado alone for a day
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


def load(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def save(path, data):
    tmp = path.with_suffix(".tmp")
    with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        json.dump(data, f)
    tmp.replace(path)


def request(url, form=None, token=None):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(form).encode() if form else None)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def login(state, path):
    code = request(LOGIN + "/device_authorize", {"client_id": CLIENT, "scope": "offline_access"})
    print("Open", code["verification_uri_complete"], "and approve code", code["user_code"])
    until = time.time() + code.get("expires_in", 300)
    while time.time() < until:
        time.sleep(max(code.get("interval", 5), 5))
        try:
            token = request(LOGIN + "/token", {
                "client_id": CLIENT,
                "device_code": code["device_code"],
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            })
        except urllib.error.HTTPError as e:
            if e.code == 400:
                continue  # not approved yet
            raise
        state["refresh"] = token["refresh_token"]
        save(path, state)
        print("Signed in.")
        return
    sys.exit("The code expired. Run login again.")


def week(blocks):
    """tado's timetable blocks as {day: [[start, temperature or None when off], ...]}."""
    groups = {"MONDAY_TO_SUNDAY": DAYS, "MONDAY_TO_FRIDAY": DAYS[:5]}
    out = {day: [] for day in DAYS}
    for b in blocks:
        setting = b["setting"]
        temp = setting["temperature"]["celsius"] if setting.get("power") == "ON" else None
        for day in groups.get(b["dayType"], [b["dayType"].lower()]):
            out[day].append([b["start"], temp])
    for entries in out.values():
        entries.sort(key=lambda e: e[0])
    return out


def fetch(state, path, wanted):
    token = request(LOGIN + "/token", {
        "client_id": CLIENT, "grant_type": "refresh_token", "refresh_token": state["refresh"],
    })
    state["refresh"] = token["refresh_token"]  # rotates on every use, so store it at once
    save(path, state)

    def get(url):
        return request(API + url, token=token["access_token"])

    if "home" not in state:
        state["home"] = get("/me")["homes"][0]["id"]
    home = "/homes/%s" % state["home"]
    if state.get("wanted") != wanted or "zone" not in state:
        zones = [z for z in get(home + "/zones") if z["type"] == "HEATING"]
        zone = next((z for z in zones if z["name"].lower() == wanted.lower()), None) if wanted else zones[0]
        if zone is None:
            sys.exit("No heating zone called %r. Found: %s" % (wanted, ", ".join(z["name"] for z in zones)))
        state.update(wanted=wanted, zone=zone["id"], zoneName=zone["name"])
    base = "%s/zones/%s/schedule" % (home, state["zone"])
    table = get(base + "/activeTimetable")["id"]
    state["schedule"] = week(get("%s/timetables/%s/blocks" % (base, table)))
    state["fetched"] = int(time.time())
    save(path, state)


def due(state, now, wanted, force):
    if force or "schedule" not in state or state.get("wanted") != wanted:
        return force or now - state.get("attempted", 0) >= RETRY
    return now - state["fetched"] >= WEEK and now - state.get("attempted", 0) >= RETRY


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", nargs="?", choices=["login"])
    parser.add_argument("--zone", default="", help="zone name; default: the first heating zone")
    parser.add_argument("--force", action="store_true", help="fetch now")
    parser.add_argument("--dir", default=Path(__file__).resolve().parent, type=Path)
    args = parser.parse_args()
    path = args.dir / "tado-schedule.json"
    state = load(path)
    if args.command == "login":
        return login(state, path)
    if "refresh" not in state:
        sys.exit("Not signed in. Run: python3 tado-schedule.py login")
    if due(state, time.time(), args.zone, args.force):
        state["attempted"] = int(time.time())
        save(path, state)
        try:
            fetch(state, path, args.zone)
        except (OSError, KeyError, IndexError, ValueError) as e:
            print("tado fetch failed:", type(e).__name__, getattr(e, "code", ""), file=sys.stderr)
    if "schedule" not in state:
        sys.exit("No schedule fetched yet.")
    print(json.dumps({
        "fetched": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(state["fetched"])),
        "zone": state.get("zoneName", ""),
        "schedule": state["schedule"],
    }))


if __name__ == "__main__":
    main()
