"""Disposable integration fixture. Fault injection routes are not in the production server."""
import argparse
import json
from pathlib import Path
from server import Handler, create_server

class TestHandler(Handler):
    def dispatch(self, method):
        if self.path == "/test/network" and method == "POST":
            try:
                self.require_local()
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                self.server.offline = bool(body["offline"])
                self.reply(200, {"ok": True})
            except Exception: self.reply(400, {"error": "invalid test control"})
            return
        if self.server.offline and self.path in {"/api/health", "/api/sync", "/api/device-diagnostics"}:
            return self.reply(503, {"ok": False, "error": "测试服务临时不可用"})
        super().dispatch(method)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("--port", type=int, default=18081); parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--release", type=Path, default=Path(__file__).with_name("release.json"))
    args = parser.parse_args()
    server = create_server("0.0.0.0", args.port, args.data, args.release)
    server.RequestHandlerClass = TestHandler; server.offline = False
    print("Disposable e2e server ready", flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()
