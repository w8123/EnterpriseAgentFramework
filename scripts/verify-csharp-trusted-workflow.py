#!/usr/bin/env python3
"""Exercise a published workflow with the isolated SampleOrders employee identity.

Run while verify-csharp-isolated-e2e.py is holding its local-only services. Supply
the same REACHAI_CSHARP_E2E_ADMIN_PASSWORD and REACHAI_CSHARP_E2E_DEMO_PASSWORD
in this process environment. It creates fixture workflows, an Agent and an MCP
publication in that isolated database; it does not remove the test database.
Sample orders are in-memory data, not an external customer's business system.
Secrets and bearer tokens remain in this process and are not printed.
"""
import json
import os
import re
import subprocess
import sys
import uuid

import requests

BASE = 'http://127.0.0.1:18603'
SAMPLE = 'http://127.0.0.1:18789'


def call(session, method, path, body=None, expected=(200,)):
    response = session.request(method, path, json=body, timeout=90)
    if response.status_code not in expected:
        raise RuntimeError(f'{method} {path.split("?")[0]} returned HTTP {response.status_code}')
    return response.json() if response.content else None


def main():
    identity = subprocess.run(
        ['wsl.exe', '-d', 'yudao-redis', '-u', 'root', '--', 'mariadb',
         '--socket=/tmp/reachai-csharp-mysql.sock', '--batch', '--skip-column-names'],
        input=b'SELECT @@datadir, @@port;', stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
    ).stdout.decode('utf-8').strip()
    if identity != '/var/lib/reachai-csharp-mysql/\t13306':
        raise RuntimeError('isolated MariaDB identity mismatch')
    admin = requests.Session()
    admin.trust_env = False
    login = call(admin, 'POST', BASE + '/api/platform/auth/login',
                 {'username': 'admin', 'password': os.environ['REACHAI_CSHARP_E2E_ADMIN_PASSWORD']})
    admin.headers['X-ReachAI-CSRF'] = login['sessionId']
    projects = call(admin, 'GET', BASE + '/api/scan-projects')
    matching = [p for p in projects if p['projectCode'].startswith('csharp-e2e-')]
    if len(matching) != 1:
        raise RuntimeError('expected exactly one isolated C# test project')
    project = matching[0]
    project_id, code = project['id'], project['projectCode']
    graph = {'schemaVersion': 2,
             'inputSchema': {'type': 'object', 'properties': {'orderId': {'type': 'string'}}, 'required': ['orderId']},
             'nodes': [{'id': 'query', 'type': 'TOOL', 'ref': {'kind': 'TOOL', 'qualifiedName': code + ':order.query'},
                        'config': {'inputMapping': {'orderId': 'params.orderId'}, 'outputAlias': 'order_result'}}],
             'edges': [], 'entryNodeId': 'query', 'exitNodeIds': ['query']}
    wf = call(admin, 'POST', BASE + '/api/workflows',
              {'projectId': project_id, 'projectCode': code, 'keySlug': 'csharp-employee-' + uuid.uuid4().hex[:6],
               'name': 'CSharp Employee Test', 'workflowKind': 'GENERAL', 'executionEngine': 'GRAPH_SPEC',
               'graphSpecJson': json.dumps(graph, ensure_ascii=False)})
    wf_id = wf['id']
    draft = call(admin, 'GET', BASE + '/api/workflows/' + str(wf_id) + '/working-copy')
    publish = {'version': '1.0.0', 'rolloutPercent': 100, 'baseRevision': draft['revision']}
    call(admin, 'POST', BASE + '/api/workflows/' + str(wf_id) + '/versions/validate', publish)
    result = call(admin, 'POST', BASE + '/api/workflows/' + str(wf_id) + '/versions/publish', publish)
    print('PASS published deterministic C# workflow', flush=True)
    existing = call(admin, 'GET', BASE + '/api/mcp/publications')
    pub_url = None
    for item in existing.get('items', []):
        if item.get('name', '').startswith('csharp_fixture_'):
            previous_url = BASE + '/api/mcp/publications/' + str(item['id']) + '/employee-access'
            previous = call(admin, 'GET', previous_url)
            if previous.get('projectCode') == code:
                pub_url = BASE + '/api/mcp/publications/' + str(item['id'])
                wf_id = call(admin, 'GET', pub_url)['items'][0]['sourceRef']
                break
    if pub_url is None:
        publication = call(admin, 'POST', BASE + '/api/mcp/publications',
                           {'name': 'csharp_fixture_' + uuid.uuid4().hex[:6], 'description': 'isolated fixture'}, (201,))
        pub_url = BASE + '/api/mcp/publications/' + str(publication['id'])
        call(admin, 'POST', pub_url + '/items',
             {'sourceKind': 'WORKFLOW', 'sourceRef': str(wf_id), 'alias': 'csharp_order_query'}, (201,))
        call(admin, 'POST', pub_url + '/resolve-preview', {})
        check = call(admin, 'POST', pub_url + '/precheck', {})
        if not check.get('ok') or check.get('toolCount') != 1:
            raise RuntimeError('workflow publication precheck failed')
        call(admin, 'POST', pub_url + '/publish', {'irreversibleAcknowledged': False})
    call(admin, 'PUT', pub_url + '/employee-access',
         {'projectId': project_id, 'projectCode': code, 'displayName': 'CSharp fixture',
          'enabled': True, 'writeApprovalRequired': True})
    print('PASS published workflow to employee MCP', flush=True)
    agent = call(admin, 'POST', BASE + '/api/agents',
                 {'projectId': project_id, 'projectCode': code, 'keySlug': 'csharp-employee-' + uuid.uuid4().hex[:6],
                  'name': 'CSharp Employee Test', 'visibility': 'PROJECT', 'enabled': True})
    demo = requests.Session()
    demo.trust_env = False
    call(demo, 'POST', SAMPLE + '/demo/login',
         {'user': 'alice', 'password': os.environ['REACHAI_CSHARP_E2E_DEMO_PASSWORD']})
    token_result = call(demo, 'POST', SAMPLE + '/reachai/embed/token',
                        {'agentId': agent['id'], 'pageKey': 'orders', 'pageInstanceId': uuid.uuid4().hex,
                         'route': '/orders', 'origin': 'http://localhost:5200'})
    if not token_result.get('businessSessionBound'):
        raise RuntimeError('issued token is not bound to business session')
    token = token_result.get('token') or token_result.get('embedToken')
    if not token:
        raise RuntimeError('embed token response missing token')
    employee = requests.Session()
    employee.trust_env = False
    employee.headers['Authorization'] = 'Bearer ' + token
    options = call(employee, 'GET', BASE + '/api/embed/ai-tool-connections/options')
    print('PASS sample login and platform issued Embed token', flush=True)
    if not any(option.get('projectCode') == code for option in options.get('data', [])):
        raise RuntimeError('fixture project not visible to employee')
    issued = call(employee, 'POST', BASE + '/api/embed/ai-tool-connections',
                  {'toolKind': 'CODEX', 'systems': [code]})['data']
    setup = issued['setup']['content']
    secret_match = re.search(r'Bearer ([^"\s]+)', setup)
    if not secret_match:
        raise RuntimeError('employee connection secret missing')
    mcp = requests.Session()
    mcp.trust_env = False
    mcp.headers['Authorization'] = 'Bearer ' + secret_match.group(1)
    mcp.headers['MCP-Protocol-Version'] = '2025-11-25'
    tools = call(mcp, 'POST', BASE + '/mcp/employee',
                 {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list', 'params': {}})
    names = [tool['name'] for tool in tools.get('result', {}).get('tools', [])]
    if 'csharp_order_query' not in names:
        raise RuntimeError('pinned workflow is not visible to employee')
    for rule in call(admin, 'GET', BASE + '/api/tool-acl?roleCode=sales&size=500').get('records', []):
        if rule.get('projectCode') == code and rule.get('targetName') in [wf_id, code + ':order.query']:
            call(admin, 'DELETE', BASE + '/api/tool-acl/' + str(rule['id']))
    invoked = call(mcp, 'POST', BASE + '/mcp/employee',
                   {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call',
                    'params': {'name': 'csharp_order_query', 'arguments': {'orderId': 'A-100'}}}, (200, 403))
    if invoked.get('error', {}).get('code') != -32002:
        raise RuntimeError('employee workflow unexpectedly permitted without ACL')
    rules = []
    for target in [wf_id, code + ':order.query']:
        rule = call(admin, 'POST', BASE + '/api/tool-acl',
                    {'roleCode': 'sales', 'projectId': project_id, 'projectCode': code,
                     'targetKind': 'TOOL', 'targetName': target, 'permission': 'ALLOW', 'enabled': True}, (200, 201))
        rules.append(rule['id'])
    invoked = call(mcp, 'POST', BASE + '/mcp/employee',
                   {'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call',
                    'params': {'name': 'csharp_order_query', 'arguments': {'orderId': 'A-100'}}}, (200, 403))
    result = invoked.get('result', {})
    answer = result.get('structuredContent', {}).get('answer', '')
    if result.get('isError') or 'id=A-100' not in answer or 'tenant=tenant-a' not in answer:
        raise RuntimeError('trusted employee workflow did not return expected fixture order')
    print('PASS employee ACL denial and trusted workflow dispatch to C# fixture', flush=True)
    crossed = call(mcp, 'POST', BASE + '/mcp/employee',
                   {'jsonrpc': '2.0', 'id': 4, 'method': 'tools/call',
                    'params': {'name': 'csharp_order_query', 'arguments': {'orderId': 'B-100'}}}, (200, 403))
    cross_result = crossed.get('result', {})
    if (not cross_result.get('isError')
            or cross_result.get('structuredContent', {}).get('errorCode') != 'CAPABILITY_TOOL_EXECUTION_FAILED'
            or 'B-100' in json.dumps(crossed)):
        raise RuntimeError('cross-tenant fixture order was not rejected')
    print('PASS cross-tenant fixture order rejected', flush=True)
    for rule_id in rules:
        call(admin, 'DELETE', BASE + '/api/tool-acl/' + str(rule_id))
    denied = call(mcp, 'POST', BASE + '/mcp/employee',
                  {'jsonrpc': '2.0', 'id': 5, 'method': 'tools/call',
                   'params': {'name': 'csharp_order_query', 'arguments': {'orderId': 'A-100'}}}, (200, 403))
    if denied.get('error', {}).get('code') != -32002:
        raise RuntimeError('employee workflow unexpectedly permitted after ACL revocation')
    print('PASS same employee denied after ACL revocation', flush=True)
    call(demo, 'POST', SAMPLE + '/demo/logout', {})
    revoked = call(employee, 'GET', BASE + '/api/embed/ai-tool-connections/options', expected=(401,))
    print('PASS business logout revokes platform Embed token', flush=True)
    print('WORKFLOW_ID', wf_id, 'PROJECT_ID', project_id, 'PROJECT_CODE', code, flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('FAILED', type(exc).__name__, str(exc), file=sys.stderr)
        sys.exit(1)
