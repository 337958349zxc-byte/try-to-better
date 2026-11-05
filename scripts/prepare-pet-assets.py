"""Compile unchanged full-body frames from a locally downloaded, pinned upstream.

Requires Pillow. Read upstream LICENSE and ASSET_LICENSE.md before obtaining assets.
Usage: python scripts/prepare-pet-assets.py --source-dir <upstream checkout/cache>
No community reference originals are read or copied by this script.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
from PIL import Image

COMMIT = "7661c8b304c5400701f91da01b1a643a207331de"
REPO = "https://github.com/YunYueSama/codex-deepseek-pet"
SOURCE_BLOBS = {
    "LICENSE": "57a0bb973d4a0a6ecc25716de05f9598dae11bf9",
    "ASSET_LICENSE.md": "ef7cd63ee2672a0da5126cef06057ce0233c5843",
    "assets/whale/idle-front.png": "e19f88b5826aba89ea2aa0d086d31d40c9def912",
    "assets/whale/inbetween-walk.png": "c84213762f8cba68b4d502dd13771983831763e9",
    "assets/whale/story-token.png": "50edf1bab5aa1de428f6e39bb12c8b8d39fc10f3",
    "assets/whale/dense-wave.png": "f9fc5cf57b041e6b30d2ee6abd10a34602b09554",
    "assets/whale/dense-dance.png": "2e181a99a7cf96ea902283fba9d65a67d1f96e66",
    "assets/whale/inbetween-jump.png": "57a799bdb58d7347f1ae5a0ef2162d9bc570fc5e",
    "assets/whale/inbetween-sleep.png": "f7b68276899a3510c030f48de2800f69cf8aba9b",
    "assets/whale/portrait.png": "4afdeae2b6f5212c6c17006ff22847bfa5d4d57e",
}
ODDS = list(range(1, 32, 2))
JUMP = [0, 1, 3, 34, 5, 35, 7, 36, 9, 11, 13, 15, 17, 19, 21, 23, 25, 45, 27, 46, 29, 47, 31]
SLEEP = [1, 3, 5, 7, 35, 9, 11, 37, 13, 38, 17, 39, 19, 21, 23, 25, 27, 29, 31]
CLIPS = {
    "idle": ("idle-front", 1024, 2, [0, 1], 512),
    "walk": ("inbetween-walk", 512, 4, [0, 1, 3, 2, 4, 5, 6, 7, 8, 9, 11, 10, 12, 13, 14, 15], 384),
    "think": ("story-token", 384, 4, [0, 1, 2], 384),
    "talk": ("dense-wave", 512, 4, ODDS, 384),
    "happy": ("dense-dance", 512, 4, ODDS, 384),
    "remind": ("inbetween-jump", 512, 4, JUMP, 384),
    "sleep": ("inbetween-sleep", 512, 4, SLEEP, 384),
    "portrait": ("portrait", 1024, 1, [0], 192),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--source-dir", required=True, type=Path)
    args = parser.parse_args()
    for name, expected in SOURCE_BLOBS.items():
        data = (args.source_dir / name).read_bytes()
        actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        assert actual == expected, f"Not the pinned upstream file: {name}"
    root = Path(__file__).resolve().parents[1]
    dest = root / "android/app/src/main/assets/petpreview"
    dest.mkdir(parents=True, exist_ok=True)
    records = []
    for name, (source, cell, cols, frames, target) in CLIPS.items():
        path = args.source_dir / f"assets/whale/{source}.png"
        img = Image.open(path).convert("RGBA")
        assert img.width == cell * cols and img.height % cell == 0, path
        out_cols = min(4, len(frames))
        atlas = Image.new("RGBA", (out_cols * target, ((len(frames) + out_cols - 1) // out_cols) * target))
        for index, frame in enumerate(frames):
            x, y = frame % cols * cell, frame // cols * cell
            assert y + cell <= img.height
            tile = img.crop((x, y, x + cell, y + cell))
            box = tile.getchannel("A").getbbox()
            assert box and min(box[:2]) > 0 and max(box[2:]) < cell, (path, frame, box)
            tile = tile.resize((target, target), Image.Resampling.LANCZOS)
            atlas.paste(tile, (index % out_cols * target, index // out_cols * target))
        output = dest / f"{name}.webp"
        atlas.save(output, "WEBP", lossless=True, method=6, exact=True)
        assert Image.open(output).convert("RGBA").tobytes() == atlas.tobytes()
        records.append({"output": output.name, "source": f"assets/whale/{source}.png",
                        "sourceSha256": sha(path), "sourceGitBlob": SOURCE_BLOBS[f"assets/whale/{source}.png"],
                        "sourceSize": list(img.size), "sourceCell": cell,
                        "sourceColumns": cols, "sourceFrames": frames, "cell": target, "columns": out_cols,
                        "frames": len(frames), "sha256": sha(output), "bytes": output.stat().st_size,
                        "alpha": True, "modifications": "Select complete source cells in upstream playback order; uniform Lanczos downsample; repack; lossless WebP. No drawing, recoloring or body-part compositing."})
    for name in ("LICENSE", "ASSET_LICENSE.md"):
        shutil.copyfile(args.source_dir / name, dest / name)
    notice = ("作者：YunYueSama\n仓库：" + REPO + "\n上游版本：" + COMMIT + "\n\n"
              "本预览由 Try to Better 适配。修改：完整帧选取、等比例缩小、重新排列及无损 WebP 编码；"
              "未重绘角色；头像在界面内采用原 portrait 的头肩取景。动画顺序与时间参考上游 src/renderer/motion.js（同一许可）。\n"
              "随附 LICENSE 与 ASSET_LICENSE.md 原文。本许可仅涵盖作者有权授权的贡献，"
              "不等于底层社区角色设计、参考图片、商标的完整授权，不代表 DeepSeek 官方认可。"
              "未打包授权未确认的社区参考原图。商业公开分发前仍须确认第三方角色等权利。\n")
    (dest / "NOTICE.txt").write_text(notice, encoding="utf-8")
    manifest = {"author": "YunYueSama", "repository": REPO, "commit": COMMIT,
                "license": "大肥鱼项目署名许可 1.0 (custom, not MIT)", "assets": records}
    (dest / "provenance.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"clips": len(records), "bytes": sum(r["bytes"] for r in records)}, indent=2))


if __name__ == "__main__":
    main()
