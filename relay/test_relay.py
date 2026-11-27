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

        await kid_ws.send_str(json.dumps({"evt": "hello", "device": "test-model"}))
        await kid_ws.send_str(json.dumps({"evt": "foreground", "pkg": "com.ok.app"}))
        fake_jpeg = b"\xff\xd8\xff\xe0FAKEJPEG"
        await kid_ws.send_bytes(fake_jpeg)
        await asyncio.sleep(0.2)

        # parent connects and should receive latest frame + state snapshot
        parent_ws = await sess.ws_connect(ws_base + f"/parent/view?token={TOKEN}", timeout=5)
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
        await parent_ws.close()

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


def main():
    port = free_port()
    env = dict(os.environ, KIDWATCH_TOKEN=TOKEN, KIDWATCH_PORT=str(port))
    # Remove any pre-existing policy.json so tests are clean
    pf = os.path.join(HERE, "policy.json")
    if os.path.exists(pf): os.remove(pf)
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


if __name__ == "__main__":
    main()
