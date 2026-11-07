import asyncio
import base64
import hashlib
import hmac
import json
import time

import httpx
import pytest
from fastapi import FastAPI
from pydantic import BaseModel

from reachai_fastapi import ReachAI, ReachAIOptions, EmbedPrincipal, current_invocation
from reachai_fastapi.protocol import registry_signature


def sign(claims):
    def b64(v): return base64.urlsafe_b64encode(v).rstrip(b'=').decode()
    head = b64(b'{"alg":"HS256","typ":"JWT"}')
    payload = b64(json.dumps(claims, separators=(',', ':')).encode())
    sig = b64(hmac.new(b'secret', (head + '.' + payload).encode(), hashlib.sha256).digest())
    return head + '.' + payload + '.' + sig


class Result(BaseModel):
    user: str
    tenant: str
    value: int


@pytest.fixture
def system():
    async def principal(request, agent_id):
        return EmbedPrincipal('alice', roles=['sales'], attributes={'tenantId': 'a'}, business_session_id='random-session', business_session_expires_at=1999999999) if request.headers.get('X-Test-Auth') == 'yes' else None
    async def bridge(context, call_next):
        if context.tenant_id not in ('a', 'b'): raise PermissionError()
        return await call_next()
    async def upstream(request):
        if request.url.path.endswith('/exchange'):
            return httpx.Response(200, json={'code': 200, 'data': {'token': 'fake', 'expiresIn': 60, 'businessSessionBound': True, 'businessSessionExpiryApplied': True}})
        if request.url.path.endswith('/capabilities/sync'):
            data = json.loads(request.content)
            return httpx.Response(200, json={'syncId': data['syncId'], 'projectCode': 'demo', 'received': len(data['capabilities'])})
        return httpx.Response(200, json={'code': 200})
    outbound = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    options = ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret', allowed_origins=('http://host',))
    reach = ReachAI(options, principal_resolver=principal, security_bridge=bridge, http_client=outbound)
    @reach.capability(name='order.query', side_effect='READ', business_identity_required=True, required_roles=('sales',), parameter_names={'order_id': 'orderId'})
    async def query(order_id: int) -> Result:
        before = current_invocation.get()
        await asyncio.sleep(0.005)
        assert current_invocation.get() is before
        return Result(user=before.external_user_id, tenant=before.tenant_id, value=order_id)
    app = FastAPI(); reach.mount(app)
    return app, reach, outbound


def make_token(user='alice', tenant='a', roles=None, capability='order.query', extra=None):
    return sign({'projectCode': 'demo', 'appKey': 'key', 'capabilityName': capability,
                 'exp': int(time.time()) + 120, 'externalUserId': user, 'tenantId': tenant,
                 'roles': ['sales'] if roles is None else roles, **(extra or {})})


@pytest.mark.asyncio
async def test_concurrent_users_are_isolated_and_roles_enforced(system):
    app, reach, outbound = system
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://host') as client:
        async def query(i):
            user, tenant = ('alice', 'a') if i % 2 else ('bob', 'b')
            response = await client.post('/reachai/capabilities/order.query/invoke', headers={'X-ReachAI-Invocation-Token': make_token(user, tenant)}, json={'orderId': i})
            assert response.status_code == 200
            assert response.json() == {'user': user, 'tenant': tenant, 'value': i}
        await asyncio.gather(*(query(i) for i in range(64)))
        assert (await client.post('/reachai/capabilities/order.query/invoke', headers={'X-ReachAI-Invocation-Token': make_token(roles=['other'])}, json={'orderId': 1})).status_code == 403
        assert (await client.post('/reachai/capabilities/order.query/invoke', headers={'X-ReachAI-Invocation-Token': make_token(user='')}, json={'orderId': 1})).status_code == 401
        assert (await client.post('/reachai/capabilities/order.query/invoke', headers={'X-ReachAI-Invocation-Token': make_token()}, json={'orderId': 1, 'roles': ['admin']})).status_code == 400
        assert (await client.post('/reachai/capabilities/order.query/invoke', headers={'X-ReachAI-Invocation-Token': 'forged'}, json={'orderId': 1})).status_code == 401
        assert current_invocation.get() is None
    await outbound.aclose()


@pytest.mark.asyncio
async def test_broker_requires_origin_and_host_login_and_binding(system):
    app, reach, outbound = system
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://host') as client:
        body = {'agentId': 'agent', 'pageInstanceId': 'page', 'origin': 'http://host'}
        assert (await client.post('/reachai/embed/token', json=body)).status_code == 403
        assert (await client.post('/reachai/embed/token', headers={'Origin': 'http://evil', 'X-Test-Auth': 'yes'}, json=body)).status_code == 403
        assert (await client.post('/reachai/embed/token', headers={'Origin': 'http://host'}, json=body)).status_code == 401
        good = await client.post('/reachai/embed/token', headers={'Origin': 'http://host', 'X-Test-Auth': 'yes'}, json=body)
        assert good.status_code == 200 and good.json()['businessSessionBound']
        assert (await client.post('/reachai/embed/token', headers={'Origin': 'http://host', 'X-Test-Auth': 'yes'}, json={**body, 'externalUserId': 'admin'})).status_code == 422
    await outbound.aclose()


