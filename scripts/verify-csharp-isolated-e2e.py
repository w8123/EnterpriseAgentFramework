#!/usr/bin/env python3
"""Exercise platform + C# sample against an explicitly verified local-only database.

The sample's orders are in-memory fixture data. Credentials are generated in memory
unless synthetic test passwords are provided via environment. The script stops only
processes that it started itself; its database, account and state are retained.
With --hold-seconds, create a STOP file in the printed LOG_DIRECTORY to end the
hold early and allow the script to stop its child processes.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import pathlib
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import uuid

import pymysql
import requests


def expect(condition, message):
    if not condition:
        raise RuntimeError(message)


def run_wsl(sql):
    result = subprocess.run(
        ['wsl.exe', '-d', 'yudao-redis', '-u', 'root', '--', 'mariadb',
         '--socket=/tmp/reachai-csharp-mysql.sock', '--batch', '--skip-column-names'],
        input=sql.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode:
        raise RuntimeError('local isolated database command failed')
    return result.stdout.decode('utf-8').strip()


def wait_http(session, url, process, accepted, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('service exited while waiting: ' + url)
        try:
            response = session.get(url, timeout=2)
            if response.status_code in accepted:
                return
        except requests.RequestException:
            pass
        time.sleep(1)
    raise RuntimeError('service startup timed out: ' + url)


def call(session, method, url, *, expected=(200,), body=None, timeout=30):
    response = session.request(method, url, json=body, timeout=timeout)
    if response.status_code not in expected:
        raise RuntimeError(method + ' ' + url.split('?', 1)[0] + ' returned HTTP ' + str(response.status_code))
    return response


def jwt(secret, claims):
    def encode(value):
        raw = json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
        return base64.urlsafe_b64encode(raw).rstrip(b'=')
    message = encode({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + encode(claims)
    signature = hmac.new(secret.encode('utf-8'), message, hashlib.sha256).digest()
    return (message + b'.' + base64.urlsafe_b64encode(signature).rstrip(b'=')).decode('ascii')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend-root', required=True, type=pathlib.Path)
    parser.add_argument('--dotnet', required=True, type=pathlib.Path)
    parser.add_argument('--sample-dll', required=True, type=pathlib.Path)
    parser.add_argument('--java', required=True, type=pathlib.Path)
    parser.add_argument('--expected-package-sha256', required=True)
    parser.add_argument('--hold-seconds', type=int, default=0)
    args = parser.parse_args()
    for name in ['model', 'knowledge', 'capability', 'runtime', 'control']:
        expect((args.backend_root / ('reachai-' + name + '-service') / 'target' /
                ('reachai-' + name + '-service-1.0.0-SNAPSHOT.jar')).is_file(), name + ' jar missing')
    expect(args.dotnet.is_file() and args.sample_dll.is_file() and args.java.is_file(), 'runtime artifact missing')
    for port in [13306, 18601, 18602, 18603, 18604, 18605, 18789, 19531]:
        with socket.socket() as test:
            listening = test.connect_ex(('127.0.0.1', port)) == 0
        expect(listening == (port == 13306), 'unexpected listener state for port ' + str(port))

    identity = run_wsl('SELECT @@datadir, @@port, @@skip_networking, VERSION();')
    expect(identity.startswith('/var/lib/reachai-csharp-mysql/\t13306\t0\t11.'),
           'database identity is not the isolated MariaDB instance')
    suffix = uuid.uuid4().hex[:10]
    database = 'ra_csharp_' + suffix
    db_user = 'cs_' + suffix
    db_password = secrets.token_hex(24)
    admin_password = os.environ.get('REACHAI_CSHARP_E2E_ADMIN_PASSWORD') or secrets.token_urlsafe(32)
    expect(len(admin_password) >= 12, 'isolated admin password must contain at least 12 characters')
    demo_password = os.environ.get('REACHAI_CSHARP_E2E_DEMO_PASSWORD') or secrets.token_urlsafe(32)
    app_key = 'csharp-e2e-' + suffix
    app_secret = secrets.token_hex(32)
    project_code = 'csharp-e2e-' + suffix
    run_wsl('CREATE DATABASE `' + database + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n'
            "CREATE USER '" + db_user + "'@'%' IDENTIFIED BY '" + db_password + "';\n"
            "GRANT ALL PRIVILEGES ON `" + database + "`.* TO '" + db_user + "'@'%';\n")
    db = pymysql.connect(host='127.0.0.1', port=13306, user=db_user, password=db_password,
                         database=database, connect_timeout=5, charset='utf8mb4')
    with db.cursor() as cursor:
        cursor.execute('SELECT DATABASE(), @@datadir, @@port, COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()')
        row = cursor.fetchone()
        expect(row == (database, '/var/lib/reachai-csharp-mysql/', 13306, 0), 'new test schema is not empty or isolated')
    db.close()
    print('PASS isolated empty MariaDB schema', flush=True)

    run_root = pathlib.Path(os.environ['LOCALAPPDATA']) / 'ReachAI' / 'csharp-e2e'
    run_root.mkdir(parents=True, exist_ok=True)
    state = pathlib.Path(tempfile.mkdtemp(prefix='run-', dir=run_root))
    username = os.environ.get('USERNAME')
    expect(bool(username), 'current Windows user unavailable')
    subprocess.run(['icacls', str(state), '/inheritance:r', '/grant:r', username + ':(OI)(CI)F'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)
    config = state / 'isolated.env'
    config.write_text('# isolated test configuration; process environment owns credentials\n', encoding='utf-8')
    # Do not inherit Spring profiles, external service URLs, proxy settings,
    # datasource settings, JVM flags, or credentials from the caller's shell.
    os_keys = ('SystemRoot', 'WINDIR', 'PATH', 'PATHEXT', 'COMSPEC', 'TEMP', 'TMP',
               'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'USERNAME', 'USERDOMAIN',
               'PROGRAMFILES', 'PROGRAMFILES(X86)', 'PROCESSOR_ARCHITECTURE')
    env = {key: os.environ[key] for key in os_keys if key in os.environ}
    env.update({
        'REACHAI_HOME': str(state / 'home'), 'REACHAI_CONFIG_FILE': str(config),
        'AI_MYSQL_HOST': '127.0.0.1', 'AI_MYSQL_PORT': '13306', 'AI_MYSQL_DATABASE': database,
        'AI_MYSQL_USER': db_user, 'AI_MYSQL_PASSWORD': db_password,
        'AI_MYSQL_URL': 'jdbc:mysql://127.0.0.1:13306/' + database + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
        'SERVER_ADDRESS': '127.0.0.1', 'REACHAI_BOOTSTRAP_ADMIN_PASSWORD': admin_password,
        'REACHAI_INTERNAL_SERVICE_SECRET': secrets.token_hex(32),
        'REACHAI_AUTOMATION_ENABLED': 'false', 'REACHAI_EVAL_WORKER_ENABLED': 'false',
        'REACHAI_MANAGED_EXECUTOR_ENABLED': 'false', 'MODEL_CATALOG_SYNC_ENABLED': 'false',
        'RUNTIME_SESSION_MEMORY_JSON_DIR': str(state / 'runtime-session-state'),
        'RUNTIME_SKILL_CACHE_DIR': str(state / 'runtime-skill-cache'),
        'RUNTIME_SESSION_MEMORY_STATE_STORE': 'json',
        'MILVUS_HOST': '127.0.0.1', 'MILVUS_PORT': '19531',
        'REACHAI_DOCLING_ENABLED': 'false', 'REACHAI_KNOWLEDGE_DOCUMENT_IMPORT_WORKER_ENABLED': 'false',
        'REACHAI_SKILL_MARKET_ENABLED': 'false',
    })
    processes = []
    handles = []
    succeeded = False
    session = requests.Session()
    session.trust_env = False

    def start_process(name, command, process_env):
        output = (state / (name + '.log')).open('wb')
        handles.append(output)
        process = subprocess.Popen(command, cwd=args.backend_root, env=process_env, stdout=output,
                                   stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        processes.append(process)
        return process

    def start_sample(mode):
        sample_env = {key: value for key, value in env.items() if not key.startswith('ReachAI__')}
        sample_env.update({
            'ASPNETCORE_URLS': 'http://127.0.0.1:18789', 'REACHAI_DEMO_PASSWORD': demo_password,
            'ReachAI__RegistryUrl': 'http://127.0.0.1:18603', 'ReachAI__ProjectCode': project_code,
            'ReachAI__ProjectName': 'C# 集成样例', 'ReachAI__BaseUrl': 'http://127.0.0.1:18789',
            'ReachAI__Environment': 'test', 'ReachAI__InstanceId': 'csharp-e2e-' + suffix,
            'ReachAI__CredentialFilePath': str(state / 'sample-credential.bin'),
        })
        if mode == 'enrollment':
            sample_env['ReachAI__EnrollmentToken'] = enrollment
        else:
            sample_env['ReachAI__AppKey'] = app_key
            sample_env['ReachAI__AppSecret'] = app_secret
        process = start_process('sample-' + mode, [str(args.dotnet), str(args.sample_dll)], sample_env)
        wait_http(session, 'http://127.0.0.1:18789/demo/me', process, {401}, 90)
        return process

    try:
        for name, port in [('model', 18601), ('knowledge', 18602), ('capability', 18605), ('runtime', 18604), ('control', 18603)]:
            jar = args.backend_root / ('reachai-' + name + '-service') / 'target' / ('reachai-' + name + '-service-1.0.0-SNAPSHOT.jar')
            proc = start_process(name, [str(args.java), '-Duser.home=' + str(state / 'java-home'),
                                        '-Xmx512m', '-jar', str(jar)], env)
            health = '/ai/actuator/health' if name == 'knowledge' else '/actuator/health'
            wait_http(session, 'http://127.0.0.1:' + str(port) + health, proc, {200, 404} if name == 'knowledge' else {200}, 240)
            print('PASS local service ' + name, flush=True)

        # Tomcat's health endpoint can answer before ApplicationRunner inserts the
        # development administrator; wait for its actual row before logging in.
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            probe = pymysql.connect(host='127.0.0.1', port=13306, user=db_user, password=db_password,
                                    database=database, connect_timeout=5, charset='utf8mb4')
            try:
                with probe.cursor() as cursor:
                    cursor.execute("SELECT COUNT(*) FROM control_platform_user WHERE username='admin' AND status='ACTIVE'")
                    if cursor.fetchone()[0] == 1:
                        break
            finally:
                probe.close()
            time.sleep(1)
        else:
            raise RuntimeError('bootstrap administrator not initialized')

        base = 'http://127.0.0.1:18603'
        login = call(session, 'POST', base + '/api/platform/auth/login', body={'username': 'admin', 'password': admin_password}).json()
        csrf = login.get('sessionId')
        expect(isinstance(csrf, str) and bool(csrf), 'platform session did not provide CSRF identity')
        session.headers.update({'X-ReachAI-CSRF': csrf})
        package_url = base + '/api/ai-assist/artifacts/dotnet-sdk/ReachAI.AspNetCore/1.0.0.nupkg'
        package = call(session, 'GET', package_url).content
        expected_hash = call(session, 'GET', package_url + '.sha256').text.strip().lower()
        actual_hash = hashlib.sha256(package).hexdigest()
        expect(actual_hash == expected_hash == args.expected_package_sha256.lower(), 'NuGet HTTP artifact digest mismatch')
        print('PASS Control authenticated NuGet download and digest', flush=True)

        issued = call(session, 'POST', base + '/api/platform/registry-enrollments', expected=(200, 201),
                      body={'projectCode': project_code}).json()
        enrollment = issued.get('enrollmentToken')
        expect(isinstance(enrollment, str) and bool(enrollment), 'enrollment token unavailable')
        sample = start_sample('enrollment')
        project = None
        for _ in range(90):
            response = call(session, 'GET', base + '/api/scan-projects')
            project = next((item for item in response.json() if item.get('projectCode') == project_code), None)
            if project:
                instances = call(session, 'GET', base + '/api/registry/projects/' + project_code + '/instances').json()
                if any(row.get('status') == 'ONLINE' for row in instances):
                    break
            time.sleep(1)
        else:
            raise RuntimeError('C# enrollment or heartbeat not observed')
        project_id = project['id']
        receipt = call(session, 'POST', base + '/api/scan-projects/' + str(project_id) + '/sdk-sync/scan',
                       body={}, timeout=150).json()
        expect(receipt.get('capabilityCount') == 2, 'C# callback did not synchronize both methods')
        print('PASS C# enrollment, heartbeat and signed full-inventory callback', flush=True)

        sample.terminate()
        sample.wait(timeout=15)
        call(session, 'PATCH', base + '/api/scan-projects/' + str(project_id) + '/registry-credential',
             body={'appKey': app_key, 'appSecret': app_secret})
        sample = start_sample('static')
        time.sleep(2)
        endpoint = 'http://127.0.0.1:18789/reachai/capabilities/'
        call(session, 'POST', endpoint + 'order.query/invoke', expected=(401,), body={'orderId': 'A-100'})
        now = int(time.time())
        def execute(name, tenant, user, roles, order, expected):
            claims = {'projectCode': project_code, 'appKey': app_key, 'capabilityName': name,
                      'tenantId': tenant, 'externalUserId': user, 'roles': roles, 'iat': now,
                      'exp': now + 60, 'attributes': {}}
            return session.post(endpoint + name + '/invoke', json={'orderId': order}, timeout=15,
                                headers={'X-ReachAI-Invocation-Token': jwt(app_secret, claims)})
        result = execute('order.query', 'tenant-a', 'alice', ['sales'], 'A-100', 200)
        expect(result.status_code == 200 and result.json().get('id') == 'A-100', 'C# authorized read failed')
        result = execute('order.query', 'tenant-b', 'bob', ['manager'], 'B-100', 200)
        expect(result.status_code == 200 and result.json().get('id') == 'B-100', 'second tenant authorized read failed')
        result = execute('order.query', 'tenant-b', 'bob', ['manager'], 'A-100', 500)
        expect(result.status_code == 500 and result.json().get('code') == 'CAPABILITY_EXECUTION_FAILED'
               and 'A-100' not in result.text, 'cross-tenant failure leaked fixture order or had unexpected response')
        expect(execute('order.complete', 'tenant-a', 'alice', ['sales'], 'A-100', 403).status_code == 403, 'unauthorized role was not rejected')
        result = execute('order.complete', 'tenant-b', 'bob', ['manager'], 'B-100', 200)
        expect(result.status_code == 200 and result.json().get('status') == 'COMPLETED', 'C# authorized fixture write failed')
        print('PASS SDK invocation authentication, fixture reads, cross-tenant no-data and fixture write', flush=True)

        demo = requests.Session()
        demo.trust_env = False
        call(demo, 'POST', 'http://127.0.0.1:18789/reachai/embed/token', expected=(401,), body={})
        call(demo, 'POST', 'http://127.0.0.1:18789/demo/login', body={'user': 'alice', 'password': demo_password})
        me = call(demo, 'GET', 'http://127.0.0.1:18789/demo/me').json()
        expect(me.get('user') == 'alice' and me.get('tenant') == 'tenant-a', 'sample login identity mismatch')
        print('PASS sample login and unauthorized broker rejection', flush=True)
        print('SCOPE in-memory fixture; direct JWT calls do not prove platform workflow, approval, or external business acceptance', flush=True)
        print('LOG_DIRECTORY ' + str(state), flush=True)
        succeeded = True
    except Exception as exc:
        print('CHECK_FAILED ' + type(exc).__name__ + ': ' + str(exc), file=sys.stderr, flush=True)
        raise
    finally:
        if succeeded and args.hold_seconds > 0 and len(processes) >= 5 and processes[4].poll() is None:
            print('HOLD local services available for ' + str(args.hold_seconds) + ' seconds', flush=True)
            deadline = time.monotonic() + args.hold_seconds
            while time.monotonic() < deadline and not (state / 'STOP').exists():
                time.sleep(1)
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
        for process in reversed(processes):
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
        for handle in handles:
            handle.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('FAILED ' + type(exc).__name__ + ': ' + str(exc), file=sys.stderr, flush=True)
        sys.exit(1)
