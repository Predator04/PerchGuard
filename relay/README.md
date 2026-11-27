# PerchGuard relay

A tiny Python server (one file, ~500 lines) that stands between the child's
phone and the parent dashboard. Everything lives on **your** machine — there
is no third-party cloud involved.

## Install in one command

On the computer that will host the relay (a laptop that stays on at home, a
small home server, a Raspberry Pi — anything that runs Python 3):

```bash
./setup.sh
```

The script:

1. Checks that `python3` is installed.
2. Creates a virtualenv under `relay/.venv` and installs `aiohttp` into it
   (falls back to `pip install --user` when `venv` is unavailable).
3. Generates a random access token and writes it to `relay/.env`.
4. Starts the relay. If your user session has `systemd`, it installs a
   `perchguard-relay.service` user unit that auto-restarts on failure and
   survives reboot. Otherwise it launches with `nohup` and writes logs to
   `relay/relay.log`.
5. Prints the **exact URL to paste into the phone** and the dashboard URL.

Re-running `setup.sh` is safe: it reuses the existing token and just restarts
the service.

## What you'll see

At the end you get something like:

```
================= PerchGuard relay is up =================

  Child phone: paste this into the app (step 4 of the setup wizard):

      ws://192.168.1.50:8899/kid/stream

  Parent dashboard (open on your laptop or phone):

      http://192.168.1.50:8899/?token=xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

That's it. Copy the first URL into the phone's PerchGuard app. Open the
second URL in your browser to watch and manage.

## Full four-step parent flow

1. Run `./setup.sh` on the home computer.
2. Install the PerchGuard APK on the child's phone and open it.
3. Tap each of the four numbered rows in order — the app walks you to the
   right system settings for each one. Paste the relay URL into step 4.
4. Tap **Start monitoring**. Accept the one-time system prompt that asks
   to share the screen. You're done.

## Operations

- **View logs:** `tail -f relay/relay.log` (nohup mode) or
  `journalctl --user -u perchguard-relay -f` (systemd mode).
- **Stop:** `systemctl --user stop perchguard-relay` or
  `pkill -f relay.py`.
- **Restart after an edit:** `systemctl --user restart perchguard-relay`
  or re-run `./setup.sh`.
- **Change the token:** delete `relay/.env` and re-run `./setup.sh`. You'll
  then need to copy the new dashboard URL again. The child phone doesn't
  need the token — only the dashboard does.

## Network / remote access

The LAN address printed above only works while the child's phone is on the
same Wi-Fi as the relay. For **off-network viewing** (child phone on cellular,
parent away from home) see [issue #1 — Cloudflare Tunnel][1]. The relay
itself speaks plain HTTP/WebSocket; a tunnel in front of it adds HTTPS and
public reachability without exposing any inbound port.

[1]: https://github.com/Predator04/PerchGuard/issues/1

## Files the relay writes

```
relay/
├── .env            token + host + port (chmod 600)
├── .venv/          isolated Python environment
├── policy.json     saved policy (blocks, limits, bedtime)
├── relay.log       nohup log (systemd mode: use journalctl)
└── run.sh          launcher used by systemd + nohup
```

## Protocol

See the docstring at the top of `relay.py`. Child WS and HTTP API are
authenticated with `KIDWATCH_TOKEN`; browser WS passes `?token=…`.

## Tests

```bash
python3 test_relay.py
```

Spawns the relay on an ephemeral port and exercises every WS and HTTP path.
