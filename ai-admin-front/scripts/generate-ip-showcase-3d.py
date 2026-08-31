#!/usr/bin/env python3
"""Generate the ReachAI mascot GLB with the official AHOLO Python SDK.

The API key is read from ``AHOLO_API_KEY`` or from a hidden terminal prompt. It
is never written to disk. Temporary result URLs are downloaded immediately and
are intentionally omitted from the metadata file.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen


FRONT_FILENAME = "reachai-ip-front-purple-ears.png"
SIDE_FILENAME = "reachai-ip-left-purple-ears.png"
BACK_FILENAME = "reachai-ip-back-purple-ears.png"


def parse_args() -> argparse.Namespace:
    frontend_root = Path(__file__).resolve().parents[1]
    reference_dir = frontend_root / "public" / "ip-showcase" / "assets" / "3d-reference"
    output_dir = frontend_root / "public" / "ip-showcase" / "assets" / "3d"

    parser = argparse.ArgumentParser(
        description="Generate a textured ReachAI mascot GLB from the approved orthographic views.",
    )
    parser.add_argument(
        "--inputs",
        nargs=3,
        type=Path,
        metavar=("FRONT", "LEFT_SIDE", "BACK"),
        default=[
            reference_dir / FRONT_FILENAME,
            reference_dir / SIDE_FILENAME,
            reference_dir / BACK_FILENAME,
        ],
        help="Ordered front, left-side, and back reference images.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=output_dir / "reachai-ip-mascot.glb",
        help="Destination GLB path.",
    )
    parser.add_argument(
        "--face-count",
        type=int,
        default=300_000,
        choices=range(10_000, 300_001),
        metavar="10000..300000",
    )
    parser.add_argument(
        "--gui-key",
        action="store_true",
        help="Read the API key from a one-time masked desktop dialog.",
    )
    return parser.parse_args()


def prompt_api_key_gui() -> str:
    import tkinter as tk
    from tkinter import simpledialog

    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)
    try:
        value = simpledialog.askstring(
            "睿池 AI · Lux3D 生成",
            "请输入 AHOLO API Key\n\n密钥仅用于本次生成，不会保存到文件。",
            show="*",
            parent=root,
        )
        return (value or "").strip()
    finally:
        root.destroy()


def require_api_key(*, gui: bool) -> str:
    api_key = os.environ.get("AHOLO_API_KEY", "").strip()
    if not api_key:
        api_key = prompt_api_key_gui() if gui else getpass.getpass(
            "请输入 AHOLO API Key（输入不会显示）: ",
        ).strip()
    if not api_key:
        raise RuntimeError("未提供 AHOLO API Key")
    os.environ["AHOLO_API_KEY"] = api_key
    return api_key


def upload_references(paths: list[Path]) -> list[str]:
    from manycore.aholo_sdk_asset import create_asset_client
    from manycore.aholo_sdk_core import AholoClientConfig

    asset = create_asset_client(
        AholoClientConfig(api_key=os.environ["AHOLO_API_KEY"], region="cn"),
    )
    urls: list[str] = []

    for index, path in enumerate(paths, start=1):
        if not path.is_file():
            raise FileNotFoundError(f"参考图不存在: {path}")

        last_percent = -1

        def on_progress(uploaded: int, total: int) -> None:
            nonlocal last_percent
            percent = round(uploaded / total * 100) if total else 0
            if percent == last_percent:
                return
            last_percent = percent
            print(f"\r[{index}/{len(paths)}] 上传 {path.name}: {percent:3d}%", end="", flush=True)

        result = asset.upload_file(path, on_progress=on_progress)
        print()
        urls.append(result.url)

    return urls


def generate_model(image_urls: list[str], face_count: int) -> tuple[int, dict]:
    from manycore.aholo_sdk_lux3d import create_lux3d_client
    from manycore.aholo_sdk_core import AholoClientConfig

    lux3d = create_lux3d_client(
        AholoClientConfig(api_key=os.environ["AHOLO_API_KEY"], region="cn"),
    )
    task_id = lux3d.img_to_3d.create(
        imgs=image_urls,
        version="G1-Turbo",
        face_count=face_count,
        output_format=["glb"],
        enable_pbr=True,
        ai_predict_size=True,
    )
    print(f"Lux3D 任务已创建: {task_id}")
    print("正在生成 PBR 模型；官方建议每 10–15 秒轮询，通常需要数分钟……")
    return task_id, lux3d.tasks.wait_for(task_id, interval_ms=12_000, timeout_ms=1_800_000)


def select_glb_url(result: dict) -> str:
    outputs = result.get("outputs") or []
    contents = [
        item.get("content")
        for item in outputs
        if isinstance(item, dict)
        and isinstance(item.get("content"), str)
        and item.get("content") != "NOT_REQUESTED"
    ]
    if not contents:
        raise RuntimeError("任务成功但未返回可下载的模型")

    for content in contents:
        if urlparse(content).path.lower().endswith(".glb"):
            return content
    raise RuntimeError("任务输出中没有 GLB 文件")


def download_and_validate_glb(url: str, destination: Path) -> tuple[int, str, int]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".partial")
    digest = hashlib.sha256()
    total = 0

    request = Request(url, headers={"User-Agent": "ReachAI-IP-Showcase/1.0"})
    try:
        with urlopen(request, timeout=180) as response, partial.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                digest.update(chunk)
                total += len(chunk)

        with partial.open("rb") as model:
            header = model.read(12)
        if len(header) != 12 or header[:4] != b"glTF":
            raise RuntimeError("下载结果不是有效的二进制 glTF（GLB）")
        version, declared_length = struct.unpack("<II", header[4:12])
        if version != 2:
            raise RuntimeError(f"不支持的 GLB 版本: {version}")
        if declared_length != total:
            raise RuntimeError(
                f"GLB 长度校验失败：文件头声明 {declared_length} 字节，实际 {total} 字节",
            )

        partial.replace(destination)
        return total, digest.hexdigest(), version
    finally:
        if partial.exists():
            partial.unlink()


def write_metadata(
    destination: Path,
    *,
    task_id: int,
    input_count: int,
    face_count: int,
    byte_count: int,
    sha256: str,
    glb_version: int,
) -> None:
    metadata_path = destination.with_suffix(".metadata.json")
    metadata = {
        "generator": "AHOLO Lux3D",
        "generatorVersion": "G1-Turbo",
        "taskId": task_id,
        "inputCount": input_count,
        "primaryView": "front",
        "referenceOrder": ["front", "left-side", "back"],
        "faceCount": face_count,
        "enablePbr": True,
        "outputFormat": "glb",
        "glbVersion": glb_version,
        "file": destination.name,
        "bytes": byte_count,
        "sha256": sha256,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
    }
    metadata_path.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    inputs = [path.resolve() for path in args.inputs]
    output = args.output.resolve()
    api_key = ""

    try:
        api_key = require_api_key(gui=args.gui_key)
        image_urls = upload_references(inputs)
        task_id, result = generate_model(image_urls, args.face_count)
        model_url = select_glb_url(result)
        byte_count, sha256, glb_version = download_and_validate_glb(model_url, output)
        write_metadata(
            output,
            task_id=task_id,
            input_count=len(inputs),
            face_count=args.face_count,
            byte_count=byte_count,
            sha256=sha256,
            glb_version=glb_version,
        )
        print(f"模型已保存: {output}")
        print(f"GLB v{glb_version} · {byte_count:,} bytes · SHA-256 {sha256}")
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI should present a concise error.
        message = str(exc)
        if api_key:
            message = message.replace(api_key, "[REDACTED]")
        print(f"生成失败 [{type(exc).__name__}]: {message}", file=sys.stderr)
        return 1
    finally:
        os.environ.pop("AHOLO_API_KEY", None)


if __name__ == "__main__":
    raise SystemExit(main())
