"""Personal LAN companion. SQLite receipts make retried phone operations idempotent."""
from __future__ import annotations

import argparse
import hashlib
import hmac
import ipaddress
import json
import re
import secrets
import socket
import socketserver
import sqlite3
import threading
import time
import webbrowser
from contextlib import contextmanager
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
CATEGORIES = {"AI", "ENGLISH", "MEAL", "WORKOUT", "LIFE"}
STATUSES = {"PENDING", "RUNNING", "PAUSED", "COMPLETED", "PARTIAL", "SKIPPED"}
ENDED = {"COMPLETED", "PARTIAL", "SKIPPED"}

def now_ms(): return int(time.time() * 1000)
def canonical(value): return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
def digest(value): return hashlib.sha256(value.encode()).hexdigest()
def protected(task): return task.get("executionStartedAt", 0) > 0 or task.get("status") in ENDED

class ApiError(Exception):
    def __init__(self, status, message): self.status, self.message = status, message

def validate_entity(key, payload, deleted=False):
    if not isinstance(key, str) or ":" not in key or len(key) > 200: raise ApiError(400, "事项标识无效")
    kind, identifier = key.split(":", 1)
    if kind not in {"task", "record", "workout"} or not identifier: raise ApiError(400, "记录类型无效")
    if deleted: return
    if not isinstance(payload, dict) or payload.get("id") != identifier: raise ApiError(400, "记录关联无效")
    try:
        if kind == "task":
            assert isinstance(payload["title"], str) and 0 < len(payload["title"]) <= 2000
            assert payload["category"] in CATEGORIES and payload["status"] in STATUSES
            assert isinstance(payload["durationMinutes"], int) and 1 <= payload["durationMinutes"] <= 1440
            assert datetime.fromisoformat(payload["scheduledAt"]).tzinfo is None
            assert payload.get("accumulatedSeconds", 0) >= 0
        elif kind == "record":
            assert payload["category"] in CATEGORIES and payload["outcome"] in ENDED and payload["seconds"] >= 0
        else:
            assert payload["seconds"] >= 0 and isinstance(payload["sets"], list)
            assert all(x["setIndex"] > 0 and x["autoReps"] >= 0 and x["confirmedReps"] >= 0 for x in payload["sets"])
    except (KeyError, TypeError, ValueError, AssertionError): raise ApiError(400, "记录内容不完整或数值无效")

