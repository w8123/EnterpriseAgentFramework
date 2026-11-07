"""Build and verify the exact Python SDK wheel distributed by Control."""
import hashlib
import base64
import csv
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "reachai-python"
OUTPUT = ROOT / "reachai-control-service/src/main/resources/ai-assist/artifacts/python-sdk"
NAME = "reachai_fastapi-1.0.0-py3-none-any.whl"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inputs():
    return [SOURCE / "pyproject.toml", *sorted((SOURCE / "src").rglob("*.py"))]


def source_digest():
    h = hashlib.sha256()
    for path in inputs():
        data = path.read_bytes()
        if b"\r\n" in data:
            raise ValueError(f"Python SDK source must use LF line endings: {path}")
        h.update(f"{path.relative_to(SOURCE).as_posix()}\0{digest(data)}\0{len(data)}\n".encode())
    data = Path(__file__).read_bytes().replace(b"\r\n", b"\n")
    h.update(f"@pack-script\0{digest(data)}\0{len(data)}\n".encode())
    return h.hexdigest()


def verify():
    data = (OUTPUT / NAME).read_bytes()
    if digest(data) != (OUTPUT / f"{NAME}.sha256").read_text().strip():
        raise ValueError("Python SDK wheel digest mismatch")
    if source_digest() != (OUTPUT / f"{NAME}.source.sha256").read_text().strip():
        raise ValueError("Python SDK packaged source is stale; rebuild the wheel")
    with zipfile.ZipFile(OUTPUT / NAME) as wheel:
        if "reachai_fastapi-1.0.0.dist-info/METADATA" not in wheel.namelist():
            raise ValueError("Wheel metadata does not match the distribution contract")
    print(f"Python SDK wheel {digest(data)} source {source_digest()}")


if sys.argv[1:] == ["--check"]:
    verify()
elif len(sys.argv) == 1:
    before = source_digest()
    with tempfile.TemporaryDirectory(prefix="reachai-python-pack-") as tmp:
        tmp = Path(tmp)
        for source in inputs():
            target = tmp / "source" / source.relative_to(SOURCE)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        env = dict(os.environ, SOURCE_DATE_EPOCH="1767225600")
        subprocess.run([sys.executable, "-m", "pip", "wheel", "--no-deps", "--no-build-isolation", "--ignore-requires-python",
                        "--wheel-dir", str(tmp / "out"), str(tmp / "source")], check=True, env=env)
        original = tmp / "out" / NAME
        OUTPUT.mkdir(parents=True, exist_ok=True)
        # Normalize the backend's platform-specific METADATA newlines, then regenerate
        # RECORD hashes so installers can verify the canonical wheel contents.
        with zipfile.ZipFile(original) as src, zipfile.ZipFile(OUTPUT / NAME, "w") as dst:
            records = {}
            for name in sorted(src.namelist()):
                if name.endswith(".dist-info/RECORD"):
                    record_name = name
                    continue
                data = src.read(name)
                if name.endswith(".dist-info/METADATA"):
                    data = data.replace(b"\r\n", b"\n")
                records[name] = data
            record = io.StringIO(newline="")
            writer = csv.writer(record, lineterminator="\n")
            for name, data in records.items():
                encoded = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
                writer.writerow((name, "sha256=" + encoded, len(data)))
            writer.writerow((record_name, "", ""))
            records[record_name] = record.getvalue().encode("utf-8")
            for name, data in sorted(records.items()):
                entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = 0o100644 << 16
                entry.compress_type = zipfile.ZIP_STORED
                dst.writestr(entry, data)
        if source_digest() != before:
            raise ValueError("Python SDK changed during packaging; rebuild the wheel")
        (OUTPUT / f"{NAME}.sha256").write_text(digest((OUTPUT / NAME).read_bytes()) + "\n", encoding="utf-8")
        (OUTPUT / f"{NAME}.source.sha256").write_text(before + "\n", encoding="utf-8")
    verify()
else:
    raise SystemExit("Usage: python scripts/pack-python-sdk-artifacts.py [--check]")
