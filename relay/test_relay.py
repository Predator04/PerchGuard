#!/usr/bin/env python3
"""End-to-end test harness for PerchGuard relay.
Spawns relay on an ephemeral port, drives every WS + HTTP path,
asserts expected behavior. Prints a summary.
"""
import asyncio
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error

import aiohttp

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = "testtoken-xyz"


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]; s.close(); return p


def http(method, url, token=None, body=None):
    req = urllib.request.Request(url, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=5) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


async def _wait_location_mirror(parent_ws):
    try:
        for _ in range(6):
            m = await asyncio.wait_for(parent_ws.receive(), timeout=2)
            if m.type == aiohttp.WSMsgType.TEXT:
                data = json.loads(m.data)
                evt = data.get("event") or {}
                if data.get("type") == "event" \
                   and (evt.get("evt") == "location"
                        or evt.get("type") == "location"):
                    return True
    except Exception:
        return False
    return False


async def run_tests(port):
    base = f"http://127.0.0.1:{port}"
    ws_base = f"ws://127.0.0.1:{port}"
    results = []

    def check(name, cond, detail=""):
        results.append((name, cond, detail))
        print(f"  {'OK ' if cond else 'FAIL'} {name} {detail if not cond else ''}")

    # 1. auth off-path returns 401
    s, _ = http("GET", base + "/api/state")
    check("state w/o token -> 401", s == 401, f"got {s}")

    # 2. auth via header
    s, b = http("GET", base + "/api/state", token=TOKEN)
    check("state w/ bearer -> 200", s == 200, f"got {s}")
    state = json.loads(b)
    check("state has policy key", "policy" in state)

    # 3. auth via ?token=
    s, _ = http("GET", base + f"/api/state?token={TOKEN}")
    check("state w/ ?token -> 200", s == 200, f"got {s}")

    # 4. wrong token
    s, _ = http("GET", base + "/api/state", token="wrong")
    check("state w/ wrong token -> 401", s == 401, f"got {s}")

    # 5. policy set + persist
    policy = {"blocked": ["com.evil.app"], "limits": {"com.ok.app": 30},
              "total_minutes": 120,
              "bedtime": {"start": "22:00", "end": "07:00", "enabled": True}}
    s, b = http("POST", base + "/api/policy", token=TOKEN, body=policy)
    check("policy set -> 200", s == 200, f"got {s} {b[:120]}")
    s, b = http("GET", base + "/api/state", token=TOKEN)
    p = json.loads(b).get("policy", {})
    check("policy persisted-blocked", p.get("blocked") == ["com.evil.app"])
    check("policy persisted-limit",   p.get("limits") == {"com.ok.app": 30})
    check("policy persisted-total",   p.get("total_minutes") == 120)
    check("policy persisted-bedtime", p.get("bedtime", {}).get("enabled") is True)

    # 6. bad json
    s, _ = http("POST", base + "/api/policy", token=TOKEN, body=None)
    check("policy no-body -> 400", s == 400, f"got {s}")

    # 7. bad field shapes should not crash
    s, _ = http("POST", base + "/api/action/tap", token=TOKEN, body={})
    check("tap missing fields -> 400", s == 400, f"got {s}")
    s, _ = http("POST", base + "/api/action/tap", token=TOKEN, body={"x": "nope", "y": 5})
    check("tap bad x -> 400", s == 400, f"got {s}")

    # 8. frame 204 when no frame
    s, _ = http("GET", base + "/api/frame.jpg", token=TOKEN)
    check("frame empty -> 204", s == 204, f"got {s}")

    # 9. simulate kid client: connect, send frame + events, parent receives
    async with aiohttp.ClientSession() as sess:
        headers = {"Authorization": "Bearer " + TOKEN}
        kid_ws = await sess.ws_connect(ws_base + "/kid/stream", headers=headers, timeout=5)
        # consume policy snapshot
        policy_msg = await asyncio.wait_for(kid_ws.receive(), timeout=2)
        obj = json.loads(policy_msg.data)
        check("kid receives policy on connect", obj.get("cmd") == "policy"
              and obj.get("blocked") == ["com.evil.app"])
        # With no parent connected, the relay should immediately tell the
        # child to idle its capture pipeline (battery win).
        off_msg = await asyncio.wait_for(kid_ws.receive(), timeout=2)
        off_obj = json.loads(off_msg.data)
        check("kid receives stream_off on lone connect",
              off_obj.get("cmd") == "stream_off")

        await kid_ws.send_str(json.dumps({"evt": "hello", "device": "test-model"}))
        await kid_ws.send_str(json.dumps({"evt": "foreground", "pkg": "com.ok.app"}))
        fake_jpeg = b"\xff\xd8\xff\xe0FAKEJPEG"
        await kid_ws.send_bytes(fake_jpeg)
        await asyncio.sleep(0.2)

        # parent connects and should receive latest frame + state snapshot
        parent_ws = await sess.ws_connect(ws_base + f"/parent/view?token={TOKEN}", timeout=5)
        # First-parent-connect should fire a stream_on toward the kid.
        got_stream_on = False
        for _ in range(4):
            m = await asyncio.wait_for(kid_ws.receive(), timeout=2)
            if m.type == aiohttp.WSMsgType.TEXT and '"stream_on"' in m.data:
                got_stream_on = True
                break
        check("parent connect triggers stream_on", got_stream_on)

        got_frame, got_state = False, False
        for _ in range(4):
            m = await asyncio.wait_for(parent_ws.receive(), timeout=2)
            if m.type == aiohttp.WSMsgType.BINARY:
                got_frame = got_frame or (m.data == fake_jpeg)
            elif m.type == aiohttp.WSMsgType.TEXT:
                data = json.loads(m.data)
                if data.get("type") == "state":
                    got_state = (data["state"]["foreground"] == "com.ok.app"
                                 and data["state"]["kid_connected"] is True)
            if got_frame and got_state: break
        check("parent receives latest frame", got_frame)
        check("parent receives state w/ foreground+kid_connected", got_state)

        # kid pushes another frame — parent should get it live
        new_jpeg = b"\xff\xd8\xff\xe0LIVE2"
        await kid_ws.send_bytes(new_jpeg)
        m = await asyncio.wait_for(parent_ws.receive(), timeout=2)
        while m.type == aiohttp.WSMsgType.TEXT:
            m = await asyncio.wait_for(parent_ws.receive(), timeout=2)
        check("parent receives live frame", m.type == aiohttp.WSMsgType.BINARY
              and m.data == new_jpeg)

        # 10. screenshot: parent asks, kid will auto-push a frame on cmd
        async def kid_responder():
            try:
                m = await asyncio.wait_for(kid_ws.receive(), timeout=4)
                if m.type == aiohttp.WSMsgType.TEXT and "screenshot" in m.data:
                    await kid_ws.send_bytes(b"\xff\xd8\xff\xe0SHOT")
            except Exception:
                pass

        resp_task = asyncio.create_task(kid_responder())
        loop = asyncio.get_running_loop()
        got = await loop.run_in_executor(None, lambda: http("POST",
            base + "/api/action/screenshot", token=TOKEN))
        await resp_task
        check("screenshot returns 200", got[0] == 200, f"got {got[0]}")
        check("screenshot returns jpeg bytes", b"SHOT" in got[1])

        # 11. action/tap flows to kid
        async def wait_tap():
            try:
                for _ in range(5):
                    m = await asyncio.wait_for(kid_ws.receive(), timeout=2)
                    if m.type == aiohttp.WSMsgType.TEXT and '"tap"' in m.data:
                        return json.loads(m.data)
            except Exception:
                return None
        waiter = asyncio.create_task(wait_tap())
        got = await loop.run_in_executor(None, lambda: http("POST",
            base + "/api/action/tap", token=TOKEN, body={"x": 100, "y": 200}))
        check("tap -> 200", got[0] == 200, f"got {got[0]}")
        cmd = await waiter
        check("kid received tap", cmd and cmd.get("x") == 100 and cmd.get("y") == 200)

        # 12. block flow updates policy and sends to kid
        async def wait_block():
            try:
                for _ in range(5):
                    m = await asyncio.wait_for(kid_ws.receive(), timeout=2)
                    if m.type == aiohttp.WSMsgType.TEXT and '"block"' in m.data:
                        return json.loads(m.data)
            except Exception:
                return None
        waiter = asyncio.create_task(wait_block())
        got = await loop.run_in_executor(None, lambda: http("POST",
            base + "/api/action/block", token=TOKEN,
            body={"package": "com.added.app", "block": True}))
        check("block -> 200", got[0] == 200, f"got {got[0]}")
        cmd = await waiter
        check("kid received block cmd", cmd and cmd.get("package") == "com.added.app")

        s, b = http("GET", base + "/api/state", token=TOKEN)
        st = json.loads(b)
        check("block appears in policy", "com.added.app" in st["policy"].get("blocked", []))

        # 12a. kid pushes a location event — relay stores it + shows it in state
        loc_evt = {"type": "location", "lat": 37.7749, "lng": -122.4194,
                   "accuracy": 12.0, "ts": time.time()}
        await kid_ws.send_str(json.dumps(loc_evt))
        await asyncio.sleep(0.2)
        s, b = http("GET", base + "/api/state", token=TOKEN)
        st = json.loads(b)
        loc = st.get("location") or {}
        check("state carries location", abs(loc.get("lat", 0) - 37.7749) < 1e-6
              and abs(loc.get("lng", 0) - (-122.4194)) < 1e-6
              and loc.get("accuracy") == 12.0)

        # 12a.1 — bad lat/lng is rejected
        await kid_ws.send_str(json.dumps({"type": "location", "lat": "nope", "lng": 1}))
        await asyncio.sleep(0.2)
        s, b = http("GET", base + "/api/state", token=TOKEN)
        loc2 = json.loads(b).get("location") or {}
        check("bad location ignored, previous retained",
              abs(loc2.get("lat", 0) - 37.7749) < 1e-6)

        # 12a.2 — HTTP POST /api/location updates + mirrors to parent WS
        got_mirror = asyncio.create_task(_wait_location_mirror(parent_ws))
        got = await loop.run_in_executor(None, lambda: http("POST",
            base + "/api/location", token=TOKEN,
            body={"lat": 40.7128, "lng": -74.0060, "accuracy": 7.5}))
        check("POST /api/location -> 200", got[0] == 200, f"got {got[0]}")
        mirrored = await got_mirror
        check("parent WS sees location event mirror", mirrored)

        # 12a.3 — GET /map renders Leaflet HTML (contains the CDN)
        s, b = http("GET", base + "/map?token=" + TOKEN)
        check("/map -> 200", s == 200, f"got {s}")
        check("/map carries Leaflet", b"leaflet" in b.lower())

        # 12a.4 — /api/action/update forwards {"cmd":"update"} to the child
        async def wait_update():
            try:
                for _ in range(8):
                    m = await asyncio.wait_for(kid_ws.receive(), timeout=2)
                    if m.type == aiohttp.WSMsgType.TEXT and '"update"' in m.data:
                        return json.loads(m.data)
            except Exception:
                return None
        waiter = asyncio.create_task(wait_update())
        got = await loop.run_in_executor(None, lambda: http("POST",
            base + "/api/action/update", token=TOKEN, body={}))
        check("update -> 200", got[0] == 200, f"got {got[0]}")
        cmd = await waiter
        check("kid received update cmd", cmd and cmd.get("cmd") == "update")

        # 12a.5 — /api/crash accepts a report without auth and persists it
        crash_payload = {"stack": "java.lang.RuntimeException: boom\n\tat X.y(X.java:1)",
                         "version": 42}
        # NOTE: no token → endpoint must still accept it.
        s, b = http("POST", base + "/api/crash", body=crash_payload)
        check("crash w/o token -> 200", s == 200, f"got {s}")
        crash_log = os.path.join(HERE, "crash.log")
        on_disk = os.path.exists(crash_log)
        check("crash.log on disk", on_disk)
        if on_disk:
            with open(crash_log) as f:
                content = f.read()
            check("crash.log carries stack", "boom" in content and "version=42" in content)

        # 12b. /api/apk POST (raw bytes) stores + tells child via install_apk
        async def wait_install():
            try:
                for _ in range(8):
                    m = await asyncio.wait_for(kid_ws.receive(), timeout=2)
                    if m.type == aiohttp.WSMsgType.TEXT and '"install_apk"' in m.data:
                        return json.loads(m.data)
            except Exception:
                return None
        waiter = asyncio.create_task(wait_install())
        fake_apk = b"PK\x03\x04FAKE-APK-BYTES"
        def _apk_post():
            req = urllib.request.Request(base + "/api/apk", method="POST", data=fake_apk)
            req.add_header("Authorization", "Bearer " + TOKEN)
            req.add_header("Content-Type", "application/vnd.android.package-archive")
            try:
                with urllib.request.urlopen(req, timeout=5) as r:
                    return r.status, r.read()
            except urllib.error.HTTPError as e:
                return e.code, e.read()
        got = await loop.run_in_executor(None, _apk_post)
        check("apk POST -> 200", got[0] == 200, f"got {got[0]}")
        cmd = await waiter
        check("kid received install_apk", cmd and cmd.get("url", "").startswith("http")
              and "/api/apk" in cmd.get("url", ""))

        # 12c. GET /api/apk returns the bytes we posted
        s, b = http("GET", base + "/api/apk", token=TOKEN)
        check("apk GET -> 200", s == 200, f"got {s}")
        check("apk GET returns stored bytes", b == fake_apk, f"got {b[:40]!r}")

        # 12d. GET /api/apk without token -> 401
        s, _ = http("GET", base + "/api/apk")
        check("apk GET w/o token -> 401", s == 401, f"got {s}")

        # 12e. closing the last parent fires stream_off at the kid.
        await parent_ws.close()
        got_stream_off = False
        for _ in range(8):
            try:
                m = await asyncio.wait_for(kid_ws.receive(), timeout=2)
            except asyncio.TimeoutError:
                break
            if m.type == aiohttp.WSMsgType.TEXT and '"stream_off"' in m.data:
                got_stream_off = True
                break
        check("last parent disconnect -> stream_off", got_stream_off)

        # 13. second kid connection supersedes first
        kid_ws2 = await sess.ws_connect(ws_base + "/kid/stream", headers=headers, timeout=5)
        # drain messages on old socket until we see the server-initiated close
        superseded = False
        try:
            for _ in range(10):
                m = await asyncio.wait_for(kid_ws.receive(), timeout=1.5)
                if m.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED,
                              aiohttp.WSMsgType.CLOSING):
                    superseded = True; break
        except (asyncio.TimeoutError, Exception):
            superseded = kid_ws.closed
        check("prior kid superseded", superseded or kid_ws.closed)
        await kid_ws2.close()

    # 14. persistence across restart — verify policy.json present
    pf = os.path.join(HERE, "policy.json")
    exists = os.path.exists(pf)
    check("policy.json on disk", exists)
    if exists:
        with open(pf) as f:
            on_disk = json.load(f)
        check("policy.json contains added block",
              "com.added.app" in on_disk.get("blocked", []))

    failed = [n for n, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed; {len(failed)} failed")
    return len(failed) == 0


def _clean_state_files():
    """Delete every file the relay creates under HERE so the suite leaves no trace."""
    for path in (
        os.path.join(HERE, "policy.json"),
        os.path.join(HERE, "state.json"),
        os.path.join(HERE, "uploads", "app.apk"),
        os.path.join(HERE, "crash.log"),
    ):
        if os.path.exists(path):
            try: os.remove(path)
            except OSError: pass


def main():
    port = free_port()
    env = dict(os.environ, KIDWATCH_TOKEN=TOKEN, KIDWATCH_PORT=str(port))
    _clean_state_files()
    proc = subprocess.Popen([sys.executable, os.path.join(HERE, "relay.py")],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        # wait for bind
        for _ in range(40):
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                    break
            except OSError:
                time.sleep(0.1)
        ok = asyncio.run(run_tests(port))
        sys.exit(0 if ok else 1)
    finally:
        proc.terminate()
        try: proc.wait(timeout=3)
        except subprocess.TimeoutExpired: proc.kill()
        _clean_state_files()


if __name__ == "__main__":
    main()
