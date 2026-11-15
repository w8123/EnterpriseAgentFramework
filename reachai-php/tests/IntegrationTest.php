<?php

declare(strict_types=1);

use ReachAI\BusinessIndexAccessScope;
use ReachAI\BusinessIndexRequest;
use ReachAI\BusinessIndexResolution;
use ReachAI\EmbedPrincipal;
use ReachAI\EncryptedFileCredentialStore;
use ReachAI\FileNonceStore;
use ReachAI\Http\Request;
use ReachAI\InMemoryNonceStore;
use ReachAI\InvocationContext;
use ReachAI\Options;
use ReachAI\Protocol;
use ReachAI\ReachAI;
use ReachAI\ReachAIException;

function options(array $overrides = []): Options
{
    return Options::fromArray(array_merge([
        'registryUrl' => 'https://reachai.example.com',
        'projectCode' => 'orders',
        'baseUrl' => 'https://erp.example.com',
        'appKey' => 'rak_orders',
        'appSecret' => 'ras_secret',
        'allowedOrigins' => ['https://erp.example.com'],
    ], $overrides));
}

function reach(?FakeTransport $transport = null, array $deps = [], array $overrides = []): ReachAI
{
    return new ReachAI(options($overrides), array_merge([
        'transport' => $transport ?? new FakeTransport(),
        'nonceStore' => new InMemoryNonceStore(),
        'securityBridge' => static function (InvocationContext $identity, callable $next) {
            return $next();
        },
        'logger' => static function (): void {
        },
    ], $deps));
}

function invocationToken(string $capability, array $extra = []): string
{
    $now = time();
    return jwt(array_merge(['projectCode' => 'orders', 'appKey' => 'rak_orders', 'capabilityName' => $capability,
        'tenantId' => 't1', 'externalUserId' => 'U001', 'roles' => ['sales'], 'traceId' => 'tr-1',
        'iat' => $now, 'exp' => $now + 60], $extra), 'ras_secret');
}

function invoke(ReachAI $reach, string $capability, string $body, ?string $token = null): array
{
    $response = $reach->handle(new Request('POST', '/reachai/capabilities/' . $capability . '/invoke',
        ['X-ReachAI-Invocation-Token' => $token ?? invocationToken($capability)], $body));
    check($response !== null, 'response expected');
    return [$response->status, json_decode($response->body, true), $response->body];
}

function orderCapabilities(ReachAI $reach): void
{
    $reach->capability('order.query', static function (int $orderId, bool $withItems = false): array {
        $caller = ReachAI::currentInvocation();
        return ['orderId' => $orderId, 'status' => 'PAID', 'user' => $caller ? $caller->externalUserId : null,
            'items' => $withItems ? ['A'] : [], 'extra' => []];
    }, ['title' => '查询订单', 'sideEffect' => 'READ', 'businessIdentityRequired' => true,
        'returns' => ['type' => 'object', 'properties' => [
            'orderId' => ['type' => 'integer', 'required' => true],
            'status' => ['type' => 'string', 'enum' => ['PAID', 'NEW'], 'required' => true],
            'user' => ['type' => 'string', 'nullable' => true],
            'items' => ['type' => 'array', 'items' => ['type' => 'string']],
            'extra' => ['type' => 'object', 'properties' => []],
        ]]]);
    $reach->capability('order.create', static function (array $order, ?string $remark = null): array {
        return ['created' => $order['customer'], 'lines' => count($order['lines']), 'remark' => $remark];
    }, ['title' => '创建订单', 'businessIdentityRequired' => true, 'requiredRoles' => ['sales'], 'parameters' => [
        'payload' => ['native' => 'order', 'type' => 'object', 'required' => true, 'properties' => [
            'customer' => ['type' => 'string', 'required' => true, 'description' => '客户'],
            'deliverOn' => ['type' => 'string', 'format' => 'date'],
            'lines' => ['type' => 'array', 'required' => true, 'items' => ['type' => 'object', 'properties' => [
                'sku' => ['type' => 'string', 'required' => true],
                'qty' => ['type' => 'number', 'required' => true],
            ]]],
        ]],
        'remark' => ['type' => 'string', 'nullable' => true],
    ]]);
}