@pytest.mark.asyncio
async def test_sync_callback_rejects_replay_and_untrusted_signature(system):
    app, reach, outbound = system
    ts, nonce = str(int(time.time() * 1000)), 'fresh'
    headers = {'X-ReachAI-App-Key': 'key', 'X-ReachAI-Timestamp': ts, 'X-ReachAI-Nonce': nonce,
               'X-ReachAI-Signature': registry_signature('demo', ts, nonce, 'secret')}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://host') as client:
        first = await client.post('/reachai/registry/capabilities/sync', headers=headers)
        assert first.status_code == 200 and first.json()['capabilityCount'] == 1
        assert (await client.post('/reachai/registry/capabilities/sync', headers=headers)).status_code == 401
        assert (await client.post('/reachai/registry/capabilities/sync', headers={**headers, 'X-ReachAI-Nonce': 'bad'})).status_code == 401
    await outbound.aclose()


def test_duplicate_name_and_unknown_inventory_fail_closed():
    reach = ReachAI(ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret'))
    with pytest.raises(RuntimeError): reach.mount(FastAPI())
    @reach.capability(name='order.query')
    async def one(order_id: int) -> int: return order_id
    with pytest.raises(ValueError):
        @reach.capability(name='ORDER.QUERY')
        async def two(order_id: int) -> int: return order_id


@pytest.mark.asyncio
async def test_threadpool_capabilities_keep_concurrent_identity():
    import time as clock
    options = ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret')
    async def bridge(context, call_next):
        return await call_next()
    reach = ReachAI(options, security_bridge=bridge)
    @reach.capability(name='order.sync', business_identity_required=True)
    def query(value: int) -> Result:
        identity = current_invocation.get()
        assert identity.claims['attributes']['departments'] == ('sales',)
        with pytest.raises(TypeError): identity.claims['attributes']['departments'] = ('other',)
        clock.sleep(0.005)
        assert current_invocation.get() is identity
        return Result(user=identity.external_user_id, tenant=identity.tenant_id, value=value)
    app = FastAPI()
    reach.mount(app)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://host') as client:
        async def call(i):
            user, tenant = ('alice', 'a') if i % 2 else ('bob', 'b')
            token = make_token(user, tenant, capability='order.sync', extra={'attributes': {'departments': ['sales']}})
            response = await client.post('/reachai/capabilities/order.sync/invoke', headers={'X-ReachAI-Invocation-Token': token}, json={'value': i})
            assert response.json() == {'user': user, 'tenant': tenant, 'value': i}
        await asyncio.gather(*(call(i) for i in range(32)))
    assert current_invocation.get() is None
    await reach.registry.close()


def test_secret_values_are_not_present_in_object_representations():
    from reachai_fastapi.credentials import Credentials
    options = ReachAIOptions('http://registry', 'demo', 'http://host', enrollment_token='enrollment-secret')
    options_with_key = ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='private-secret')
    assert 'enrollment-secret' not in repr(options)
    assert 'private-secret' not in repr(options_with_key)
    assert 'private-secret' not in repr(Credentials('key', 'private-secret'))
    assert 'business-session-secret' not in repr(EmbedPrincipal('alice', business_session_id='business-session-secret'))


@pytest.mark.asyncio
async def test_bridge_that_skips_execution_fails_and_oversized_body_is_rejected():
    options = ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret')
    async def skipping_bridge(context, call_next):
        return {'value': 0}
    reach = ReachAI(options, security_bridge=skipping_bridge)
    called = []
    @reach.capability(name='order.skip', business_identity_required=True)
    async def skip(value: int) -> Result:
        called.append(value)
        return Result(user='x', tenant='y', value=value)
    app = FastAPI()
    reach.mount(app)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://host') as client:
        token = make_token('alice', 'a', capability='order.skip')
        response = await client.post('/reachai/capabilities/order.skip/invoke', headers={'X-ReachAI-Invocation-Token': token}, json={'value': 1})
        assert response.status_code == 500 and response.json()['code'] == 'CAPABILITY_EXECUTION_FAILED'
        oversized = b'{"value":1' + b' ' * (1024 * 1024) + b'}'
        response = await client.post('/reachai/capabilities/order.skip/invoke', headers={'X-ReachAI-Invocation-Token': token, 'Content-Type': 'application/json'}, content=oversized)
        assert response.status_code == 413
    assert called == []
    await reach.registry.close()
