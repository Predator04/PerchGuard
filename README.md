# PerchGuard

Self-hosted parental monitoring that respects you and your kid. Live view of
the phone, remote tap/swipe/type, app blocking, daily app limits, total screen
time, and bedtime — all delivered through a relay **you run on your own
hardware**. No third-party cloud, no telemetry, no subscription.

```
       ┌────────────────┐   WebSocket + JPEG    ┌──────────────────┐
       │ Child's Android│ ────────────────────▶ │ relay.py on your │
       │  (PerchGuard)  │ ◀──── commands ────── │  home computer   │
       └────────────────┘                       │  (Python+aiohttp)│
                                                └────────┬─────────┘
                                                         │ HTTP+WebSocket
                                                         ▼
                                                 ┌───────────────┐
                                                 │ Parent browser│
                                                 │   dashboard   │
                                                 └───────────────┘
```

## Four-step setup for a parent

**On your home computer** (laptop, home server, Raspberry Pi — anything that
runs Python 3):

1. Clone the repo and `cd relay && ./setup.sh`. The script installs
   dependencies into a venv, generates a random token, starts the relay, and
   prints the two URLs you need.

**On the child's phone:**

2. Install the PerchGuard APK (build locally with `./gradlew assembleDebug`
   → `app/build/outputs/apk/debug/app-debug.apk`) and open it. You'll see a
   four-row setup checklist.

3. Tap each row in order:
   - **Let PerchGuard tap and type** — opens Accessibility; flip the switch.
   - **Let PerchGuard see app usage** — opens Usage access; flip the switch.
   - **Point to your home relay** — paste the `ws://…/kid/stream` URL from
     step 1.

4. Tap the big **Start monitoring** button. Android shows a one-time "share
   screen" prompt — accept it. Done.

**On your laptop or phone (any browser):** open the `http://…/?token=…` URL
from step 1 to see the child's screen, remote-control it, and set limits.

## What's in this repo

| Path            | What it is                                               |
| --------------- | -------------------------------------------------------- |
| `app/`          | Android app (Java 17, minSdk 24, targetSdk 34, no Kotlin)|
| `relay/`        | Relay server + dashboard + one-command installer         |
| `relay/relay.py`| The whole server, ~500 lines of aiohttp                  |
| `relay/index.html` | Dashboard: live frame, taps, app list, limits, bedtime|
| `relay/test_relay.py` | End-to-end test harness; passes on each release    |

Read [`relay/README.md`](relay/README.md) for details on the installer,
operations, and the protocol.

## Privacy stance

- The child phone connects **only** to the host and port you type into it.
- The relay persists only policy (`policy.json`) and a tail of recent events
  in RAM. No frames are written to disk.
- Dashboard access requires the token the installer generated.
- No analytics, no external calls.

## Limitations today

- LAN-only out of the box. Off-network viewing needs a tunnel in front of the
  relay — see [#1](https://github.com/Predator04/PerchGuard/issues/1).
- No uninstall protection yet — [#6](https://github.com/Predator04/PerchGuard/issues/6).
- JPEG stream, not H.264 — [#3](https://github.com/Predator04/PerchGuard/issues/3).

## Building the Android app

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Running the relay tests

```bash
python3 relay/test_relay.py
```

## License

No license file yet — treat as source-available until one is added.
