"""Start and exercise the SampleOrders fixture with test-only project credentials."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

with socket.socket() as reserved:
    reserved.bind(("127.0.0.1", 0))
    PORT = reserved.getsockname()[1]
ROOT = f"http://127.0.0.1:{PORT}"
SAMPLE = Path(__file__).resolve().parents[1] / "SampleOrders"
ASSEMBLY = SAMPLE / "bin" / "Release" / "net10.0" / "SampleOrders.dll"
if not ASSEMBLY.exists():
    raise SystemExit("Build SampleOrders in Release configuration before running this test")
environment = os.environ.copy()
environment.update({"ReachAI__RegistryUrl": "http://127.0.0.1:59999", "ReachAI__ProjectCode": "orders",
                    "ReachAI__ProjectName": "Sample Orders", "ReachAI__BaseUrl": ROOT,
                    "ReachAI__AppKey": "test-key", "ReachAI__AppSecret": "test-secret",
                    "REACHAI_DEMO_PASSWORD": "example-test-only", "ASPNETCORE_URLS": ROOT})
process = subprocess.Popen([sys.argv[1] if len(sys.argv) > 1 else "dotnet", str(ASSEMBLY)],
                           cwd=SAMPLE, env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def token(user, tenant, roles, project="orders", capability="order.query", expired=False, global_only=False):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
    now = int(time.time())
    header = encode({"alg": "HS256", "typ": "JWT"})
    payload = encode({"projectCode": project, "appKey": "test-key", "capabilityName": capability,
                      ("globalUserId" if global_only else "externalUserId"): user, "tenantId": tenant, "roles": roles,
                      "iat": now - 120 if expired else now, "exp": now - 60 if expired else now + 60})
    content = header + b"." + payload
    signature = base64.urlsafe_b64encode(hmac.new(b"test-secret", content, hashlib.sha256).digest()).rstrip(b"=")
    return (content + b"." + signature).decode()


def post(path, body, auth=None):
    headers = {"Content-Type": "application/json"}
    if auth:
        headers["X-ReachAI-Invocation-Token"] = auth
    request = urllib.request.Request(ROOT + path, data=json.dumps(body).encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None


try:
    deadline = time.monotonic() + 30
    while True:
        if process.poll() is not None:
            raise RuntimeError("SampleOrders exited before becoming ready")
        try:
            with urllib.request.urlopen(ROOT + "/demo/me", timeout=1):
                pass
            break
        except urllib.error.HTTPError as error:
            if error.code == 401:
                break
            raise
        except (urllib.error.URLError, TimeoutError):
            if time.monotonic() >= deadline:
                raise RuntimeError("SampleOrders startup timed out")
            time.sleep(0.1)
    query = "/reachai/capabilities/order.query/invoke"
    complete = "/reachai/capabilities/order.complete/invoke"
    status, result = post(query, {"orderId": "A-100"}, token("alice", "tenant-a", ["sales"]))
    assert status == 200 and result["id"] == "A-100" and result["tenant"] == "tenant-a"
    status, result = post(query, {"orderId": "A-100"}, token("alice", "tenant-a", ["sales"], global_only=True))
    assert status == 200 and result["id"] == "A-100" and result["tenant"] == "tenant-a"
    assert post(query, {"orderId": "A-100"}, token("", "tenant-a", ["sales"], global_only=True))[0] == 401
    assert post(query, {"orderId": "A-100"})[0] == 401
    assert post(query, {"orderId": "A-100"}, token("alice", "tenant-a", ["sales"], project="other"))[0] == 401
    assert post(query, {"orderId": "A-100"}, token("alice", "tenant-a", ["sales"], expired=True))[0] == 401
    assert post(query, {"orderId": "B-100"}, token("alice", "tenant-a", ["sales"]))[0] == 500
    assert post(query, {"orderId": "A-100"}, token("bob", "tenant-a", ["manager"]))[0] == 500
    assert post(complete, {"orderId": "A-100"}, token("alice", "tenant-a", ["sales"], capability="order.complete"))[0] == 403
    status, result = post(complete, {"orderId": "B-100"}, token("bob", "tenant-b", ["manager"], capability="order.complete", global_only=True))
    assert status == 200 and result["status"] == "COMPLETED"
    # Invalid input is rejected before business code runs (400), never reported as an execution failure.
    assert post(query, {"orderId": "A-100", "extra": 1}, token("alice", "tenant-a", ["sales"]))[0] == 400
    assert post(query, [], token("alice", "tenant-a", ["sales"]))[0] == 400
    assert post("/reachai/registry/capabilities/sync", {})[0] == 401
    assert post("/reachai/embed/token", {"agentId": "x", "pageInstanceId": "x"})[0] == 401
    def concurrent_query(index):
        alice = index % 2 == 0
        user, tenant, role, order = ("alice", "tenant-a", "sales", "A-100") if alice else ("bob", "tenant-b", "manager", "B-100")
        status, result = post(query, {"orderId": order}, token(user, tenant, [role]))
        return status == 200 and result["tenant"] == tenant and result["id"] == order
    with ThreadPoolExecutor(max_workers=12) as executor:
        assert all(executor.map(concurrent_query, range(48)))
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    login = urllib.request.Request(ROOT + "/demo/login", data=json.dumps({"user": "alice", "password": "example-test-only"}).encode(), headers={"Content-Type": "application/json"}, method="POST")
    with client.open(login, timeout=10) as response:
        assert json.load(response)["tenant"] == "tenant-a"
    with client.open(ROOT + "/demo/me", timeout=10) as response:
        assert json.load(response)["user"] == "alice"
    logout = urllib.request.Request(ROOT + "/demo/logout", data=b"{}", headers={"Content-Type": "application/json"}, method="POST")
    try:
        client.open(logout, timeout=10)
        raise AssertionError("unreachable revocation must report failure")
    except urllib.error.HTTPError as error:
        assert error.code == 502
    try:
        client.open(ROOT + "/demo/me", timeout=10)
        raise AssertionError("business session remained logged in")
    except urllib.error.HTTPError as error:
        assert error.code == 401
    print("Sample host signed invocation and tenant isolation passed")
finally:
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