class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path, self.lock, self.attempts = path, threading.RLock(), {}
        with self.db() as db:
            db.executescript("""
              PRAGMA journal_mode=WAL;
              CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,seen INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
              CREATE TABLE IF NOT EXISTS entities(key TEXT PRIMARY KEY,revision INTEGER NOT NULL,payload TEXT NOT NULL,deleted INTEGER NOT NULL,seq INTEGER NOT NULL,source TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS receipts(device TEXT NOT NULL,id TEXT NOT NULL,request_hash TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(device,id));
              CREATE TABLE IF NOT EXISTS connection_requests(id TEXT PRIMARY KEY,name TEXT NOT NULL,secret_hash TEXT NOT NULL,expires INTEGER NOT NULL,status TEXT NOT NULL,device_id TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS device_diagnostics(device TEXT NOT NULL,id TEXT NOT NULL,request_hash TEXT NOT NULL,report TEXT NOT NULL,received INTEGER NOT NULL,PRIMARY KEY(device,id));
              CREATE TABLE IF NOT EXISTS browser_requests(device TEXT NOT NULL,id TEXT NOT NULL,request_hash TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(device,id));
            """)
            for key, value in {"serverId": secrets.token_hex(16), "cursor": "0", "planVersion": "1", "claimSecret": secrets.token_hex(32)}.items():
                db.execute("INSERT OR IGNORE INTO meta VALUES(?,?)", (key, value))
            if not self.meta(db, "pairCode"): self.rotate(db)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db: yield db
        finally: db.close()

    @staticmethod
    def meta(db, key):
        row = db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else ""

    @staticmethod
    def set_meta(db, key, value): db.execute("INSERT OR REPLACE INTO meta VALUES(?,?)", (key, str(value)))

    def rotate(self, db):
        self.set_meta(db, "pairCode", f"{secrets.randbelow(1_000_000):06d}")
        self.set_meta(db, "pairExpiresAt", now_ms() + 300_000)

    def health(self):
        with self.db() as db:
            addresses = [a for a in socket.gethostbyname_ex(socket.gethostname())[2] if ipaddress.ip_address(a).is_private and not ipaddress.ip_address(a).is_loopback]
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
                    route.connect(("192.0.2.1", 80))  # Route lookup only; no datagram is sent.
                    primary = route.getsockname()[0]
                if primary in addresses: addresses = [primary] + [a for a in addresses if a != primary]
            except OSError: pass
            return {"ok": True, "service": "try-to-batter", "protocol": 1, "serverId": self.meta(db, "serverId"), "serverName": socket.gethostname(), "planVersion": int(self.meta(db, "planVersion")), "serverTime": now_ms(), "lanAddresses": addresses}

    def pair(self, code, name, address):
        with self.lock, self.db() as db:
            attempts = self.attempts.setdefault(address, [])
            attempts[:] = [t for t in attempts if now_ms() - t < 300_000]
            if len(attempts) >= 10: raise ApiError(429, "配对尝试过多，请稍后重试")
            attempts.append(now_ms())
            valid = isinstance(code, str) and secrets.compare_digest(code, self.meta(db, "pairCode"))
            if not valid or now_ms() >= int(self.meta(db, "pairExpiresAt")): raise ApiError(401, "配对码无效或已过期，请查看电脑页面")
            identifier, token = secrets.token_hex(16), secrets.token_urlsafe(32)
            db.execute("INSERT INTO devices VALUES(?,?,?,?,0)", (identifier, str(name)[:100] or "Android 手机", digest(token), now_ms()))
            self.rotate(db)
            return {**self.health(), "deviceId": identifier, "token": token}

    def authenticate(self, db, token):
        device = db.execute("SELECT * FROM devices WHERE token_hash=? AND revoked=0", (digest(token),)).fetchone()
        if not device: raise ApiError(401, "配对已失效，请重新配对")
        return device

    def receive_diagnostic(self, token, body):
        identifier, report = body.get("id"), body.get("report")
        if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9-]{16,80}", identifier): raise ApiError(400, "诊断编号无效")
        if not isinstance(report, str) or not 1 <= len(report.encode("utf-8")) <= 65536: raise ApiError(400, "诊断内容大小无效")
        with self.lock, self.db() as db:
            device = self.authenticate(db, token)
            previous = db.execute("SELECT * FROM device_diagnostics WHERE device=? AND id=?", (device["id"], identifier)).fetchone()
            if previous and previous["request_hash"] != digest(report): raise ApiError(409, "诊断编号已被不同内容使用")
            received = previous["received"] if previous else now_ms()
            # Stable filename and receipt make a lost HTTP response safe to retry.
            folder = self.path.parent / "clock-diagnostics"
            folder.mkdir(exist_ok=True)
            name = f"{device['id']}-{identifier}.txt"
            temporary = folder / (name + ".tmp")
            temporary.write_text(f"设备：{device['name']}\n回执：{identifier}\n电脑接收时间：{received}\n\n{report}\n", encoding="utf-8")
            temporary.replace(folder / name)
            db.execute("INSERT OR IGNORE INTO device_diagnostics VALUES(?,?,?,?,?)", (device["id"], identifier, digest(report), report, received))
            return {"ok": True, "serverId": self.meta(db, "serverId"), "receipt": {"id": identifier, "receivedAt": received, "fileName": name}}

    def diagnostics(self):
        with self.lock, self.db() as db:
            rows = db.execute("SELECT device,id,received,report FROM device_diagnostics ORDER BY received DESC LIMIT 20").fetchall()
            return {"ok": True, "reports": [dict(row) for row in rows]}

    def request_connection(self, identifier, secret, name):
        if not isinstance(identifier, str) or not 16 <= len(identifier) <= 100 or not isinstance(secret, str) or not 32 <= len(secret) <= 128:
            raise ApiError(400, "连接请求无效")
        with self.lock, self.db() as db:
            db.execute("DELETE FROM connection_requests WHERE expires<?", (now_ms(),))
            old = db.execute("SELECT * FROM connection_requests WHERE id=?", (identifier,)).fetchone()
            if old:
                if not secrets.compare_digest(old["secret_hash"], digest(secret)): raise ApiError(401, "连接请求身份不一致")
                return {"ok": True, "status": old["status"], "expiresAt": old["expires"]}
            if db.execute("SELECT COUNT(*) FROM connection_requests WHERE status IN ('pending','approved')").fetchone()[0] >= 20: raise ApiError(429, "待确认连接较多，请稍后再试")
            expires = now_ms() + 120_000
            db.execute("INSERT INTO connection_requests VALUES(?,?,?,?,?,?)", (identifier, str(name or "Android 手机")[:100], digest(secret), expires, "pending", secrets.token_hex(16)))
            return {"ok": True, "status": "pending", "expiresAt": expires}

    def decide_connection(self, identifier, allow):
        if not isinstance(allow, bool): raise ApiError(400, "请选择允许或拒绝")
        with self.lock, self.db() as db:
            row = db.execute("SELECT * FROM connection_requests WHERE id=?", (identifier,)).fetchone()
            if not row or row["expires"] <= now_ms(): raise ApiError(410, "连接请求已过期，请在手机重试")
            if row["status"] != "pending": raise ApiError(409, "该连接请求已经处理")
            db.execute("UPDATE connection_requests SET status=? WHERE id=?", ("approved" if allow else "rejected", identifier))
            return {"ok": True}

    def claim_connection(self, identifier, secret):
        if not isinstance(secret, str): raise ApiError(401, "连接请求身份无效")
        with self.lock, self.db() as db:
            row = db.execute("SELECT * FROM connection_requests WHERE id=?", (identifier,)).fetchone()
            if not row or not secrets.compare_digest(row["secret_hash"], digest(secret)): raise ApiError(401, "连接请求身份无效")
            if row["expires"] <= now_ms(): raise ApiError(410, "连接请求已过期，请重试")
            if row["status"] in {"pending", "rejected"}: return {"ok": True, "status": row["status"], "expiresAt": row["expires"]}
            token = hmac.new(bytes.fromhex(self.meta(db, "claimSecret")), (identifier + ":" + secret).encode(), hashlib.sha256).hexdigest()
            if row["status"] == "claimed":
                self.authenticate(db, token)  # A revoked approval must never recreate its device.
            else:
                db.execute("INSERT INTO devices VALUES(?,?,?,?,0)", (row["device_id"], row["name"], digest(token), now_ms()))
                db.execute("UPDATE connection_requests SET status='claimed' WHERE id=?", (identifier,))
            return {**self.health(), "status": "approved", "deviceId": row["device_id"], "token": token}

    @staticmethod
    def entity(row):
        return {"key": row["key"], "revision": row["revision"], "payload": json.loads(row["payload"]), "deleted": bool(row["deleted"]), "sequence": row["seq"]}

    def write_entity(self, db, key, payload, deleted, source):
        old = db.execute("SELECT * FROM entities WHERE key=?", (key,)).fetchone()
        revision = old["revision"] + 1 if old else 1
        cursor = int(self.meta(db, "cursor")) + 1
        self.set_meta(db, "cursor", cursor)
        db.execute("INSERT OR REPLACE INTO entities VALUES(?,?,?,?,?,?)", (key, revision, canonical(payload), int(deleted), cursor, source))
        return {"key": key, "revision": revision, "payload": payload, "deleted": deleted, "sequence": cursor}

    def sync(self, token, operations, cursor):
        if not isinstance(operations, list) or len(operations) > 64 or not isinstance(cursor, int) or cursor < 0: raise ApiError(400, "同步请求无效")
        with self.lock, self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            device = self.authenticate(db, token)
            db.execute("UPDATE devices SET seen=? WHERE id=?", (now_ms(), device["id"]))
            acknowledged, conflicts = [], []
            seen_keys = set()
            for operation in operations:
                op_id, key = operation.get("id"), operation.get("key")
                if not isinstance(op_id, str) or not 1 <= len(op_id) <= 100: raise ApiError(400, "操作标识无效")
                if not isinstance(operation.get("deleted", False), bool) or type(operation.get("baseRevision")) is not int or operation["baseRevision"] < 0: raise ApiError(400, "操作版本无效")
                validate_entity(key, operation.get("payload"), operation.get("deleted", False))
                if key in seen_keys: raise ApiError(400, "同一批次包含重复事项")
                seen_keys.add(key)
                fingerprint = digest(canonical(operation))
                receipt = db.execute("SELECT * FROM receipts WHERE device=? AND id=?", (device["id"], op_id)).fetchone()
                if receipt:
                    if receipt["request_hash"] != fingerprint: raise ApiError(400, "已确认操作的内容不能改变")
                    acknowledged.append(json.loads(receipt["result"])); continue
                old = db.execute("SELECT * FROM entities WHERE key=?", (key,)).fetchone()
                if operation.get("baseRevision") != (old["revision"] if old else 0):
                    remote = self.entity(old) if old else {"key": key, "revision": 0, "payload": {}, "deleted": True, "sequence": 0}
                    conflicts.append({"operationId": op_id, "remote": remote, "reason": "电脑与手机修改了同一记录"}); continue
                result = self.write_entity(db, key, operation.get("payload", {}), bool(operation.get("deleted")), device["id"])
                result["operationId"] = op_id
                db.execute("INSERT INTO receipts VALUES(?,?,?,?)", (device["id"], op_id, fingerprint, canonical(result)))
                acknowledged.append(result)
            current = int(self.meta(db, "cursor"))
            if cursor > current: raise ApiError(409, "电脑数据版本已回退，请断开后重新配对")
            changes = [self.entity(r) for r in db.execute("SELECT * FROM entities WHERE seq>? ORDER BY seq", (cursor,))]
            return {**self.health(), "cursor": current, "acknowledged": acknowledged, "conflicts": conflicts, "changes": changes}

    def open_on_computer(self, token, body):
        identifier, task_id, expected_url = body.get("id"), body.get("taskId"), body.get("resourceUrl")
        if not isinstance(identifier, str) or not 16 <= len(identifier) <= 100 or not isinstance(task_id, str) or not 1 <= len(task_id) <= 100:
            raise ApiError(400, "打开请求无效")
        with self.lock:
            with self.db() as db:
                device = self.authenticate(db, token)
                fingerprint = digest(canonical(body))
                previous = db.execute("SELECT * FROM browser_requests WHERE device=? AND id=?", (device["id"], identifier)).fetchone()
                if previous:
                    if previous["request_hash"] != fingerprint: raise ApiError(409, "同一打开请求的内容不能改变")
                    return self.browser_receipt(identifier, previous["state"])
                row = db.execute("SELECT * FROM entities WHERE key=? AND deleted=0", ("task:" + task_id,)).fetchone()
                if not row: raise ApiError(404, "电脑尚未收到这项课程，请先同步")
                task = json.loads(row["payload"])
                urls = [task.get("resourceUrl", "")] + re.findall(r'''https?://[^\s<>"'，。；]+''', task.get("note", ""))
                urls = {u.rstrip(")]\u007d）】") for u in urls if isinstance(u, str)}
                if task.get("category") not in {"AI", "ENGLISH"} or task.get("isDemo"): raise ApiError(400, "仅支持真实学习事项的资料")
                if not isinstance(expected_url, str) or expected_url not in urls: raise ApiError(409, "手机和电脑的资料链接不一致，请先处理同步或内容冲突")
                url = expected_url
                parsed = urlsplit(url)
                if not url or len(url) > 4096 or parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username is not None or any(ord(c) < 32 for c in url):
                    raise ApiError(400, "课程需要有效的HTTP或HTTPS资料链接")
                count = db.execute("SELECT COUNT(*) FROM browser_requests WHERE device=? AND created>?", (device["id"], now_ms()-10_000)).fetchone()[0]
                if count >= 5: raise ApiError(429, "打开请求过于频繁，请稍候")
                # Reserve before launching. An interrupted launch is never replayed automatically.
                db.execute("INSERT INTO browser_requests VALUES(?,?,?,?,?,?,?)", (device["id"], identifier, fingerprint, task["title"], url, "pending", now_ms()))
            try: opened = bool(webbrowser.open(url, new=2))
            except Exception: opened = False
            state = "requested" if opened else "failed"
            with self.db() as db:
                db.execute("UPDATE browser_requests SET state=? WHERE device=? AND id=?", (state, device["id"], identifier))
            return self.browser_receipt(identifier, state)

    @staticmethod
    def browser_receipt(identifier, state):
        messages = {"requested": "已请求电脑浏览器打开资料，请在电脑查看；不代表页面已加载或课程已完成", "failed": "电脑未能启动浏览器，请在电脑学习工作台手动打开", "pending": "上次打开状态尚未确认，请先查看电脑；未自动重复打开"}
        return {"ok": state == "requested", "requestId": identifier, "state": state, "message": messages[state]}

    def desktop_state(self):
        with self.lock, self.db() as db:
            if now_ms() >= int(self.meta(db, "pairExpiresAt")): self.rotate(db)
            devices = [{"id": r["id"], "name": r["name"], "lastSeen": r["seen"], "online": now_ms() - r["seen"] < 12_000} for r in db.execute("SELECT * FROM devices WHERE revoked=0")]
            return {**self.health(), "pairCode": self.meta(db, "pairCode"), "pairExpiresAt": int(self.meta(db, "pairExpiresAt")), "devices": devices,
                    "connectionRequests": [{"id": r["id"], "name": r["name"], "status": r["status"], "expiresAt": r["expires"]} for r in db.execute("SELECT * FROM connection_requests WHERE expires>? AND status IN ('pending','approved')", (now_ms(),))],
                    "browserRequests": [{"title": r["title"], "state": r["state"], "createdAt": r["created"]} for r in db.execute("SELECT title,state,created FROM browser_requests ORDER BY created DESC LIMIT 10")],
                    "entities": [self.entity(r) for r in db.execute("SELECT * FROM entities ORDER BY seq DESC")]}

    def publish(self, tasks, expected, expected_revisions=None, archive_task_ids=None):
        if not isinstance(tasks, list) or not 1 <= len(tasks) <= 500: raise ApiError(400, "请提供 1–500 项事项")
        if expected_revisions is None: expected_revisions = {}
        if not isinstance(expected_revisions, dict): raise ApiError(400, "事项基准版本无效")
        if archive_task_ids is None: archive_task_ids = []
        if not isinstance(archive_task_ids, list) or len(archive_task_ids) > 500 or any(not isinstance(x, str) or not x for x in archive_task_ids): raise ApiError(400, "归档事项无效")
        if len(set(archive_task_ids)) != len(archive_task_ids) or set(archive_task_ids) & {t.get("id") for t in tasks}: raise ApiError(400, "归档事项重复或与更新重叠")
        if len({t.get("id") for t in tasks}) != len(tasks): raise ApiError(400, "计划中存在重复事项")
        with self.lock, self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            if expected != int(self.meta(db, "planVersion")): raise ApiError(409, "计划版本已变化，请刷新后重试")
            for identifier in archive_task_ids:
                key = "task:" + identifier
                old = db.execute("SELECT * FROM entities WHERE key=?", (key,)).fetchone()
                if not old or old["deleted"] or expected_revisions.get(identifier) != old["revision"]: raise ApiError(409, "归档事项已变化，请刷新")
                payload = json.loads(old["payload"])
                if protected(payload) or payload.get("status") != "PENDING" or payload.get("accumulatedSeconds", 0) or payload.get("executionSnapshot"): raise ApiError(409, "已有执行的事项不能通过计划清理归档")
                # Tombstone retains the full original payload; records and workouts are untouched.
                self.write_entity(db, key, payload, True, "desktop")
            for task in tasks:
                key = "task:" + str(task.get("id", "")); validate_entity(key, task)
                old = db.execute("SELECT * FROM entities WHERE key=?", (key,)).fetchone()
                if old and expected_revisions.get(task["id"]) != old["revision"]: raise ApiError(409, "手机或其他操作已修改这项安排，请刷新后重新编辑")
                if old and not old["deleted"] and protected(json.loads(old["payload"])): raise ApiError(409, "已开始或完成的事项受到保护，请新建未来安排")
                if protected(task): raise ApiError(400, "电脑计划不能伪造手机执行结果")
                self.write_entity(db, key, task, False, "desktop")
            self.set_meta(db, "planVersion", expected + 1)
            return {"ok": True, "planVersion": expected + 1}

