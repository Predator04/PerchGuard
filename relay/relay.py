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
  POST /api/apk           body: raw APK bytes (application/vnd.android.package-archive)
                           OR  JSON {"url":"https://..."} — relay prefetches and tells child
  GET  /api/apk           serves the stored APK (child downloads this after install_apk cmd)
  POST /api/location      body: {"lat":F,"lng":F,"accuracy":F,"ts":F}
  GET  /api/location      latest location (or {} if never reported)
  GET  /map               self-contained Leaflet page (for the parent WebView)

Auth: if KIDWATCH_TOKEN is set, all routes require it.
  child WS   : Authorization: Bearer <token>
  browser WS : ?token=<token>
  HTTP       : Authorization: Bearer <token>  or  ?token=<token>

Child→relay events  :  {"evt":"hello"|"foreground"|"usage_daily"|"block_triggered"|"battery"|"bedtime"|"ack", ...}
Relay→child commands:  see POST /api/action/* above; raw JSON pushed verbatim over /kid/stream.

Run: KIDWATCH_TOKEN=... python3 relay.py   (binds 0.0.0.0:8899)
"""
import asyncio
import hmac
import json
import os
import tempfile
import time
from aiohttp import web, WSMsgType

TOKEN = os.environ.get("KIDWATCH_TOKEN", "")
HERE = os.path.dirname(os.path.abspath(__file__))
POLICY_PATH = os.path.join(HERE, "policy.json")
STATE_PATH  = os.path.join(HERE, "state.json")
POLICY_LOCK = asyncio.Lock()

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
    "last_seen_ts": 0.0,     # last time we received anything from the child
    "events": [],            # tail of recent events (max 200)
    "screenshot_waiters": [],  # list[Future], resolved when next frame arrives
    "location": None,        # {"lat":..,"lng":..,"accuracy":..,"ts":..}
}


def load_state():
    try:
        with open(STATE_PATH) as f:
            data = json.load(f)
        loc = data.get("location")
        if isinstance(loc, dict) and "lat" in loc and "lng" in loc:
            STATE["location"] = loc
    except Exception:
        pass


def save_state_async():
    # best-effort, fire-and-forget disk write of persistent portions of STATE.
    try:
        payload = {"location": STATE["location"]}
        fd, tmp = tempfile.mkstemp(prefix=".state.", suffix=".json", dir=HERE)
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(payload, f, indent=2)
            os.replace(tmp, STATE_PATH)
        except Exception:
            try: os.unlink(tmp)
            except OSError: pass
            raise
    except Exception as e:
        print(f"[relay] save_state failed: {e}")


def load_policy():
    try:
        with open(POLICY_PATH) as f:
            return json.load(f)
    except Exception:
        return {"blocked": [], "limits": {}, "total_minutes": 0,
                "bedtime": {"start": "", "end": "", "enabled": False}}


def save_policy(p):
    try:
        fd, tmp = tempfile.mkstemp(prefix=".policy.", suffix=".json", dir=HERE)
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(p, f, indent=2)
            os.replace(tmp, POLICY_PATH)
        except Exception:
            try: os.unlink(tmp)
            except OSError: pass
            raise
    except Exception as e:
        print(f"[relay] save_policy failed: {e}")


def _sanitize_location(body):
    """Pull a well-formed {lat,lng,accuracy,ts} out of an arbitrary dict, or None."""
    try:
        lat = float(body.get("lat"))
        lng = float(body.get("lng"))
    except (TypeError, ValueError):
        return None
    if not (-90.0 <= lat <= 90.0) or not (-180.0 <= lng <= 180.0):
        return None
    try:
        acc = float(body.get("accuracy", 0.0) or 0.0)
    except (TypeError, ValueError):
        acc = 0.0
    try:
        ts = float(body.get("ts", 0.0) or 0.0)
    except (TypeError, ValueError):
        ts = 0.0
    if ts <= 0:
        ts = time.time()
    return {"lat": lat, "lng": lng, "accuracy": max(0.0, acc), "ts": ts}


def _sanitize_policy(body, current):
    blocked_in = body.get("blocked", current.get("blocked", []))
    limits_in  = body.get("limits",  current.get("limits", {}))
    bedtime_in = body.get("bedtime", current.get("bedtime", {}))
    out = {
        "blocked": sorted({str(x) for x in blocked_in if isinstance(x, (str, int))}),
        "limits":  {str(k): max(0, int(v)) for k, v in limits_in.items()
                    if isinstance(k, str) and isinstance(v, (int, float))},
        "total_minutes": max(0, int(body.get("total_minutes",
                                             current.get("total_minutes", 0)) or 0)),
        "bedtime": {
            "start":   str(bedtime_in.get("start", "") or ""),
            "end":     str(bedtime_in.get("end", "") or ""),
            "enabled": bool(bedtime_in.get("enabled", False)),
        },
    }
    return out


POLICY = load_policy()
load_state()


def authorized(request):
    if not TOKEN:
        return True
    tok = ""
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        tok = auth[7:]
    else:
        tok = request.query.get("token", "")
    return hmac.compare_digest(tok, TOKEN)


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


async def app_apk(request):
    return web.FileResponse(os.path.join(HERE, "app.apk"))


UPLOADS_DIR = os.path.join(HERE, "uploads")
UPLOADED_APK = os.path.join(UPLOADS_DIR, "app.apk")
# Safety ceiling: refuse APK uploads larger than this. Easily tuned, keeps us
# from OOMing on a malicious / mistaken giant upload.
MAX_APK_BYTES = 200 * 1024 * 1024


def _public_base(request):
    # Prefer the Cloudflare-tunnelled forwarded host so the child uses the
    # outside URL, not the relay's private 0.0.0.0:8899 bind.
    host = request.headers.get("X-Forwarded-Host") or request.host
    proto = request.headers.get("X-Forwarded-Proto") \
            or ("https" if request.secure else "http")
    return f"{proto}://{host}"


async def _fetch_to_local(url):
    """Best-effort: download a remote APK into our local uploads folder so a
    later child reconnect can also use /api/apk."""
    import aiohttp as _aio
    os.makedirs(UPLOADS_DIR, exist_ok=True)
    tmp = UPLOADED_APK + ".part"
    total = 0
    async with _aio.ClientSession() as sess:
        async with sess.get(url, timeout=_aio.ClientTimeout(total=120)) as resp:
            if resp.status != 200:
                raise RuntimeError(f"HTTP {resp.status} from {url}")
            with open(tmp, "wb") as f:
                async for chunk in resp.content.iter_chunked(64 * 1024):
                    total += len(chunk)
                    if total > MAX_APK_BYTES:
                        raise RuntimeError("remote apk too large")
                    f.write(chunk)
    os.replace(tmp, UPLOADED_APK)


async def kid_ws_handler(request):
    if not authorized(request):
        return web.Response(status=401, text="unauthorized")
    ws = web.WebSocketResponse(max_msg_size=8_000_000, heartbeat=25)
    await ws.prepare(request)
    prev = STATE["kid_ws"]
    if prev is not None and prev is not ws and not prev.closed:
        try: await prev.close(code=1000, message=b"superseded")
        except Exception: pass
    STATE["kid_ws"] = ws
    print("[relay] kid connected")
    # Re-push policy snapshot on every reconnect so the child is always in sync.
    async with POLICY_LOCK:
        snap = dict(POLICY)
    await ws.send_str(json.dumps({"cmd": "policy", **snap}))
    # If a parent is already watching when the child reconnects, un-pause the
    # capture pipeline so live view resumes without manual action.
    if STATE["parents"]:
        await ws.send_str(json.dumps({"cmd": "stream_on"}))
    else:
        await ws.send_str(json.dumps({"cmd": "stream_off"}))
    try:
        async for msg in ws:
            STATE["last_seen_ts"] = time.time()
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
                # Accept either {"evt":"..."} (child events) or {"type":"..."}
                # (spec-shaped location payloads).
                kind = evt.get("evt") or evt.get("type")
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
                elif kind == "location":
                    loc = _sanitize_location(evt)
                    if loc:
                        STATE["location"] = loc
                        save_state_async()
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
    ws = web.WebSocketResponse(max_msg_size=8_000_000, heartbeat=25)
    await ws.prepare(request)
    was_empty = len(STATE["parents"]) == 0
    STATE["parents"].add(ws)
    print(f"[relay] parent connected ({len(STATE['parents'])} total)")
    if was_empty:
        # First watcher online — wake the child's capture pipeline.
        await send_to_kid({"cmd": "stream_on"})
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
        if not STATE["parents"]:
            # No one is watching — tell the child to pause capture.
            await send_to_kid({"cmd": "stream_off"})
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
        "last_seen_ts": STATE["last_seen_ts"],
        "policy": POLICY,
        "events": STATE["events"][-40:],
        "parents": len(STATE["parents"]),
        "location": STATE["location"],
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
    if not isinstance(body, dict):
        return web.json_response({"error": "bad_shape"}, status=400)
    async with POLICY_LOCK:
        POLICY = _sanitize_policy(body, POLICY)
        save_policy(POLICY)
        snapshot = dict(POLICY)
    await send_to_kid({"cmd": "policy", **snapshot})
    return web.json_response({"ok": True, "policy": snapshot})


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
    loop = asyncio.get_running_loop()
    fut = loop.create_future()
    STATE["screenshot_waiters"].append(fut)
    try:
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
    finally:
        try: STATE["screenshot_waiters"].remove(fut)
        except ValueError: pass
    return web.Response(body=jpeg, content_type="image/jpeg",
                        headers={"Cache-Control": "no-store"})


async def _read_body(request):
    if not request.can_read_body:
        return {}
    try:
        body = await request.json()
    except Exception:
        return None
    return body if isinstance(body, dict) else None


def _require(body, *keys):
    for k in keys:
        if k not in body:
            return k
    return None


def _simple_forward(cmd_builder, required=()):
    @api_auth
    async def inner(request):
        body = await _read_body(request)
        if body is None:
            return web.json_response({"error": "bad_json"}, status=400)
        missing = _require(body, *required)
        if missing:
            return web.json_response({"error": "missing_field", "field": missing}, status=400)
        try:
            cmd = cmd_builder(body)
        except (KeyError, ValueError, TypeError) as e:
            return web.json_response({"error": "bad_field", "detail": str(e)}, status=400)
        ok = await send_to_kid(cmd)
        return web.json_response({"ok": ok, "sent": cmd})
    return inner


def _clamp_coord(v):
    v = int(v)
    if v < 0 or v > 100_000:
        raise ValueError("coord_out_of_range")
    return v


api_tap   = _simple_forward(
    lambda b: {"cmd": "tap", "x": _clamp_coord(b["x"]), "y": _clamp_coord(b["y"])},
    required=("x", "y"))
api_swipe = _simple_forward(
    lambda b: {"cmd": "swipe",
               "x1": _clamp_coord(b["x1"]), "y1": _clamp_coord(b["y1"]),
               "x2": _clamp_coord(b["x2"]), "y2": _clamp_coord(b["y2"]),
               "dur": max(1, min(60_000, int(b.get("dur", 200))))},
    required=("x1", "y1", "x2", "y2"))
api_key   = _simple_forward(lambda b: {"cmd": "key",  "code": str(b["code"])}, required=("code",))
api_type  = _simple_forward(lambda b: {"cmd": "type", "text": str(b["text"])}, required=("text",))


@api_auth
async def api_block(request):
    global POLICY
    body = await _read_body(request)
    if body is None:
        return web.json_response({"error": "bad_json"}, status=400)
    if "package" not in body:
        return web.json_response({"error": "missing_field", "field": "package"}, status=400)
    pkg = str(body["package"])
    block = bool(body.get("block", True))
    async with POLICY_LOCK:
        bl = set(POLICY.get("blocked", []))
        if block: bl.add(pkg)
        else: bl.discard(pkg)
        POLICY["blocked"] = sorted(bl)
        save_policy(POLICY)
        blocked_list = list(POLICY["blocked"])
    await send_to_kid({"cmd": "block", "package": pkg, "block": block})
    return web.json_response({"ok": True, "blocked": blocked_list})


@api_auth
async def api_bedtime(request):
    global POLICY
    body = await _read_body(request)
    if body is None:
        return web.json_response({"error": "bad_json"}, status=400)
    bt = {
        "start": str(body.get("start", "") or ""),
        "end": str(body.get("end", "") or ""),
        "enabled": bool(body.get("enabled", False)),
    }
    async with POLICY_LOCK:
        POLICY["bedtime"] = bt
        save_policy(POLICY)
    await send_to_kid({"cmd": "bedtime", **bt})
    return web.json_response({"ok": True, "bedtime": bt})


@api_auth
async def api_apk_get(request):
    if not os.path.exists(UPLOADED_APK):
        return web.json_response({"error": "no_apk"}, status=404)
    return web.FileResponse(UPLOADED_APK, headers={
        "Content-Type": "application/vnd.android.package-archive",
        "Cache-Control": "no-store",
    })


@api_auth
async def api_apk_post(request):
    os.makedirs(UPLOADS_DIR, exist_ok=True)
    ct = (request.content_type or "").lower()
    source_url = None
    if ct.startswith("application/json"):
        try:
            body = await request.json()
        except Exception:
            return web.json_response({"error": "bad_json"}, status=400)
        if not isinstance(body, dict):
            return web.json_response({"error": "bad_shape"}, status=400)
        src = body.get("url")
        if not isinstance(src, str) or not src:
            return web.json_response({"error": "missing_field",
                                      "field": "url"}, status=400)
        source_url = src
        try:
            await _fetch_to_local(src)
        except Exception as e:
            print(f"[relay] prefetch of {src} failed: {e}")
    else:
        clen = request.content_length or 0
        if clen > MAX_APK_BYTES:
            return web.json_response({"error": "too_large",
                                      "limit": MAX_APK_BYTES}, status=413)
        total = 0
        tmp = UPLOADED_APK + ".part"
        try:
            with open(tmp, "wb") as f:
                async for chunk in request.content.iter_chunked(64 * 1024):
                    total += len(chunk)
                    if total > MAX_APK_BYTES:
                        f.close()
                        try: os.unlink(tmp)
                        except OSError: pass
                        return web.json_response({"error": "too_large",
                                                  "limit": MAX_APK_BYTES}, status=413)
                    f.write(chunk)
            os.replace(tmp, UPLOADED_APK)
        except Exception as e:
            try: os.unlink(tmp)
            except OSError: pass
            return web.json_response({"error": "write_failed",
                                      "detail": str(e)}, status=500)

    download_url = source_url or (
        _public_base(request) + "/api/apk?token=" + TOKEN)
    ok = await send_to_kid({"cmd": "install_apk", "url": download_url})
    return web.json_response({"ok": True,
                              "stored": os.path.exists(UPLOADED_APK),
                              "url": download_url,
                              "child_notified": ok})


@api_auth
async def api_location_get(request):
    return web.json_response(STATE["location"] or {})


@api_auth
async def api_location_post(request):
    body = await _read_body(request)
    if body is None:
        return web.json_response({"error": "bad_json"}, status=400)
    loc = _sanitize_location(body)
    if not loc:
        return web.json_response({"error": "bad_field", "detail": "lat/lng"}, status=400)
    STATE["location"] = loc
    save_state_async()
    # Mirror to parent dashboards so a map updates in real time, mimicking the
    # child-WS path.
    await broadcast_parents_text({"type": "event",
                                  "event": {"evt": "location", **loc}})
    return web.json_response({"ok": True, "location": loc})


async def map_page(request):
    """Self-contained Leaflet map served for the parent app's WebView.

    The page bakes the bearer token in from the current query so the WebView
    doesn't have to juggle auth headers.
    """
    token = request.query.get("token", "")
    html = f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PerchGuard · Location</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
  html,body,#map {{ margin:0; padding:0; height:100%; background:#0b0d10; color:#eef1f5;
    font-family:system-ui,sans-serif; }}
  #info {{ position:fixed; top:10px; left:10px; z-index:1000; background:rgba(14,19,25,.85);
    border:1px solid #1e242c; padding:8px 12px; border-radius:8px; font-size:13px;
    line-height:1.4; }}
</style></head><body>
<div id="map"></div>
<div id="info">no fix yet</div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const TOKEN = {json.dumps(token)};
const qs = TOKEN ? ("?token=" + encodeURIComponent(TOKEN)) : "";
const info = document.getElementById("info");
const map = L.map("map").setView([0,0], 2);
L.tileLayer("https://{{s}}.tile.openstreetmap.org/{{z}}/{{x}}/{{y}}.png",
  {{maxZoom: 19, attribution: "© OpenStreetMap"}}).addTo(map);
let marker = null, circle = null;
function fmtAgo(ts) {{
  const s = Math.max(0, Math.round(Date.now()/1000 - ts));
  if (s < 60) return s + "s ago";
  if (s < 3600) return Math.round(s/60) + "m ago";
  if (s < 86400) return Math.round(s/3600) + "h ago";
  return Math.round(s/86400) + "d ago";
}}
function paint(loc) {{
  if (!loc || loc.lat == null) {{ info.textContent = "no fix yet"; return; }}
  const ll = [loc.lat, loc.lng];
  if (!marker) {{
    marker = L.marker(ll).addTo(map);
    circle = L.circle(ll, {{radius: Math.max(5, loc.accuracy||0), color:"#2b5bd7",
                            fillOpacity:.12}}).addTo(map);
    map.setView(ll, 16);
  }} else {{
    marker.setLatLng(ll);
    circle.setLatLng(ll).setRadius(Math.max(5, loc.accuracy||0));
  }}
  info.innerHTML = `${{loc.lat.toFixed(5)}}, ${{loc.lng.toFixed(5)}}<br>`
    + `±${{Math.round(loc.accuracy||0)}}m · ${{fmtAgo(loc.ts)}}`;
}}
async function tick() {{
  try {{
    const r = await fetch("/api/state" + qs);
    if (r.ok) {{ const s = await r.json(); paint(s.location); }}
  }} catch (e) {{}}
}}
tick(); setInterval(tick, 10000);
</script></body></html>"""
    return web.Response(text=html, content_type="text/html",
                        headers={"Cache-Control": "no-store"})


@api_auth
async def api_limits(request):
    global POLICY
    body = await _read_body(request)
    if body is None:
        return web.json_response({"error": "bad_json"}, status=400)
    pkgs_in = body.get("packages", {})
    if not isinstance(pkgs_in, dict):
        return web.json_response({"error": "bad_field", "field": "packages"}, status=400)
    try:
        new_limits = {str(k): max(0, int(v.get("daily_minutes", 0)) if isinstance(v, dict) else int(v))
                      for k, v in pkgs_in.items() if isinstance(k, str)}
        new_total = max(0, int(body["total_minutes"])) if "total_minutes" in body else None
    except (ValueError, TypeError) as e:
        return web.json_response({"error": "bad_field", "detail": str(e)}, status=400)
    async with POLICY_LOCK:
        POLICY["limits"] = new_limits
        if new_total is not None:
            POLICY["total_minutes"] = new_total
        save_policy(POLICY)
        snap_limits = dict(POLICY["limits"])
        snap_total = POLICY["total_minutes"]
    await send_to_kid({"cmd": "set_limits",
                       "packages": {k: {"daily_minutes": v} for k, v in snap_limits.items()},
                       "total_minutes": snap_total})
    return web.json_response({"ok": True, "limits": snap_limits,
                              "total_minutes": snap_total})


app = web.Application()
app.router.add_get("/", index)
app.router.add_get("/app.apk", app_apk)
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
app.router.add_get("/api/apk",  api_apk_get)
app.router.add_post("/api/apk", api_apk_post)
app.router.add_get("/api/location",  api_location_get)
app.router.add_post("/api/location", api_location_post)
app.router.add_get("/map", map_page)

if __name__ == "__main__":
    port = int(os.environ.get("KIDWATCH_PORT", "8899"))
    host = os.environ.get("KIDWATCH_HOST", "0.0.0.0")
    print(f"[relay] PerchGuard relay on {host}:{port} (auth %s)"
          % ("ON" if TOKEN else "OFF"))
    web.run_app(app, host=host, port=port)
