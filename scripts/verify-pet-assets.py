"""Validate packaged pet atlases, frame safety, attribution and immutable source geometry."""
import hashlib
import json
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
assets = root / "android/app/src/main/assets/petpreview"
manifest = json.loads((assets / "provenance.json").read_text(encoding="utf-8"))
result = []
for record in manifest["assets"]:
    path = assets / record["output"]
    assert hashlib.sha256(path.read_bytes()).hexdigest() == record["sha256"], path
    img = Image.open(path)
    assert img.mode == "RGBA", path
    cell, cols = record["cell"], record["columns"]
    assert img.width == cols * cell and img.height == ((record["frames"] + cols - 1) // cols) * cell
    boxes = []
    for frame in range(record["frames"]):
        x, y = frame % cols * cell, frame // cols * cell
        tile = img.crop((x, y, x + cell, y + cell))
        box = tile.getchannel("A").getbbox()
        assert box and box[0] > 0 and box[1] > 0 and box[2] < cell and box[3] < cell, (path, frame, box)
        assert tile.getpixel((0, 0))[3] == 0
        boxes.append(list(box))
    result.append({"asset": path.name, "frames": record["frames"], "dimensions": list(img.size), "rgba": True,
                   "noFrameTouchesCellBoundary": True, "sha256Matches": True, "alphaBoundingBoxes": boxes})
assert "YunYueSama" in (assets / "LICENSE").read_text(encoding="utf-8")
assert manifest["repository"] in (assets / "NOTICE.txt").read_text(encoding="utf-8")
assert not any("community" in p.name for p in assets.rglob("*"))
report = {"upstreamCommit": manifest["commit"], "checksPassed": True, "totalFrames": sum(r["frames"] for r in result),
          "packagedBytes": sum(a["bytes"] for a in manifest["assets"]), "assets": result,
          "scope": "Package geometry/transparency/integrity and attribution checks; not third-party rights clearance."}
dest = root / "docs/pet/verification/assets.json"
dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in report.items() if k != "assets"}, ensure_ascii=False, indent=2))
