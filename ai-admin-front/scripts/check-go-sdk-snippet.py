"""Compile the actual Go wizard template against the distributed source ZIP."""
import json
import base64
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

root = Path(__file__).resolve().parent.parent.parent
source = (root / 'ai-admin-front/src/views/registry/composables/useSdkAccessWizardGoSnippets.ts').read_text(encoding='utf-8')
match = re.search(r'return `(.+?)`\s*\n', source, re.DOTALL)
if match is None:
    raise AssertionError('Go wizard template not found')
snippet = match.group(1)
for key, value in {'registry': 'http://127.0.0.1:18603', 'projectCode': 'snippet-test',
                   'projectName': '测试项目', 'baseUrl': 'http://127.0.0.1:18790'}.items():
    marker = '${' + key + '}'
    if marker not in snippet:
        raise AssertionError(f'missing {marker}')
    snippet = snippet.replace(marker, json.dumps(value, ensure_ascii=False))
if '${' in snippet:
    raise AssertionError('unexpected template interpolation')
subprocess.run([sys.executable, str(root / 'scripts/pack-go-sdk-artifacts.py'), '--check'], check=True)
archive_path = root / 'reachai-control-service/src/main/resources/ai-assist/artifacts/go-sdk/reachai-go-sdk-1.0.0.zip'
with tempfile.TemporaryDirectory(prefix='reachai-go-snippet-') as directory:
    target = Path(directory)
    module = target / 'third_party/reachai-go'
    with zipfile.ZipFile(archive_path) as archive:
        for entry in archive.infolist():
            path = Path(entry.filename)
            if not entry.filename.startswith('reachai-go/') or path.is_absolute() or '..' in path.parts or entry.is_dir():
                raise ValueError('unexpected archive entry')
            relative = Path(*path.parts[1:])
            dest = module / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(archive.read(entry))
    (target / 'go.mod').write_text(
        'module example.com/go-snippet\n\ngo 1.22\n\nrequire github.com/w8123/EnterpriseAgentFramework/reachai-go v1.0.0\n'
        'replace github.com/w8123/EnterpriseAgentFramework/reachai-go => ./third_party/reachai-go\n', encoding='utf-8')
    (target / 'main.go').write_text(snippet, encoding='utf-8')
    env = dict(os.environ, GOCACHE=str(target / 'cache'), GOPATH=str(target / 'gopath'), GOWORK='off', GOPROXY='off', GOTOOLCHAIN='local')
    subprocess.run(['go', 'build', '-o', str(target / 'example'), '.'], cwd=target, env=env, check=True)
    env.update(REACHAI_CREDENTIAL_KEY=base64.b64encode(bytes(range(32))).decode('ascii'),
               REACHAI_CREDENTIAL_FILE=str(target / 'credentials.json'),
               REACHAI_ENROLLMENT_TOKEN='fixture-enrollment-token',
               REACHAI_BUSINESS_ORIGIN='http://127.0.0.1:18790')
    subprocess.run(['go', 'run', '.'], cwd=target, env=env, check=True)
print('Go wizard template compiled and registered the sample against the verified distribution')
