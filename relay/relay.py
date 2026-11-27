#!/usr/bin/env python3
"""PerchGuard relay — bidirectional bridge between child device and parent dashboard.

Transports:
  WS   /kid/stream     child: JPEG frames (binary), events (text JSON), command acks
  WS   /parent/view    parent: live JPEG frames + mirrored child events
  GET  /               parent dashboard (static)
  GET  /api/state      current policy + latest events + device status
  POST /api/policy     set full policy (persists to policy.json, pushes to child)
  POST /api/action/lock              send lock_now
  POST /api/action/screenshot        request a single frame; returns JPEG
  GET  /api/frame.jpg                latest known frame (or 204)
  GET  /api/usage                    latest usage_daily event
  POST /api/action/tap    body: {"x":I,"y":I}         forwards tap to child
  POST /api/action/swipe  body: {"x1":I,"y1":I,"x2":I,"y2":I,"dur":I}
  POST /api/action/key    body: {"code":"home|back|recents|notifications|quick_settings|lock"}
  POST /api/action/type   body: {"text":"..."}
  POST /api/action/block  body: {"package":"...","block":true|false}
  POST /api/action/bedtime body: {"start":"HH:MM","end":"HH:MM","enabled":true}

Auth: if KIDWATCH_TOKEN is set, all routes require it.
  child WS   : Authorization: Bearer <token>
  browser WS : ?token=<token>
  HTTP       : Authorization: Bearer <token>  or  ?token=<token>

Child→relay events  :  {"evt":"hello"|"foreground"|"usage_daily"|"block_triggered"|"battery"|"bedtime"|"ack", ...}
Relay→child commands:  see POST /api/action/* above; raw JSON pushed verbatim over /kid/stream.

Run: KIDWATCH_TOKEN=... python3 relay.py   (binds 0.0.0.0:8899)
"""
import asyncio
import json
import os
import time
from aiohttp import web, WSMsgType

TOKEN = os.environ.get("KIDWATCH_TOKEN", "")
HERE = os.path.dirname(os.path.abspath(__file__))
POLICY_PATH = os.path.join(HERE, "policy.json")

STATE = {
    "frame": bytearray(),
    "frame_ts": 0.0,
    "kid_ws": None,          # single child socket
    "parents": set(),        # parent browser sockets
    "device": {},            # last hello
    "foreground": None,      # last foreground pkg
    "usage": {},             # last usage_daily
    "battery": {},           # last battery event
    "bedtime_active": False,
    "events": [],            # tail of recent events (max 200)
    "screenshot_waiters": [],  # list[Future], resolved when next frame arrives
}


def load_policy():
    try:
        with open(POLICY_PATH) as f:
            return json.load(f)
    except Exception:
        return {"blocked": [], "limits": {}, "total_minutes": 0,
                "bedtime": {"start": "", "end": "", "enabled": False}}


def save_policy(p):
    try:
        with open(POLICY_PATH, "w") as f:
            json.dump(p, f, indent=2)
    except Exception as e:
        print(f"[relay] save_policy failed: {e}")


POLICY = load_policy()


def authorized(request):
    if not TOKEN:
        return True
    tok = request.query.get("token", "")
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        tok = auth[7:]
    return tok == TOKEN


async def send_to_kid(obj) -> bool:
    ws = STATE["kid_ws"]
    if ws is None or ws.closed:
        return False
    await ws.send_str(json.dumps(obj))
    return True


def record_event(evt):
    STATE["events"].append({"t": time.time(), **evt})
    STATE["events"] = STATE["events"][-200:]


async def broadcast_parents_text(obj):
    msg = json.dumps(obj)
    for p in list(STATE["parents"]):
        try:
            await p.send_str(msg)
        except Exception:
            STATE["parents"].discard(p)


async def index(request):
    return web.FileResponse(os.path.join(HERE, "index.html"))


