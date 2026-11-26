#!/usr/bin/env python3
"""One-time Google Nest sign-in. Adds a "nest" block to your config.json.

    python3 tools/nest-auth.py config.json

Needs the Device Access project ID and an OAuth client ID and secret (see the README).
Standard library only. Nothing is sent anywhere except to Google.
"""
import getpass
import json
import os
import sys
import urllib.parse
import urllib.request

REDIRECT = "https://www.google.com"


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: nest-auth.py config.json")
    path = sys.argv[1]
    with open(path) as f:
        config = json.load(f)

    project = input("Device Access project ID: ").strip()
    client = input("OAuth client ID: ").strip()
    secret = getpass.getpass("OAuth client secret (hidden): ").strip()

    query = urllib.parse.urlencode({
        "redirect_uri": REDIRECT, "access_type": "offline", "prompt": "consent", "client_id": client,
        "response_type": "code", "scope": "https://www.googleapis.com/auth/sdm.service",
    })
    print("\nOpen this, choose your home and thermostat, and allow access:\n")
    print(f"https://nestservices.google.com/partnerconnections/{project}/auth?{query}\n")
    pasted = input("Paste the address of the google.com page you end up on: ").strip()
    codes = urllib.parse.parse_qs(urllib.parse.urlparse(pasted).query).get("code")
    if not codes:
        sys.exit("No code found in that address.")

    form = urllib.parse.urlencode({
        "client_id": client, "client_secret": secret, "code": codes[0],
        "grant_type": "authorization_code", "redirect_uri": REDIRECT,
    }).encode()
    with urllib.request.urlopen("https://www.googleapis.com/oauth2/v4/token", form) as r:
        tokens = json.load(r)

    # Google only finishes linking after the device list has been requested once.
    request = urllib.request.Request(
        f"https://smartdevicemanagement.googleapis.com/v1/enterprises/{project}/devices",
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
    )
    with urllib.request.urlopen(request) as r:
        devices = json.load(r).get("devices", [])
    count = sum(d.get("type") == "sdm.devices.types.THERMOSTAT" for d in devices)

    config["nest"] = {"projectId": project, "clientId": client, "clientSecret": secret,
                      "refreshToken": tokens["refresh_token"], "room": ""}
    with open(path, "w") as f:
        json.dump(config, f, indent=2)
    os.chmod(path, 0o600)
    print(f"\nSaved to {path}. Thermostats found: {count}.")


if __name__ == "__main__":
    main()