return [
    'registration shape matches other SDKs' => static function (): void {
        $reach = reach();
        orderCapabilities($reach);
        [$query, $create] = $reach->catalog()->registrations();
        same('/reachai/capabilities/order.query/invoke', $query['endpointPath'], 'endpoint');
        same('READ', $query['sideEffect'], 'side effect');
        same('WRITE', $create['sideEffect'], 'default side effect');
        same([
            ['name' => 'orderId', 'type' => 'integer', 'description' => '', 'required' => true, 'location' => 'BODY', 'children' => [], 'metadata' => ['nullable' => false]],
            ['name' => 'withItems', 'type' => 'boolean', 'description' => '', 'required' => false, 'location' => 'BODY', 'children' => [], 'metadata' => ['nullable' => false]],
        ], $query['parameters'], 'inferred parameters');
        same('BUSINESS_METHOD', $query['metadata']['assetType'], 'asset type');
        same(['type' => 'object', 'properties' => ['orderId', 'status', 'user', 'items', 'extra'], 'required' => ['orderId', 'status']],
            ['type' => $query['metadata']['resultSchema']['type'], 'properties' => array_keys((array) $query['metadata']['resultSchema']['properties']),
                'required' => $query['metadata']['resultSchema']['required']], 'result schema');
        $payload = $create['parameters'][0];
        same('payload', $payload['name'], 'wire alias');
        same(['customer', 'deliverOn', 'lines'], array_column($payload['children'], 'name'), 'children');
        same(['sku', 'qty'], array_column($payload['children'][2]['children'], 'name'), 'array item children');
        same(['nullable' => false, 'itemsType' => 'object', 'itemsMetadata' => ['nullable' => false]], $payload['children'][2]['metadata'], 'array metadata');
        same(['sales'], $create['metadata']['requiredRoles'], 'roles');
        $json = Protocol::encodeJson($query);
        check(strpos($json, '"extra":{"type":"object","properties":{},"required":[]}') !== false, 'empty object schema encodes as {}');
    },
    'invalid declarations fail fast' => static function (): void {
        $reach = reach();
        $cases = [
            static function () use ($reach) { $reach->capability('bad name!', static function (): void {}); },
            static function () use ($reach) { $reach->capability('x', static function (array $a): void {}); },
            static function () use ($reach) { $reach->capability('x', static function (int $a): void {}, ['parameters' => ['b' => 'integer']]); },
            static function () use ($reach) { $reach->capability('x', static function (): void {}, ['requiredRoles' => ['a']]); },
            static function () use ($reach) { $reach->capability('x', static function (): void {}, ['sideEffect' => 'DELETE']); },
            static function () use ($reach) { $reach->capability('x', static function (): void {}, ['unknown' => 1]); },
            static function () use ($reach) { $reach->capability('x', static function (array $a): void {}, ['parameters' => ['a' => ['type' => 'object']]]); },
            static function () use ($reach) { $reach->capability('x', static function (array $a): void {}, ['parameters' => ['a' => ['type' => 'array']]]); },
            static function () use ($reach) { $reach->capability('x', static function (BusinessIndexRequest $r): BusinessIndexResolution { throw new LogicException(); }, ['businessIndexResolver' => true]); },
        ];
        foreach ($cases as $i => $case) {
            throws(InvalidArgumentException::class, $case, 'declaration case ' . $i);
        }
        $reach->capability('dup', static function (): void {});
        throws(InvalidArgumentException::class, static function () use ($reach) { $reach->capability('DUP', static function (): void {}); }, 'duplicate');
        throws(ReachAIException::class, static function () { reach()->catalog()->registrations(); }, 'unconfigured inventory');
        $empty = reach();
        $empty->markEmptyInventoryComplete();
        same([], $empty->catalog()->registrations(), 'explicit empty inventory');
    },
    'invoke verifies identity and binds strict arguments' => static function (): void {
        $reach = reach();
        orderCapabilities($reach);
        [$status, $body, $raw] = invoke($reach, 'order.query', '{"orderId":7}');
        same(200, $status, 'status');
        same(['orderId' => 7, 'status' => 'PAID', 'user' => 'U001', 'items' => [], 'extra' => []], $body, 'body');
        check(strpos($raw, '"extra":{}') !== false, 'declared empty object stays an object');
        same(null, ReachAI::currentInvocation(), 'context cleared');
        same(400, invoke($reach, 'order.query', '{"orderId":"7"}')[0], 'string for integer');
        same(400, invoke($reach, 'order.query', '{"orderId":7.5}')[0], 'float for integer');
        same(400, invoke($reach, 'order.query', '{"orderId":7,"x":1}')[0], 'unknown field');
        same(400, invoke($reach, 'order.query', '{}')[0], 'missing field');
        same(400, invoke($reach, 'order.query', '[7]')[0], 'array body');
        same(400, invoke($reach, 'order.query', 'nope')[0], 'invalid json');
        same(413, invoke($reach, 'order.query', '{"orderId":' . str_repeat(' ', ReachAI::MAX_INVOCATION_BYTES) . '1}')[0], 'too large');
        same(401, invoke($reach, 'order.query', '{"orderId":7}', 'bad.token.here')[0], 'bad token');
        same(401, invoke($reach, 'order.query', '{"orderId":7}', invocationToken('order.create'))[0], 'token for other capability');
        same(401, invoke($reach, 'order.query', '{"orderId":7}', invocationToken('order.query', ['externalUserId' => null]))[0], 'identity required');
        same(404, invoke($reach, 'order.missing', '{}')[0], 'unknown capability');
        same(404, invoke($reach, 'ORDER.QUERY', '{}', invocationToken('ORDER.QUERY'))[0], 'case-sensitive names');
        $created = invoke($reach, 'order.create', '{"payload":{"customer":"ACME","deliverOn":"2026-11-09","lines":[{"sku":"A","qty":1.5}]}}');
        same([200, ['created' => 'ACME', 'lines' => 1, 'remark' => null]], [$created[0], $created[1]], 'nested object binding');
        same(400, invoke($reach, 'order.create', '{"payload":{"customer":"ACME","deliverOn":"2026-13-40","lines":[]}}')[0], 'date format');
        same(400, invoke($reach, 'order.create', '{"payload":{"customer":"ACME","lines":[{"sku":"A"}]}}')[0], 'nested required');
        same(403, invoke($reach, 'order.create', '{"payload":{"customer":"A","lines":[]}}', invocationToken('order.create', ['roles' => ['viewer']]))[0], 'roles');
        $response = $reach->handle(new Request('GET', '/reachai/capabilities/order.query/invoke', [], ''));
        same(405, $response->status, 'method');
        same(null, $reach->handle(new Request('GET', '/other', [], '')), 'fall through');
    },
    'security bridge must execute once and failures are not 200' => static function (): void {
        $skip = reach(null, ['securityBridge' => static function (InvocationContext $identity, callable $next) {
            return ['fake' => true];
        }]);
        orderCapabilities($skip);
        $result = invoke($skip, 'order.query', '{"orderId":1}');
        same([500, 'CAPABILITY_EXECUTION_FAILED'], [$result[0], $result[1]['code']], 'skipped bridge');
        $seen = [];
        $wrap = reach(null, ['securityBridge' => static function (InvocationContext $identity, callable $next) use (&$seen) {
            $seen[] = [$identity->tenantId, $identity->roles, ReachAI::currentInvocation() === $identity];
            return $next();
        }]);
        $wrap->capability('boom', static function (): void {
            throw new RuntimeException('database password=secret');
        }, ['businessIdentityRequired' => true]);
        orderCapabilities($wrap);
        same(200, invoke($wrap, 'order.query', '{"orderId":1}')[0], 'bridged call');
        same([['t1', ['sales'], true]], $seen, 'bridge saw verified identity');
        $boom = invoke($wrap, 'boom', '{}');
        same(500, $boom[0], 'exception status');
        check(strpos($boom[2], 'secret') === false, 'exception message not leaked');
        $noBridge = new ReachAI(options(), ['transport' => new FakeTransport(), 'nonceStore' => new InMemoryNonceStore(), 'logger' => static function (): void {}]);
        orderCapabilities($noBridge);
        same(503, invoke($noBridge, 'order.query', '{"orderId":1}')[0], 'identity capability without bridge fails closed');
    },
    'sync callback verifies signature and rejects replay' => static function (): void {
        $transport = new FakeTransport();
        $reach = reach($transport);
        orderCapabilities($reach);
        $transport->replies[] = static function (string $url, array $headers, string $body) {
            $sync = json_decode($body, true);
            return [200, ['code' => 200, 'data' => ['syncId' => 'snap-1', 'projectCode' => 'orders', 'received' => count($sync['capabilities'])]]];
        };
        $timestamp = (string) Protocol::nowMillis();
        $headers = ['X-ReachAI-App-Key' => 'rak_orders', 'X-ReachAI-Timestamp' => $timestamp, 'X-ReachAI-Nonce' => 'n-1',
            'X-ReachAI-Signature' => Protocol::registrySignature('orders', $timestamp, 'n-1', 'ras_secret')];
        $response = $reach->handle(new Request('POST', '/reachai/registry/capabilities/sync', $headers, ''));
        same(200, $response->status, 'sync status');
        $body = json_decode($response->body, true);
        same(2, $body['capabilityCount'], 'count');
        same(0, $body['httpApiCount'], 'http apis');
        $sent = $transport->lastJson();
        same(['SDK_CALLBACK', false, 2], [$sent['source'], $sent['apply'], count($sent['capabilities'])], 'sync body');
        same('https://reachai.example.com/api/registry/projects/orders/capabilities/sync', $transport->requests[0]['url'], 'sync url');
        same(401, $reach->handle(new Request('POST', '/reachai/registry/capabilities/sync', $headers, ''))->status, 'replay');
        $bad = array_merge($headers, ['X-ReachAI-Nonce' => 'n-2']);
        same(401, $reach->handle(new Request('POST', '/reachai/registry/capabilities/sync', $bad, ''))->status, 'signature over other nonce');
        $old = (string) (Protocol::nowMillis() - 400000);
        $stale = ['X-ReachAI-App-Key' => 'rak_orders', 'X-ReachAI-Timestamp' => $old, 'X-ReachAI-Nonce' => 'n-3',
            'X-ReachAI-Signature' => Protocol::registrySignature('orders', $old, 'n-3', 'ras_secret')];
        same(401, $reach->handle(new Request('POST', '/reachai/registry/capabilities/sync', $stale, ''))->status, 'stale timestamp');
        $transport->replies[] = [200, ['code' => 200, 'data' => ['syncId' => 'snap-1', 'projectCode' => 'orders', 'received' => 1]]];
        $t2 = (string) Protocol::nowMillis();
        $h2 = ['X-ReachAI-App-Key' => 'rak_orders', 'X-ReachAI-Timestamp' => $t2, 'X-ReachAI-Nonce' => 'n-4',
            'X-ReachAI-Signature' => Protocol::registrySignature('orders', $t2, 'n-4', 'ras_secret')];
        same(502, $reach->handle(new Request('POST', '/reachai/registry/capabilities/sync', $h2, ''))->status, 'count mismatch');
    },
    'embed broker checks origin, principal and session binding' => static function (): void {
        $transport = new FakeTransport();
        $principal = new EmbedPrincipal('U001', null, '张三', ['sales'], ['tenantId' => '1'], 'sess-abc', time() + 3600);
        $resolved = [];
        $reach = reach($transport, ['principalResolver' => static function (Request $request, string $agentId) use (&$resolved, $principal) {
            $resolved[] = $agentId;
            return $request->header('Cookie') === 'session=ok' ? $principal : null;
        }]);
        $reach->markEmptyInventoryComplete();
        $body = '{"agentId":"agent-1","pageInstanceId":"p-1","pageKey":"orders.list","route":"/orders"}';
        $request = static function (array $headers, string $payload = null) use ($body) {
            return new Request('POST', '/reachai/embed/token', $headers, $payload ?? $body);
        };
        same(403, $reach->handle($request(['Cookie' => 'session=ok']))->status, 'missing origin');
        same(403, $reach->handle($request(['Origin' => 'https://evil.example.com', 'Cookie' => 'session=ok']))->status, 'foreign origin');
        same(400, $reach->handle($request(['Origin' => 'https://erp.example.com'], '{"agentId":"a","pageInstanceId":"p","principal":{}}'))->status, 'extra field');
        same(403, $reach->handle($request(['Origin' => 'https://erp.example.com'], '{"agentId":"a","pageInstanceId":"p","origin":"https://evil.example.com"}'))->status, 'body origin mismatch');
        same(401, $reach->handle($request(['Origin' => 'https://erp.example.com']))->status, 'no session');
        $transport->replies[] = [200, ['code' => 200, 'data' => ['token' => 'emb-1', 'expiresIn' => 900, 'businessSessionBound' => true, 'businessSessionExpiryApplied' => true]]];
        $ok = $reach->handle($request(['Origin' => 'https://erp.example.com:443', 'Cookie' => 'session=ok']));
        same(200, $ok->status, 'exchange ok');
        same('emb-1', json_decode($ok->body, true)['token'], 'token');
        $sent = $transport->requests[0];
        same('https://reachai.example.com/api/embed/token/exchange', $sent['url'], 'exchange url');
        same(hash('sha256', $sent['body']), $sent['headers']['X-ReachAI-Body-SHA256'], 'body hash header');
        same(Protocol::bodySignature('POST', '/api/embed/token/exchange', 'orders', 'rak_orders', $sent['headers']['X-ReachAI-Timestamp'],
            $sent['headers']['X-ReachAI-Nonce'], $sent['body'], 'ras_secret'), $sent['headers']['X-ReachAI-Body-Signature'], 'body signature');
        same(Protocol::registrySignature('orders', $sent['headers']['X-ReachAI-Timestamp'], $sent['headers']['X-ReachAI-Nonce'], 'ras_secret'),
            $sent['headers']['X-ReachAI-Signature'], 'registry signature');
        $json = json_decode($sent['body'], true);
        same(['orders', 'agent-1', 'https://erp.example.com', 'U001', ['tenantId' => '1'], 'sess-abc'],
            [$json['projectCode'], $json['agentId'], $json['origin'], $json['principal']['externalUserId'], $json['principal']['attributes'], $json['businessSessionId']], 'exchange body');
        check(strpos($sent['body'], '张三') !== false, 'unicode sent unescaped');
        $transport->replies[] = [200, ['code' => 200, 'data' => ['token' => 'emb-2', 'expiresIn' => 900, 'businessSessionBound' => false]]];
        same(502, $reach->handle($request(['Origin' => 'https://erp.example.com', 'Cookie' => 'session=ok']))->status, 'binding not confirmed');
        $transport->replies[] = [403, ['code' => 403]];
        same(403, $reach->handle($request(['Origin' => 'https://erp.example.com', 'Cookie' => 'session=ok']))->status, 'platform rejection');
        $transport->replies[] = [200, ['code' => 200]];
        $reach->revokeBusinessSession('sess-abc');
        same('https://reachai.example.com/api/embed/token/business-session/revoke', $transport->requests[3]['url'], 'revoke url');
        $transport->replies[] = [200, ['code' => 500]];
        throws(ReachAIException::class, static function () use ($reach) { $reach->revokeBusinessSession('sess-abc'); }, 'revoke not confirmed');
    },
    'enrollment persists marker, encrypts credentials and never replays token' => static function (): void {
        $dir = tempDir();
        $key = EncryptedFileCredentialStore::generateKey();
        $store = new EncryptedFileCredentialStore($dir . '/reachai.cred', $key);
        $transport = new FakeTransport();
        $markerSeen = null;
        $transport->replies[] = static function (string $url, array $headers, string $body) use ($dir, &$markerSeen) {
            $markerSeen = is_file($dir . '/reachai.cred.enrollment-pending');
            return [200, ['code' => 200, 'data' => ['projectCode' => 'orders', 'appKey' => 'rak_new', 'appSecret' => 'ras_new']]];
        };
        $transport->replies[] = [200, ['code' => 200, 'data' => ['instance' => ['instanceId' => 'php-fixed']]]];
        $reach = reach($transport, ['credentialStore' => $store], ['appKey' => '', 'appSecret' => '', 'enrollmentToken' => 'enroll-1', 'instanceId' => 'php-fixed']);
        $reach->markEmptyInventoryComplete();
        $reach->register();
        same(true, $markerSeen, 'marker written before request');
        same('enroll-1', $transport->requests[0]['headers']['X-ReachAI-Registry-Enrollment-Token'], 'token header');
        same(null, $transport->lastJson()['appKey'], 'no app key on enrollment');
        check(!is_file($dir . '/reachai.cred.enrollment-pending'), 'marker cleared');
        $raw = (string) file_get_contents($dir . '/reachai.cred');
        check(strpos($raw, 'ras_new') === false && strpos($raw, 'rak_new') === false, 'credential file encrypted');
        same('0600', substr(sprintf('%o', fileperms($dir . '/reachai.cred')), -4), 'file mode');
        same('rak_new', $store->load()->appKey(), 'round trip');
        $reach->heartbeat();
        same('rak_new', $transport->requests[1]['headers']['X-ReachAI-App-Key'], 'heartbeat uses new credentials');
        same('php', $transport->lastJson()['metadata']['sdkLanguage'], 'sdk language');
        throws(ReachAIException::class, static function () use ($store) { $store->save(new \ReachAI\Credentials('a', 'b')); }, 'never overwrite');
        throws(ReachAIException::class, static function () use ($dir) {
            (new EncryptedFileCredentialStore($dir . '/reachai.cred', EncryptedFileCredentialStore::generateKey()))->load();
        }, 'wrong key');

        $dir2 = tempDir();
        $store2 = new EncryptedFileCredentialStore($dir2 . '/reachai.cred', $key);
        $failing = new FakeTransport();
        $failing->replies[] = static function () {
            throw new ReachAIException('connection reset');
        };
        $again = reach($failing, ['credentialStore' => $store2], ['appKey' => '', 'appSecret' => '', 'enrollmentToken' => 'enroll-2']);
        throws(ReachAIException::class, static function () use ($again) { $again->register(); }, 'lost response');
        throws(ReachAIException::class, static function () use ($again) { $again->register(); }, 'uncertain enrollment blocks retry');
        same(1, count($failing->requests), 'token sent once');
    },
    'file nonce store is atomic across instances' => static function (): void {
        $dir = tempDir();
        $a = new FileNonceStore($dir);
        $b = new FileNonceStore($dir);
        $expiry = Protocol::nowMillis() + 300000;
        same(true, $a->claim('orders', 'n-1', $expiry), 'first claim');
        same(false, $b->claim('orders', 'n-1', $expiry), 'second process');
        same(true, $b->claim('other', 'n-1', $expiry), 'project scoped');
        same(true, $a->claim('orders', 'n-old', Protocol::nowMillis() - 1), 'expired claim');
        same(true, $b->claim('orders', 'n-old', $expiry), 'expired nonce may be reused');
    },
    'business index resolver re-checks authorized scope' => static function (): void {
        $transport = new FakeTransport();
        $reach = reach($transport);
        $reach->capability('order.resolve', static function (BusinessIndexRequest $request): BusinessIndexResolution {
            $caller = ReachAI::currentInvocation();
            $rowTenant = $request->resourceId === 'cross' ? 't2' : 't1';
            $scope = BusinessIndexAccessScope::authorizeRow((string) $caller->tenantId, $rowTenant, (string) $caller->userId(),
                'orders', $request->resourceType, $request->resourceId, $request->resourceId, true);
            return BusinessIndexResolution::createAuthorized($scope, 'erp', 'v3', ['title' => '订单 ' . $request->resourceId]);
        }, ['businessIdentityRequired' => true, 'businessIndexResolver' => true]);
        $registration = $reach->catalog()->registrations()[0];
        same(['business-index-resolver'], $registration['metadata']['tags'], 'resolver tag');
        same(['resourceType', 'resourceId', 'expectedSourceVersion'], array_column($registration['parameters'][0]['children'], 'name'), 'request schema');
        [$status, $body] = invoke($reach, 'order.resolve', '{"request":{"resourceType":"order","resourceId":"42","expectedSourceVersion":"v3"}}');
        same(200, $status, 'resolved');
        same(['reachai-business-index-resolution-v1', 't1', 'orders', '42', ['title' => '订单 42']],
            [$body['schema'], $body['tenantId'], $body['projectCode'], $body['resourceId'], $body['data']], 'resolution');
        same(200, invoke($reach, 'order.resolve', '{"resourceType":"order","resourceId":"43","expectedSourceVersion":"v3"}')[0], 'unwrapped request');
        same(500, invoke($reach, 'order.resolve', '{"request":{"resourceType":"order","resourceId":"cross","expectedSourceVersion":"v3"}}')[0], 'cross tenant');
        $transport->replies[] = [200, ['code' => 200]];
        $reach->indexUpsert('orders_idx', ['bizId' => '42', 'title' => '订单']);
        $sent = $transport->requests[0];
        same('https://reachai.example.com/api/knowledge-ingress/projects/orders/biz-index/orders_idx/upsert', $sent['url'], 'index url');
        same(Protocol::bodySignature('POST', '/api/knowledge-ingress/projects/orders/biz-index/orders_idx/upsert', 'orders', 'rak_orders',
            $sent['headers']['X-ReachAI-Timestamp'], $sent['headers']['X-ReachAI-Nonce'], $sent['body'], 'ras_secret'),
            $sent['headers']['X-ReachAI-Signature'], 'project-only body signature');
        check(!isset($sent['headers']['X-ReachAI-Body-Signature']), 'no second signature header');
        throws(InvalidArgumentException::class, static function () use ($reach) { $reach->indexDelete('../x', '1'); }, 'index path');
    },
    'options validate configuration' => static function (): void {
        $bad = [
            ['projectCode' => 'Orders'],
            ['registryUrl' => 'ftp://x'],
            ['baseUrl' => 'https://user:pw@erp.example.com'],
            ['appSecret' => ''],
            ['enrollmentToken' => 't'],
            ['allowedOrigins' => ['https://erp.example.com/path']],
            ['contextPath' => 'erp/'],
            ['unknownOption' => 1],
        ];
        foreach ($bad as $i => $override) {
            throws(InvalidArgumentException::class, static function () use ($override) { options($override); }, 'option case ' . $i);
        }
        same(['https://erp.example.com', 'http://localhost:8080'], options(['allowedOrigins' => ['HTTPS://ERP.example.com:443/', 'http://localhost:8080']])->allowedOrigins, 'origin normalization');
        check(strpos(print_r(options(), true), 'ras_secret') === false, 'secret hidden from debug output');
    },
];