async def kid_ws_handler(request):
    if not authorized(request):
        return web.Response(status=401, text="unauthorized")
    ws = web.WebSocketResponse(max_msg_size=8_000_000)
    await ws.prepare(request)
    STATE["kid_ws"] = ws
    print("[relay] kid connected")
    # Re-push policy snapshot on every reconnect so the child is always in sync.
    await ws.send_str(json.dumps({"cmd": "policy", **POLICY}))
    try:
        async for msg in ws:
            if msg.type == WSMsgType.BINARY:
                STATE["frame"] = bytearray(msg.data)
                STATE["frame_ts"] = time.time()
                # Resolve any pending screenshot waiters with this frame.
                for fut in STATE["screenshot_waiters"]:
                    if not fut.done():
                        fut.set_result(bytes(msg.data))
                STATE["screenshot_waiters"].clear()
                for p in list(STATE["parents"]):
                    try:
                        await p.send_bytes(msg.data)
                    except Exception:
                        STATE["parents"].discard(p)
            elif msg.type == WSMsgType.TEXT:
                try:
                    evt = json.loads(msg.data)
                except Exception:
                    print("[relay] kid non-json:", msg.data[:120])
                    continue
                kind = evt.get("evt")
                if kind == "hello":
                    STATE["device"] = evt
                elif kind == "foreground":
                    STATE["foreground"] = evt.get("pkg")
                elif kind == "usage_daily":
                    STATE["usage"] = evt
                elif kind == "battery":
                    STATE["battery"] = evt
                elif kind == "bedtime":
                    STATE["bedtime_active"] = bool(evt.get("active"))
                record_event(evt)
                await broadcast_parents_text({"type": "event", "event": evt})
    finally:
        if STATE["kid_ws"] is ws:
            STATE["kid_ws"] = None
        print("[relay] kid disconnected")
    return ws


async def parent_ws_handler(request):
    if not authorized(request):
        return web.Response(status=401, text="unauthorized")
    ws = web.WebSocketResponse(max_msg_size=8_000_000)
    await ws.prepare(request)
    STATE["parents"].add(ws)
    print(f"[relay] parent connected ({len(STATE['parents'])} total)")
    try:
        if STATE["frame"]:
            await ws.send_bytes(bytes(STATE["frame"]))
        await ws.send_str(json.dumps({"type": "state", "state": _state_dict()}))
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    obj = json.loads(msg.data)
                except Exception:
                    continue
                # A browser may push commands directly through the WS too.
                if "cmd" in obj:
                    await send_to_kid(obj)
    finally:
        STATE["parents"].discard(ws)
        print("[relay] parent disconnected")
    return ws


def _state_dict():
    return {
        "device": STATE["device"],
        "foreground": STATE["foreground"],
        "usage": STATE["usage"],
        "battery": STATE["battery"],
        "bedtime_active": STATE["bedtime_active"],
        "kid_connected": STATE["kid_ws"] is not None and not STATE["kid_ws"].closed,
        "frame_ts": STATE["frame_ts"],
        "policy": POLICY,
        "events": STATE["events"][-40:],
    }


# ---------- HTTP API ----------

def api_auth(handler):
    async def wrapper(request):
        if not authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        return await handler(request)
    return wrapper


@api_auth
async def api_state(request):
    return web.json_response(_state_dict())


@api_auth
async def api_policy(request):
    global POLICY
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad_json"}, status=400)
    POLICY = {
        "blocked": list(body.get("blocked", POLICY.get("blocked", []))),
        "limits":  dict(body.get("limits",  POLICY.get("limits", {}))),
        "total_minutes": int(body.get("total_minutes", POLICY.get("total_minutes", 0))),
        "bedtime": dict(body.get("bedtime", POLICY.get("bedtime", {}))),
    }
    save_policy(POLICY)
    await send_to_kid({"cmd": "policy", **POLICY})
    return web.json_response({"ok": True, "policy": POLICY})


@api_auth
async def api_frame(request):
    if not STATE["frame"]:
        return web.Response(status=204)
    return web.Response(body=bytes(STATE["frame"]),
                        content_type="image/jpeg",
                        headers={"Cache-Control": "no-store"})


@api_auth
async def api_usage(request):
    return web.json_response(STATE["usage"])


@api_auth
async def api_lock(request):
    ok = await send_to_kid({"cmd": "lock_now", "lock_now": True})
    return web.json_response({"ok": ok})


