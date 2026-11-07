"""Package and verify the Go SDK source archive distributed by Control."""
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'reachai-go'
OUTPUT = ROOT / 'reachai-control-service/src/main/resources/ai-assist/artifacts/go-sdk'
NAME = 'reachai-go-sdk-1.0.0.zip'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inputs():
    files = [SOURCE / 'go.mod', SOURCE / 'README.md', SOURCE / 'LICENSE']
    if (SOURCE / 'go.sum').is_file():
        files.append(SOURCE / 'go.sum')
    files.extend(path for path in SOURCE.rglob('*.go') if not path.name.endswith('_test.go'))
    return sorted(files, key=lambda path: path.relative_to(SOURCE).as_posix())


def source_digest():
    h = hashlib.sha256()
    for path in inputs():
        if path.is_symlink() or not path.is_file():
            raise ValueError(f'invalid SDK source: {path}')
        data = path.read_bytes()
        if b'\r\n' in data:
            raise ValueError(f'Go SDK source must use LF line endings: {path}')
        h.update(f'{path.relative_to(SOURCE).as_posix()}\0{digest(data)}\0{len(data)}\n'.encode())
    data = Path(__file__).read_bytes().replace(b'\r\n', b'\n')
    h.update(f'@pack-script\0{digest(data)}\0{len(data)}\n'.encode())
    return h.hexdigest()


def verify():
    data = (OUTPUT / NAME).read_bytes()
    if digest(data) != (OUTPUT / f'{NAME}.sha256').read_text(encoding='ascii').strip():
        raise ValueError('Go SDK archive digest mismatch')
    if source_digest() != (OUTPUT / f'{NAME}.source.sha256').read_text(encoding='ascii').strip():
        raise ValueError('Go SDK packaged source is stale')
    expected = {'reachai-go/' + path.relative_to(SOURCE).as_posix() for path in inputs()}
    with zipfile.ZipFile(OUTPUT / NAME) as archive:
        names = archive.namelist()
        if len(names) != len(expected) or set(names) != expected:
            raise ValueError('Go SDK archive contents mismatch')
        for path in inputs():
            if archive.read('reachai-go/' + path.relative_to(SOURCE).as_posix()) != path.read_bytes():
                raise ValueError('Go SDK archive source mismatch')
    print(f'Go SDK archive {digest(data)} source {source_digest()}')


if sys.argv[1:] == ['--check']:
    verify()
elif len(sys.argv) == 1:
    before = source_digest()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix='reachai-go-', suffix='.zip', dir=OUTPUT, delete=False) as temporary:
        temp_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(temp_path, 'w', compression=zipfile.ZIP_STORED) as archive:
            for path in inputs():
                info = zipfile.ZipInfo('reachai-go/' + path.relative_to(SOURCE).as_posix(), (2020, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, path.read_bytes())
        if before != source_digest():
            raise ValueError('Go SDK source changed during packaging')
        data = temp_path.read_bytes()
        os.replace(temp_path, OUTPUT / NAME)
        (OUTPUT / f'{NAME}.sha256').write_text(digest(data) + '\n', encoding='ascii', newline='\n')
        (OUTPUT / f'{NAME}.source.sha256').write_text(before + '\n', encoding='ascii', newline='\n')
        verify()
    finally:
        if temp_path.exists():
            temp_path.unlink()
else:
    raise SystemExit('usage: python scripts/pack-go-sdk-artifacts.py [--check]')