class Handler(BaseHTTPRequestHandler):
    server_version = "BatterLAN/0.2"
    def log_message(self, fmt, *args): pass  # Tokens and pair codes never enter request logs.
    @property
    def store(self): return self.server.store

    def reply(self, status, data, content_type="application/json; charset=utf-8"):
        raw = data if isinstance(data, bytes) else canonical(data).encode("utf-8")
        self.send_response(status); self.send_header("Content-Type", content_type); self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store"); self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'")
        self.end_headers(); self.wfile.write(raw)

    def require_local(self):
        if not ipaddress.ip_address(self.client_address[0]).is_loopback: raise ApiError(403, "电脑控制台仅限本机访问")

    def validate_host(self):
        host = urlsplit("http://" + self.headers.get("Host", "")).hostname
        try: valid = host == "localhost" or ipaddress.ip_address(host).is_private or ipaddress.ip_address(host).is_loopback
        except (ValueError, TypeError): valid = False
        if not valid: raise ApiError(403, "主机地址无效")
        origin = self.headers.get("Origin")
        if origin and origin != "http://" + self.headers.get("Host"): raise ApiError(403, "不允许跨站请求")

    def dispatch(self, method):
        try:
            self.validate_host()
            path = urlsplit(self.path).path
            if method == "GET":
                if path == "/recipes":
                    self.send_response(302)
                    query = urlsplit(self.path).query
                    self.send_header("Location", "/recipes/" + ("?" + query if query else ""))
                    self.end_headers()
                    return
                recipe_assets = {"/recipes/": ("index.html", "text/html; charset=utf-8"),
                                 "/recipes/index.html": ("index.html", "text/html; charset=utf-8"),
                                 "/recipes/app.js": ("app.js", "application/javascript; charset=utf-8"),
                                 "/recipes/style.css": ("style.css", "text/css; charset=utf-8")}
                if path in recipe_assets:
                    name, mime = recipe_assets[path]
                    file = ROOT.parent / "菜谱网页" / "dist" / name
                    if not file.is_file(): raise ApiError(404, "菜谱资料尚未就绪")
                    return self.reply(200, file.read_bytes(), mime)
                if path == "/api/health": return self.reply(200, self.store.health())
                if path == "/api/update":
                    release = self.server.release_file
                    if not release.is_file(): return self.reply(200, {"ok": True, "update": None})
                    update = json.loads(release.read_text(encoding="utf-8"))
                    name = update.pop("fileName")
                    if "/" in name or "\\" in name or not name.endswith(".apk") or update.get("packageName") != "com.trytobatter.app": raise ApiError(500, "更新配置无效")
                    host = urlsplit("http://" + self.headers["Host"]).hostname
                    update["downloadUrl"] = f"http://{host}:8080/{name}"
                    return self.reply(200, {"ok": True, "update": update})
                self.require_local()
                if path == "/api/desktop": return self.reply(200, self.store.desktop_state())
                if path == "/api/device-diagnostics": return self.reply(200, self.store.diagnostics())
                if path in {"/", "/app.js", "/style.css"}:
                    name = "index.html" if path == "/" else path[1:]
                    mime = {"index.html": "text/html; charset=utf-8", "app.js": "application/javascript; charset=utf-8", "style.css": "text/css; charset=utf-8"}[name]
                    return self.reply(200, (ROOT / "web" / name).read_bytes(), mime)
                raise ApiError(404, "页面不存在")
            if not self.headers.get("Content-Type", "").startswith("application/json"): raise ApiError(415, "需要 JSON 请求")
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4_000_000: raise ApiError(413, "请求大小无效")
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict): raise ApiError(400, "请求内容无效")
            if path == "/api/pair": return self.reply(200, self.store.pair(body.get("code"), body.get("deviceName"), self.client_address[0]))
            if path == "/api/connection-request": return self.reply(200, self.store.request_connection(body.get("id"), body.get("secret"), body.get("deviceName")))
            if path == "/api/connection-claim": return self.reply(200, self.store.claim_connection(body.get("id"), body.get("secret")))
            if path == "/api/sync":
                token = self.headers.get("Authorization", "").removeprefix("Bearer ")
                return self.reply(200, self.store.sync(token, body.get("operations", []), body.get("cursor", 0)))
            if path == "/api/open-on-computer":
                token = self.headers.get("Authorization", "").removeprefix("Bearer ")
                return self.reply(200, self.store.open_on_computer(token, body))
            if path == "/api/device-diagnostics":
                token = self.headers.get("Authorization", "").removeprefix("Bearer ")
                return self.reply(200, self.store.receive_diagnostic(token, body))
            if path == "/api/unpair":
                token = self.headers.get("Authorization", "").removeprefix("Bearer ")
                with self.store.lock, self.store.db() as db:
                    device = self.store.authenticate(db, token)
                    db.execute("UPDATE devices SET revoked=1 WHERE id=?", (device["id"],))
                return self.reply(200, {"ok": True})
            self.require_local()
            if path == "/api/connection-decide": return self.reply(200, self.store.decide_connection(body.get("id"), body.get("allow")))
            if path == "/api/rotate-code":
                with self.store.lock, self.store.db() as db: self.store.rotate(db)
                return self.reply(200, {"ok": True})
            if path == "/api/publish": return self.reply(200, self.store.publish(body.get("tasks"), body.get("basePlanVersion"), body.get("expectedRevisions"), body.get("archiveTaskIds")))
            if path == "/api/revoke":
                with self.store.lock, self.store.db() as db: db.execute("UPDATE devices SET revoked=1 WHERE id=?", (body.get("deviceId"),))
                return self.reply(200, {"ok": True})
            raise ApiError(404, "接口不存在")
        except ApiError as error: self.reply(error.status, {"ok": False, "error": error.message})
        except (ValueError, KeyError, TypeError, json.JSONDecodeError): self.reply(400, {"ok": False, "error": "请求格式无效"})
        except (BrokenPipeError, ConnectionResetError): pass
        except Exception:
            self.reply(500, {"ok": False, "error": "电脑服务暂时无法保存，请稍后重试"})

    def do_GET(self): self.dispatch("GET")
    def do_POST(self): self.dispatch("POST")

