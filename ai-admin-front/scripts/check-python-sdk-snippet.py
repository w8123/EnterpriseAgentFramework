"""Execute the actual wizard's Python template using the installed SDK, without starting a server."""
import os
from pathlib import Path
import re
import tempfile

from cryptography.fernet import Fernet
from fastapi.testclient import TestClient

source = (Path(__file__).resolve().parent.parent /
          "src/views/registry/composables/useSdkAccessWizardPythonSnippets.ts").read_text(encoding="utf-8")
match = re.search(r"return `(.+?)`\s*\n", source, re.DOTALL)
if match is None:
    raise AssertionError("Python wizard template not found")
snippet = match.group(1)
substitutions = {
    "registry": '"http://127.0.0.1:18603"',
    "projectCode": '"snippet-test"',
    "projectName": '"测试项目"',
    "baseUrl": '"http://127.0.0.1:18790"',
}
for key, value in substitutions.items():
    placeholder = "${" + key + "}"
    if placeholder not in snippet:
        raise AssertionError(f"missing {placeholder}")
    snippet = snippet.replace(placeholder, value)
if "${" in snippet:
    raise AssertionError("unexpected template interpolation")
with tempfile.TemporaryDirectory(prefix="reachai-snippet-") as directory:
    os.environ.update({
        "REACHAI_ENROLLMENT_TOKEN": "test-only-token",
        "REACHAI_BUSINESS_ORIGIN": "http://127.0.0.1:18790",
        "REACHAI_CREDENTIAL_FILE": str(Path(directory) / "credentials"),
        "REACHAI_CREDENTIAL_KEY": Fernet.generate_key().decode(),
    })
    scope = {"__name__": "__main__"}
    exec(compile(snippet, "python-wizard-snippet", "exec"), scope)
    # Start the real app lifespan while replacing registry I/O with no-ops;
    # an unsigned request still reaches the real authentication boundary.
    async def no_network(*args, **kwargs):
        return None
    for method in ("register", "heartbeat", "offline", "close"):
        setattr(scope["reach"].registry, method, no_network)
    with TestClient(scope["app"]) as client:
        assert client.post("/reachai/registry/capabilities/sync").status_code == 401
    registrations = scope["reach"].catalog.registrations()
    assert len(registrations) == 1 and registrations[0]["name"] == "order.query"
    assert registrations[0]["metadata"]["resultSchema"]["type"] == "object"
print("Python wizard template registered its typed capability and routes")
