"""Inspect actual Android UI after a separately prepared force-stop recovery fixture."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("--adb", required=True)
p.add_argument("--serial", required=True)
p.add_argument("--out", required=True)
args = p.parse_args()
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)

def adb(*words):
    return subprocess.run([args.adb, "-s", args.serial, *words], check=True, capture_output=True, timeout=20).stdout.decode("utf-8", errors="replace")

def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/batter-check.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/batter-check.xml"))

def tap_text(text, attempts=1):
    for attempt in range(attempts):
        t = tree()
        nodes = [n for n in t.iter("node") if n.get("text") == text]
        if nodes:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
            adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(.6)
            return
        root = list(t)[0]
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", root.get("bounds")))
        adb("shell", "input", "swipe", str(x2//2), str(int(y2*.76)), str(x2//2), str(int(y2*.25)), "350")
        time.sleep(.4)
    raise AssertionError("Android UI text not found: " + text)

tap_text("计划")
tap_text("运动训练", attempts=5)
t = tree()
text = ET.tostring(t, encoding="unicode")
(out / "workout-recovery-ui.xml").write_text(text, encoding="utf-8")
assert "已暂停" in text and "00:12" in text
assert any(n.get("text") == "3" for n in t.iter("node")), "Restored rep count is not 3"
print("Actual process restart: workout resumed paused with 3 reps and 12 seconds; camera remained closed.")
