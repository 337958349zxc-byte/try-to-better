import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
from pathlib import Path
from server import ApiError, Handler, Store, create_server

def task(title="学习"):
    return dict(id="task-1", title=title, category="AI", scheduledAt="2026-10-26T09:00", durationMinutes=30, status="PENDING", accumulatedSeconds=0)

class StoreTests(unittest.TestCase):
    def test_long_term_repeat_rule_roundtrips_without_changing_sync_entities(self):
        recurring = {**task(), "seriesId": "repeat-series", "repeatRule": "R1|W|2|1,3,5|2027-05-01"}
        op = self.op(payload=recurring)
        first = self.store.sync(self.token, [op], 0)
        self.assertEqual(recurring, first["acknowledged"][0]["payload"])
        retry = self.store.sync(self.token, [op], 0)
        self.assertEqual(first["acknowledged"], retry["acknowledged"])
        row = next(e for e in self.store.desktop_state()["entities"] if e["key"] == "task:task-1")
        self.assertEqual(recurring, row["payload"])

    def test_plan_archive_retains_payload_and_syncs_tombstone(self):
        old = {**task("旧力量"), "category": "WORKOUT"}
        self.store.publish([old], 1)
        replacement = {**task("全身力量训练｜周衡 V1.4"), "id": "new-strength"}
        self.store.publish([replacement], 2, {old["id"]: 1}, [old["id"]])
        rows = {e["key"]: e for e in self.store.desktop_state()["entities"]}
        self.assertTrue(rows["task:task-1"]["deleted"])
        self.assertEqual(old, rows["task:task-1"]["payload"])
        self.assertFalse(rows["task:new-strength"]["deleted"])
        changes = self.store.sync(self.token, [], 0)["changes"]
        self.assertTrue(next(e for e in changes if e["key"] == "task:task-1")["deleted"])

    def test_archive_of_started_task_rolls_back_entire_publish(self):
        started = {**task(), "status": "PAUSED", "executionStartedAt": 42}
        self.store.sync(self.token, [self.op(payload=started)], 0)
        before = self.store.desktop_state()["entities"]
        with self.assertRaises(ApiError):
            self.store.publish([{**task(), "id": "new"}], 1, {"task-1": 1}, ["task-1"])
        self.assertEqual(before, self.store.desktop_state()["entities"])
        self.assertEqual(1, self.store.health()["planVersion"])

    def test_archive_requires_fresh_revision(self):
        self.store.publish([task()], 1)
        with self.assertRaises(ApiError):
            self.store.publish([{**task(), "id": "new"}], 2, {"task-1": 0}, ["task-1"])
        self.assertEqual(2, self.store.health()["planVersion"])

    def test_computer_can_open_registered_supplement_without_arbitrary_url(self):
        course = {**task(), "resourceUrl": "https://example.org/first", "note": "全部资源：\n视频1：https://example.org/first\n视频2：https://example.org/second"}
        self.store.publish([course], 1)
        body = {"id": "second-video-123456", "taskId": course["id"], "resourceUrl": "https://example.org/second"}
        with patch("server.webbrowser.open", return_value=True) as launch:
            self.assertTrue(self.store.open_on_computer(self.token, body)["ok"])
            launch.assert_called_once_with("https://example.org/second", new=2)
            with self.assertRaises(ApiError): self.store.open_on_computer(self.token, {**body, "id": "unlisted-video-123456", "resourceUrl": "https://unlisted.invalid"})

    def test_computer_open_is_authenticated_idempotent_and_keeps_execution(self):
        course = {**task(), "resourceUrl": "https://example.org/lesson", "isDemo": False}
        self.store.publish([course], 1)
        before = self.store.desktop_state()["entities"]
        body = {"id": "open-request-123456", "taskId": course["id"], "resourceUrl": course["resourceUrl"]}
        with patch("server.webbrowser.open", return_value=True) as launch:
            with self.assertRaises(ApiError): self.store.open_on_computer("invalid", body)
            result = self.store.open_on_computer(self.token, body)
            self.assertTrue(result["ok"])
            self.assertEqual("requested", result["state"])
            reopened = Store(self.store.path)
            self.assertEqual(result, reopened.open_on_computer(self.token, body))
            launch.assert_called_once_with(course["resourceUrl"], new=2)
            with self.assertRaises(ApiError): self.store.open_on_computer(self.token, {**body, "resourceUrl": "https://wrong.invalid"})
        self.assertEqual(before, self.store.desktop_state()["entities"])
        self.assertEqual(2, self.store.health()["planVersion"])

    def test_computer_open_rejects_unconfirmed_links_and_unsafe_schemes(self):
        course = {**task(), "resourceUrl": "https://example.org/lesson"}
        self.store.publish([course], 1)
        body = {"id": "open-request-123456", "taskId": course["id"], "resourceUrl": "https://wrong.invalid"}
        with patch("server.webbrowser.open") as launch:
            with self.assertRaises(ApiError): self.store.open_on_computer(self.token, body)
            self.store.publish([{**course, "resourceUrl": "file:///C:/private"}], 2, {course["id"]: 1})
            with self.assertRaises(ApiError): self.store.open_on_computer(self.token, {**body, "resourceUrl": "file:///C:/private"})
            launch.assert_not_called()

    def test_computer_open_failed_launch_is_not_reported_as_opened(self):
        course = {**task(), "resourceUrl": "https://example.org/lesson"}
        self.store.publish([course], 1)
        body = {"id": "open-request-123456", "taskId": course["id"], "resourceUrl": course["resourceUrl"]}
        with patch("server.webbrowser.open", return_value=False) as launch:
            result = self.store.open_on_computer(self.token, body)
            self.assertFalse(result["ok"])
            self.assertEqual("failed", result["state"])
            self.assertEqual(result, self.store.open_on_computer(self.token, body))
            launch.assert_called_once()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.tmp.name) / "test.sqlite3")
        code = self.store.desktop_state()["pairCode"]
        self.token = self.store.pair(code, "测试手机", "127.0.0.1")["token"]

    def tearDown(self): self.tmp.cleanup()
    def op(self, identifier="op-1", revision=0, payload=None):
        return dict(id=identifier, key="task:task-1", baseRevision=revision, payload=payload or task(), deleted=False)

    def test_one_time_pairing_and_revocation(self):
        code = self.store.desktop_state()["pairCode"]
        result = self.store.pair(code, "另一手机", "127.0.0.1")
        with self.assertRaises(ApiError): self.store.pair(code, "重试", "127.0.0.1")
        with self.store.db() as db: db.execute("UPDATE devices SET revoked=1 WHERE id=?", (result["deviceId"],))
        with self.assertRaises(ApiError): self.store.sync(result["token"], [], 0)

    def test_receipt_retries_never_duplicate(self):
        first = self.store.sync(self.token, [self.op()], 0)
        repeated = self.store.sync(self.token, [self.op()], 0)
        self.assertEqual(first["cursor"], repeated["cursor"])
        self.assertEqual(1, len(self.store.desktop_state()["entities"]))
        with self.assertRaises(ApiError): self.store.sync(self.token, [self.op(payload=task("被改动的重放"))], 0)

    def test_revision_conflict_keeps_both_sides(self):
        self.store.sync(self.token, [self.op()], 0)
        self.store.publish([task("电脑新安排")], 1, {"task-1": 1})
        result = self.store.sync(self.token, [self.op("op-2", 1, task("手机改动"))], 1)
        self.assertEqual("电脑新安排", result["conflicts"][0]["remote"]["payload"]["title"])
        self.assertEqual([], result["acknowledged"])
        resolved = self.store.sync(self.token, [self.op("op-3", 2, task("手机改动"))], result["cursor"])
        self.assertEqual(3, resolved["acknowledged"][0]["revision"])

    def test_started_execution_is_protected(self):
        running = {**task(), "status": "RUNNING", "executionStartedAt": 100}
        self.store.sync(self.token, [self.op(payload=running)], 0)
        with self.assertRaises(ApiError): self.store.publish([task("覆盖执行")], 1, {"task-1": 1})
        self.assertEqual("RUNNING", self.store.desktop_state()["entities"][0]["payload"]["status"])

    def test_delete_tombstone_and_device_identity_persist(self):
        self.store.sync(self.token, [self.op()], 0)
        op = {**self.op("delete", 1), "deleted": True, "payload": {}}
        self.store.sync(self.token, [op], 1)
        reopened = Store(self.store.path)
        self.assertEqual(self.store.health()["serverId"], reopened.health()["serverId"])
        self.assertTrue(reopened.sync(self.token, [], 0)["changes"][0]["deleted"])

    def test_invalid_plan_rolls_back_whole_batch(self):
        with self.assertRaises(ApiError): self.store.publish([task(), {**task(), "id": "bad", "durationMinutes": -1}], 1)
        self.assertEqual([], self.store.desktop_state()["entities"])
        self.assertEqual(1, self.store.health()["planVersion"])

    def test_stale_desktop_editor_cannot_overwrite_acknowledged_phone_edit(self):
        self.store.sync(self.token, [self.op()], 0)
        self.store.sync(self.token, [self.op("phone-edit", 1, task("手机已确认修改"))], 1)
        with self.assertRaises(ApiError): self.store.publish([task("陈旧的电脑表单")], 1, {"task-1": 1})
        self.assertEqual("手机已确认修改", self.store.desktop_state()["entities"][0]["payload"]["title"])

    def test_owner_approval_required_and_claim_retry_is_safe(self):
        identifier, secret = "new-phone-request-0001", "client-private-request-key-1234567890"
        self.store.request_connection(identifier, secret, "我的手机")
        pending = self.store.claim_connection(identifier, secret)
        self.assertEqual("pending", pending["status"]); self.assertNotIn("token", pending)
        with self.assertRaises(ApiError): self.store.claim_connection(identifier, "wrong-key")
        self.store.decide_connection(identifier, True)
        approved = self.store.claim_connection(identifier, secret)
        repeated = self.store.claim_connection(identifier, secret)
        self.assertEqual(approved["token"], repeated["token"])
        with self.store.db() as db: db.execute("UPDATE devices SET revoked=1 WHERE id=?", (approved["deviceId"],))
        with self.assertRaises(ApiError): self.store.claim_connection(identifier, secret)