def create_server(host, port, path, release_file=ROOT / "release.json"):
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    server.store = Store(Path(path))
    server.release_file = Path(release_file)
    return server

class DiscoveryHandler(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        try:
            if len(data) > 512 or not ipaddress.ip_address(self.client_address[0]).is_private: return
            query = json.loads(data)
            if query.get("probe") != "try-to-batter" or query.get("protocol") != 1: return
            health = self.server.store.health()
            reply = {k: health[k] for k in ("service", "protocol", "serverId", "serverName")}
            reply["port"] = self.server.http_port
            sock.sendto(canonical(reply).encode(), self.client_address)
        except (ValueError, OSError): pass

def start_discovery(store, http_port):
    discovery = socketserver.ThreadingUDPServer(("0.0.0.0", 28761), DiscoveryHandler)
    discovery.daemon_threads = True; discovery.store = store; discovery.http_port = http_port
    threading.Thread(target=discovery.serve_forever, daemon=True).start()
    return discovery

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0"); parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--data", type=Path, default=ROOT / "data" / "batter.sqlite3")
    options = parser.parse_args()
    server = create_server(options.host, options.port, options.data)
    try: discovery = start_discovery(server.store, options.port)
    except OSError: discovery = None  # Manual/current-address fallback remains available.
    print(f"try to batter desktop: http://127.0.0.1:{options.port}", flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        server.server_close()
        if discovery: discovery.shutdown(); discovery.server_close()
