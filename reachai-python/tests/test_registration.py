import asyncio
import json

import httpx
import pytest
from cryptography.fernet import Fernet

from reachai_fastapi import EncryptedFileCredentialStore, ReachAI, ReachAIOptions
from fastapi import FastAPI


@pytest.mark.asyncio
async def test_lost_enrollment_response_never_replays_after_restart(tmp_path):
    store = EncryptedFileCredentialStore(tmp_path / 'credential.bin', Fernet.generate_key())
    calls = []
    async def lost(request):
        calls.append(request.url.path)
        raise httpx.ReadTimeout('simulated lost response')
    async with httpx.AsyncClient(transport=httpx.MockTransport(lost)) as http:
        options = ReachAIOptions('http://registry', 'demo', 'http://host', enrollment_token='one-time')
        one = ReachAI(options, credential_store=store, http_client=http)
        with pytest.raises(httpx.ReadTimeout): await one.registry.register()
        two = ReachAI(options, credential_store=store, http_client=http)
        with pytest.raises(RuntimeError, match='uncertain'): await two.registry.register()
        assert calls == ['/api/registry/projects/register']


@pytest.mark.asyncio
async def test_enrollment_persists_encrypted_and_sync_uses_complete_inventory(tmp_path):
    store = EncryptedFileCredentialStore(tmp_path / 'credential.bin', Fernet.generate_key())
    captured = []
    async def server(request):
        captured.append(request)
        if request.url.path.endswith('/register'):
            return httpx.Response(200, json={'projectCode': 'demo', 'appKey': 'key', 'appSecret': 'secret'})
        if request.url.path.endswith('/sync'):
            data = json.loads(request.content)
            return httpx.Response(200, json={'syncId': data['syncId'], 'projectCode': 'demo', 'received': len(data['capabilities'])})
        return httpx.Response(200, json={'code': 200})
    async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as http:
        reach = ReachAI(ReachAIOptions('http://registry', 'demo', 'http://host', enrollment_token='one-time'), credential_store=store, http_client=http)
        @reach.capability(name='order.query', side_effect='READ')
        async def query(order_id: int) -> int: return order_id
        await reach.registry.register()
        assert b'secret' not in store.path.read_bytes()
        assert (await store.load()).app_secret == 'secret'
        assert not await store.enrollment_uncertain()
        await reach.registry.sync()
        body = json.loads(captured[-1].content)
        assert body['apply'] is False and body['capabilities'][0]['name'] == 'order.query'
        assert 'httpApis' not in body


@pytest.mark.asyncio
async def test_sync_accepts_reused_snapshot_but_rejects_incomplete_responses():
    calls = []
    response_overrides = []
    async def server(request):
        body = json.loads(request.content)
        calls.append(body)
        if response_overrides:
            return httpx.Response(200, json=response_overrides.pop(0))
        # Existing Capability versions return the original snapshot id on a
        # new observation with identical content, rather than the request id.
        return httpx.Response(200, json={'syncId': calls[0]['syncId'], 'projectCode': 'demo',
                                         'received': len(body['capabilities'])})
    async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as http:
        reach = ReachAI(ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret'), http_client=http)
        reach.mark_empty_inventory_complete()
        first = await reach.registry.sync()
        repeated = await reach.registry.sync()
        assert calls[0]['syncId'] != calls[1]['syncId']
        assert repeated['syncId'] == first['syncId']
        for payload in [
            {'syncId': '', 'projectCode': 'demo', 'received': 0},
            {'syncId': 'x' * 65, 'projectCode': 'demo', 'received': 0},
            {'syncId': 'ok', 'projectCode': 'other', 'received': 0},
            {'syncId': 'ok', 'projectCode': 'demo', 'received': True},
            {'syncId': 'ok', 'projectCode': 'demo', 'received': 1},
        ]:
            response_overrides.append(payload)
            with pytest.raises(RuntimeError, match='incomplete'):
                await reach.registry.sync()


@pytest.mark.asyncio
@pytest.mark.parametrize('response', [
    httpx.Response(200, json={'code': 500, 'message': 'failure'}),
    httpx.Response(200, json={'projectCode': 'demo', 'appKey': 'key'}),
    httpx.Response(200, json={'projectCode': 'other', 'appKey': 'key', 'appSecret': 'secret'}),
    httpx.Response(302, headers={'Location': 'http://other.example/'}),
])
async def test_failed_or_incomplete_enrollment_never_replays(tmp_path, response):
    store = EncryptedFileCredentialStore(tmp_path / 'credentials', Fernet.generate_key())
    calls = []
    async def server(request):
        calls.append(str(request.url))
        return response
    async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as http:
        options = ReachAIOptions('http://registry', 'demo', 'http://host', enrollment_token='one-time')
        with pytest.raises(Exception): await ReachAI(options, credential_store=store, http_client=http).registry.register()
        with pytest.raises(RuntimeError, match='uncertain'):
            await ReachAI(options, credential_store=store, http_client=http).registry.register()
    assert calls == ['http://registry/api/registry/projects/register']


@pytest.mark.asyncio
async def test_credential_save_failure_does_not_replay(tmp_path):
    class FailingStore(EncryptedFileCredentialStore):
        async def save(self, credential): raise OSError('simulated disk error')
    store = FailingStore(tmp_path / 'credentials', Fernet.generate_key()); calls = []
    async def server(request):
        calls.append(str(request.url))
        return httpx.Response(200, json={'projectCode': 'demo', 'appKey': 'key', 'appSecret': 'secret'})
    async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as http:
        options = ReachAIOptions('http://registry', 'demo', 'http://host', enrollment_token='one-time')
        with pytest.raises(OSError): await ReachAI(options, credential_store=store, http_client=http).registry.register()
        with pytest.raises(RuntimeError, match='uncertain'): await ReachAI(options, credential_store=store, http_client=http).registry.register()
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_success_status_with_invalid_registry_envelopes_is_rejected():
    async def server(request):
        if request.url.path.endswith('/heartbeat'): return httpx.Response(200, json={'code': 500})
        return httpx.Response(200, json={'code': 200})
    async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as http:
        reach = ReachAI(ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret'), http_client=http)
        reach.mark_empty_inventory_complete()
        with pytest.raises(RuntimeError): await reach.registry.register()
        with pytest.raises(RuntimeError): await reach.registry.heartbeat()
        with pytest.raises(RuntimeError): await reach.registry.sync()


@pytest.mark.asyncio
async def test_lifespan_registers_heartbeats_and_marks_instance_offline():
    events, heartbeat_seen = [], asyncio.Event()
    async def server(request):
        events.append(request.url.path)
        body = json.loads(request.content)
        if request.url.path.endswith('/register'):
            return httpx.Response(200, json={'projectCode': 'demo'})
        if request.url.path.endswith('/heartbeat'):
            assert body['metadata']['sdkLanguage'] == 'python'
            heartbeat_seen.set()
            return httpx.Response(200, json={'instance': {'instanceId': body['instanceId']}})
        return httpx.Response(200, json={'code': 200})
    async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as http:
        sdk = ReachAI(ReachAIOptions('http://registry', 'demo', 'http://host', app_key='key', app_secret='secret'), http_client=http)
        sdk.mark_empty_inventory_complete()
        app = FastAPI()
        sdk.mount(app)
        async with app.router.lifespan_context(app):
            await asyncio.wait_for(heartbeat_seen.wait(), timeout=2)
    assert events[0].endswith('/register')
    assert events[1].endswith('/heartbeat')
    assert events[-1].endswith('/offline')
