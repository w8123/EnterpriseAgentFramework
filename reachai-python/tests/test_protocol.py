import json
import base64
import hmac
import hashlib
from pathlib import Path

import pytest

from reachai_fastapi.protocol import body_signature, registry_signature, verify_invocation

VECTORS = json.loads((Path(__file__).parents[2] / 'ai-runtime-contract/src/main/resources/contracts/reachai-sdk-protocol-v1.vectors.json').read_text(encoding='utf-8'))


def test_shared_protocol_vectors():
    for item in VECTORS['registry']:
        assert registry_signature(item['projectCode'], item['timestamp'], item['nonce'], item['appSecret']) == item['signature']
    for item in VECTORS['projectRequest']:
        assert body_signature(item['method'], item['path'], item['projectCode'], item['appKey'], item['timestamp'], item['nonce'], item['body'].encode(), item['appSecret']) == item['signature']
    for item in VECTORS['invocation']:
        c = item['claims']
        assert verify_invocation(item['token'], c['appKey'], item['appSecret'], c['projectCode'], c['capabilityName'], c['iat'])['externalUserId'] == 'U001'


def test_invocation_rejects_tampering_expiry_and_wrong_scope():
    item = VECTORS['invocation'][0]; c = item['claims']; token = item['token']
    for actual in ((token[:-1] + ('A' if token[-1] != 'A' else 'B'), c['appKey'], 'ras_secret', 'orders', 'order.query', c['iat']),
                   (token, c['appKey'], 'ras_secret', 'orders', 'order.query', c['exp']),
                   (token, 'other', 'ras_secret', 'orders', 'order.query', c['iat']),
                   (token, c['appKey'], 'ras_secret', 'other', 'order.query', c['iat']),
                   (token, c['appKey'], 'ras_secret', 'orders', 'other', c['iat'])):
        with pytest.raises(PermissionError): verify_invocation(*actual)


def test_invocation_rejects_invalid_algorithm_and_time_claims():
    item = VECTORS['invocation'][0]; claims = item['claims']
    def token(header, body):
        encode = lambda data: base64.urlsafe_b64encode(json.dumps(data, separators=(',', ':')).encode()).rstrip(b'=').decode()
        first, second = encode(header), encode(body)
        digest = hmac.new(b'ras_secret', (first + '.' + second).encode(), hashlib.sha256).digest()
        return first + '.' + second + '.' + base64.urlsafe_b64encode(digest).rstrip(b'=').decode()
    for header, body in [({'alg': 'none'}, claims), ({'alg': 'HS256'}, {**claims, 'exp': '1700000060'}),
                         ({'alg': 'HS256'}, {**claims, 'nbf': claims['iat'] + 1}),
                         ({'alg': 'HS256'}, {**claims, 'iat': claims['iat'] + 1})]:
        with pytest.raises(PermissionError):
            verify_invocation(token(header, body), claims['appKey'], item['appSecret'], claims['projectCode'], claims['capabilityName'], claims['iat'])


@pytest.mark.parametrize('path', ['x', '/a?x=1', '/x#y', '/x/../y', '/x\nY'])
def test_body_signer_rejects_ambiguous_path(path):
    with pytest.raises(ValueError): body_signature('POST', path, 'p', 'key', '1', 'n', b'', 's')