class HttpTests(unittest.TestCase):
    def test_paired_phone_computer_open_endpoint(self):
        with tempfile.TemporaryDirectory() as folder:
            server = create_server("127.0.0.1", 0, Path(folder) / "test.sqlite3")
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            base = f"http://127.0.0.1:{server.server_port}"
            def post(path, body, token=""):
                req = urllib.request.Request(base + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "Authorization": "Bearer " + token}, method="POST")
                with opener.open(req, timeout=5) as response: return json.load(response)
            try:
                token = post("/api/pair", {"code": server.store.desktop_state()["pairCode"], "deviceName": "隔离联动测试"})["token"]
                course = {**task(), "resourceUrl": "https://example.org/lesson"}
                post("/api/publish", {"basePlanVersion": 1, "tasks": [course]})
                body = {"id": "http-open-request-123456", "taskId": course["id"], "resourceUrl": course["resourceUrl"]}
                with patch("server.webbrowser.open", return_value=True) as launch:
                    with self.assertRaises(urllib.error.HTTPError) as error: post("/api/open-on-computer", body)
                    self.assertEqual(401, error.exception.code)
                    self.assertEqual("requested", post("/api/open-on-computer", body, token)["state"])
                    self.assertTrue(post("/api/open-on-computer", body, token)["ok"])
                    launch.assert_called_once()
                self.assertEqual("PENDING", server.store.desktop_state()["entities"][0]["payload"]["status"])
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_diagnostics_require_pairing_persist_to_pc_and_retry_same_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            server = create_server("127.0.0.1", 0, Path(folder) / "test.sqlite3")
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            base = f"http://127.0.0.1:{server.server_port}"
            def send(body, token="invalid"):
                request = urllib.request.Request(base + "/api/device-diagnostics", json.dumps(body).encode(), {"Content-Type": "application/json", "Authorization": "Bearer " + token})
                with opener.open(request, timeout=5) as response: return json.load(response)
            body = {"id": "test-clock-diagnostic-0001", "report": "小米时钟诊断\nSET 1/3\nCANCEL 未确认"}
            try:
                with self.assertRaises(urllib.error.HTTPError) as error: send(body)
                self.assertEqual(401, error.exception.code)
                token = server.store.pair(server.store.desktop_state()["pairCode"], "测试手机", "127.0.0.1")["token"]
                first = send(body, token); repeated = send(body, token)
                self.assertEqual(first, repeated)
                self.assertEqual(1, len(server.store.diagnostics()["reports"]))
                text = (Path(folder) / "clock-diagnostics" / first["receipt"]["fileName"]).read_text(encoding="utf-8")
                self.assertIn(body["report"], text); self.assertNotIn(token, text)
                with self.assertRaises(urllib.error.HTTPError) as error: send({**body, "report": "different"}, token)
                self.assertEqual(409, error.exception.code)
                with self.assertRaises(urllib.error.HTTPError) as error: send({**body, "id": "../../outside-diagnostic"}, token)
                self.assertEqual(400, error.exception.code)
                with self.assertRaises(urllib.error.HTTPError) as error: send({**body, "id": "test-clock-diagnostic-0002", "report": "x" * 65537}, token)
                self.assertEqual(400, error.exception.code)
                with patch.object(Handler, "require_local", side_effect=ApiError(403, "电脑控制台仅限本机访问")):
                    with self.assertRaises(urllib.error.HTTPError) as error: opener.open(base + "/api/device-diagnostics", timeout=5)
                    self.assertEqual(403, error.exception.code)
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_lan_recipe_allowlist_does_not_open_desktop_management(self):
        with tempfile.TemporaryDirectory() as folder:
            server = create_server("127.0.0.1", 0, Path(folder) / "test.sqlite3")
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                with patch.object(Handler, "require_local", side_effect=ApiError(403, "电脑控制台仅限本机访问")):
                    for path, mime in [("/recipes/", "text/html"), ("/recipes?meal=lunch", "text/html"), ("/recipes/app.js", "application/javascript"), ("/recipes/style.css", "text/css")]:
                        with opener.open(base + path, timeout=5) as response:
                            self.assertEqual(200, response.status)
                            self.assertIn(mime, response.headers["Content-Type"])
                            self.assertGreater(len(response.read()), 100)
                            if path == "/recipes?meal=lunch": self.assertTrue(response.geturl().endswith("/recipes/?meal=lunch"))
                    for path in ["/", "/api/desktop", "/recipes/../data/batter.sqlite3", "/recipes/server.py"]:
                        with self.assertRaises(urllib.error.HTTPError) as error:
                            opener.open(base + path, timeout=5)
                        self.assertEqual(403, error.exception.code)
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_real_http_health_pair_sync_update_and_origin_guard(self):
        with tempfile.TemporaryDirectory() as folder:
            release = Path(folder) / "release.json"
            release.write_text(json.dumps({"packageName": "com.trytobatter.app", "versionName": "0.0.1", "versionCode": 1,
                                           "fileName": "test-fixture.apk", "size": 128, "sha256": "a" * 64}), encoding="utf-8")
            server = create_server("127.0.0.1", 0, Path(folder) / "test.sqlite3", release_file=release)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            def request(path, body=None, token=None, origin=None):
                headers = {"Content-Type": "application/json"}
                if token: headers["Authorization"] = "Bearer " + token
                if origin: headers["Origin"] = origin
                req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
                with urllib.request.urlopen(req, timeout=5) as response: return json.load(response)
            try:
                self.assertEqual("try-to-batter", request("/api/health")["service"])
                code = request("/api/desktop")["pairCode"]
                token = request("/api/pair", {"code": code, "deviceName": "HTTP测试"})["token"]
                self.assertTrue(request("/api/sync", {"cursor": 0, "operations": []}, token)["ok"])
                self.assertEqual("com.trytobatter.app", request("/api/update")["update"]["packageName"])
                with self.assertRaises(urllib.error.HTTPError): request("/api/rotate-code", {}, origin="https://unrelated.invalid")
                with self.assertRaises(urllib.error.HTTPError): request("/api/sync", {"cursor": 0}, "invalid")
            finally: server.shutdown(); server.server_close(); thread.join()

if __name__ == "__main__": unittest.main()
