import hashlib
import json
import time

import httpx
import pytest
from fastapi import FastAPI

from reachai_fastapi import (BusinessIndexAccessScope, BusinessIndexRequest, BusinessIndexResolution,
                             ReachAI, ReachAIOptions, current_invocation)
from reachai_fastapi.protocol import body_signature
from test_integration import make_token


def test_authorize_row_requires_matching_tenant_object_and_permission():
    for tenant, actual, visible in [('other', 'id-1', True), ('tenant', 'id-2', True), ('tenant', 'id-1', False)]:
        with pytest.raises(PermissionError):
            BusinessIndexAccessScope.authorize_row('tenant', tenant, 'alice', 'demo', 'order', 'id-1', actual, visible)


@pytest.mark.asyncio
async def test_signed_index_writes_match_exact_sent_bytes():
    observed = []
    async def upstream(request): observed.append(request); return httpx.Response(200, json={'code': 200})
    async with httpx.AsyncClient(transport=httpx.MockTransport(upstream)) as http:
        sdk = ReachAI(ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret'), http_client=http)
        await sdk.index_upsert('orders', {'bizId': '订单一', 'value': 4})
        req = observed[0]
        assert req.headers['X-ReachAI-Body-SHA256'] == hashlib.sha256(req.content).hexdigest()
        assert req.headers['X-ReachAI-Signature'] == body_signature('POST', req.url.path, 'demo', 'key', req.headers['X-ReachAI-Timestamp'], req.headers['X-ReachAI-Nonce'], req.content, 'secret')
        assert 'X-ReachAI-Body-Signature' not in req.headers


@pytest.mark.asyncio
async def test_resolver_rejects_scope_swap_and_plain_dict():
    async def bridge(context, call_next): return await call_next()
    sdk = ReachAI(ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret'), security_bridge=bridge)
    @sdk.capability(name='index.resolve', side_effect='READ', business_identity_required=True, business_index_resolver=True)
    async def resolve(request: BusinessIndexRequest) -> BusinessIndexResolution:
        if request.resource_id == 'plain': return {'data': {'value': 1}}
        ctx = current_invocation.get()
        resource = 'other' if request.resource_id == 'swap' else request.resource_id
        scope = BusinessIndexAccessScope.authorize_row(ctx.tenant_id, ctx.tenant_id, ctx.external_user_id,
                                                       ctx.project_code, request.resource_type, resource, resource, True)
        return BusinessIndexResolution.create_authorized(scope, 'orders', 'v1', {'value': 1})
    app = FastAPI(); sdk.mount(app)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://host') as client:
        def payload(id): return {'request': {'resourceType': 'order', 'resourceId': id, 'expectedSourceVersion': 'v1'}}
        headers = {'X-ReachAI-Invocation-Token': make_token(user='alice', tenant='a', capability='index.resolve')}
        good = await client.post('/reachai/capabilities/index.resolve/invoke', json=payload('id-1'), headers=headers)
        assert good.status_code == 200 and good.json()['resourceId'] == 'id-1'
        assert (await client.post('/reachai/capabilities/index.resolve/invoke', json=payload('swap'), headers=headers)).status_code == 500
        assert (await client.post('/reachai/capabilities/index.resolve/invoke', json=payload('plain'), headers=headers)).status_code == 500
    await sdk.registry.close()