@api_auth
async def api_screenshot(request):
    fut = asyncio.get_event_loop().create_future()
    STATE["screenshot_waiters"].append(fut)
    ok = await send_to_kid({"cmd": "screenshot"})
    if not ok:
        return web.json_response({"error": "child_offline"}, status=503)
    try:
        jpeg = await asyncio.wait_for(fut, timeout=5.0)
    except asyncio.TimeoutError:
        if STATE["frame"]:
            jpeg = bytes(STATE["frame"])
        else:
            return web.json_response({"error": "timeout"}, status=504)
    return web.Response(body=jpeg, content_type="image/jpeg",
                        headers={"Cache-Control": "no-store"})


def _simple_forward(cmd_builder):
    @api_auth
    async def inner(request):
        try:
            body = await request.json() if request.can_read_body else {}
        except Exception:
            body = {}
        cmd = cmd_builder(body)
        ok = await send_to_kid(cmd)
        return web.json_response({"ok": ok, "sent": cmd})
    return inner


api_tap    = _simple_forward(lambda b: {"cmd": "tap",   "x": int(b["x"]), "y": int(b["y"])})
api_swipe  = _simple_forward(lambda b: {"cmd": "swipe", "x1": int(b["x1"]), "y1": int(b["y1"]),
                                        "x2": int(b["x2"]), "y2": int(b["y2"]),
                                        "dur": int(b.get("dur", 200))})
api_key    = _simple_forward(lambda b: {"cmd": "key",   "code": str(b["code"])})
api_type   = _simple_forward(lambda b: {"cmd": "type",  "text": str(b["text"])})


@api_auth
async def api_block(request):
    global POLICY
    body = await request.json()
    pkg = str(body["package"])
    block = bool(body.get("block", True))
    bl = set(POLICY.get("blocked", []))
    if block: bl.add(pkg)
    else: bl.discard(pkg)
    POLICY["blocked"] = sorted(bl)
    save_policy(POLICY)
    await send_to_kid({"cmd": "block", "package": pkg, "block": block})
    return web.json_response({"ok": True, "blocked": POLICY["blocked"]})


@api_auth
async def api_bedtime(request):
    global POLICY
    body = await request.json()
    bt = {
        "start": str(body.get("start", "")),
        "end": str(body.get("end", "")),
        "enabled": bool(body.get("enabled", False)),
    }
    POLICY["bedtime"] = bt
    save_policy(POLICY)
    await send_to_kid({"cmd": "bedtime", **bt})
    return web.json_response({"ok": True, "bedtime": bt})


@api_auth
async def api_limits(request):
    global POLICY
    body = await request.json()
    POLICY["limits"] = {k: int(v.get("daily_minutes", 0)) if isinstance(v, dict) else int(v)
                        for k, v in body.get("packages", {}).items()}
    if "total_minutes" in body:
        POLICY["total_minutes"] = int(body["total_minutes"])
    save_policy(POLICY)
    await send_to_kid({"cmd": "set_limits",
                       "packages": {k: {"daily_minutes": v} for k, v in POLICY["limits"].items()},
                       "total_minutes": POLICY["total_minutes"]})
    return web.json_response({"ok": True, "limits": POLICY["limits"],
                              "total_minutes": POLICY["total_minutes"]})


app = web.Application()
app.router.add_get("/", index)
app.router.add_get("/kid/stream", kid_ws_handler)
app.router.add_get("/parent/view", parent_ws_handler)
app.router.add_get("/api/state", api_state)
app.router.add_post("/api/policy", api_policy)
app.router.add_get("/api/frame.jpg", api_frame)
app.router.add_get("/api/usage", api_usage)
app.router.add_post("/api/action/lock", api_lock)
app.router.add_post("/api/action/screenshot", api_screenshot)
app.router.add_post("/api/action/tap", api_tap)
app.router.add_post("/api/action/swipe", api_swipe)
app.router.add_post("/api/action/key", api_key)
app.router.add_post("/api/action/type", api_type)
app.router.add_post("/api/action/block", api_block)
app.router.add_post("/api/action/bedtime", api_bedtime)
app.router.add_post("/api/action/limits", api_limits)

if __name__ == "__main__":
    print("[relay] PerchGuard relay on 0.0.0.0:8899 (auth %s)"
          % ("ON" if TOKEN else "OFF"))
    web.run_app(app, host="0.0.0.0", port=8899)
