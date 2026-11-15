<?php

declare(strict_types=1);

// Drives a running host app through the ReachAI flow against mock-reachai.php.
// php tests/e2e/drive.php --app=http://127.0.0.1:9200 --mock=http://127.0.0.1:9100 --cli="php artisan reachai" \
//     --invoke='customer.search={"keyword":"a"}' [--cookie='laravel_session=...'] [--origin=http://127.0.0.1:9200]
require __DIR__ . '/../../autoload.php';

use ReachAI\Protocol;

$args = ['invoke' => []];
foreach (array_slice($argv, 1) as $arg) {
    if (preg_match('/^--([a-z]+)=(.*)$/s', $arg, $m)) {
        if ($m[1] === 'invoke') {
            $args['invoke'][] = $m[2];
        } else {
            $args[$m[1]] = $m[2];
        }
    }
}
$failures = 0;
$step = static function (string $name, bool $ok, string $detail = '') use (&$failures): void {
    echo ($ok ? 'PASS ' : 'FAIL ') . $name . ($detail !== '' ? '  ' . $detail : '') . "\n";
    if (!$ok) {
        $failures++;
    }
};
$http = static function (string $url, array $headers, string $body, string $method = 'POST'): array {
    $lines = [];
    foreach ($headers as $k => $v) {
        $lines[] = $k . ': ' . $v;
    }
    $ch = curl_init($url);
    curl_setopt_array($ch, [CURLOPT_CUSTOMREQUEST => $method, CURLOPT_POSTFIELDS => $body, CURLOPT_HTTPHEADER => $lines,
        CURLOPT_RETURNTRANSFER => true, CURLOPT_PROXY => '', CURLOPT_NOPROXY => '*', CURLOPT_TIMEOUT => 30]);
    $out = (string) curl_exec($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    return [$status, $out];
};
$state = static function () use ($http, $args): array {
    return json_decode($http($args['mock'] . '/__state', [], '', 'GET')[1], true);
};

exec($args['cli'] . ' heartbeat 2>&1', $output, $code);
$s = $state();
$step('CLI 首次注册 + 心跳', $code === 0 && $s['enrolled'] && $s['heartbeats'] >= 1, implode(' | ', $output));
$step('心跳上报 sdkLanguage=php', ($s['instance']['metadata']['sdkLanguage'] ?? '') === 'php', (string) ($s['instance']['instanceId'] ?? ''));
exec($args['cli'] . ' heartbeat 2>&1', $output2, $code2);
$s2 = $state();
$step('再次心跳复用凭据、实例 ID 稳定', $code2 === 0 && $s2['heartbeats'] === $s['heartbeats'] + 1
    && $s2['instance']['instanceId'] === $s['instance']['instanceId']);

$timestamp = (string) Protocol::nowMillis();
$nonce = Protocol::uuid4();
$signedHeaders = ['X-ReachAI-App-Key' => $s['appKey'], 'X-ReachAI-Timestamp' => $timestamp, 'X-ReachAI-Nonce' => $nonce,
    'X-ReachAI-Signature' => Protocol::registrySignature('demo-php', $timestamp, $nonce, $s['appSecret']), 'Content-Type' => 'application/json'];
[$status, $body] = $http($args['app'] . '/reachai/registry/capabilities/sync', $signedHeaders, '');
$inventory = $state()['inventory'];
$step('平台触发签名同步回调', $status === 200 && is_array($inventory) && count($inventory) > 0,
    'HTTP ' . $status . ' ' . substr($body, 0, 120) . ' 能力: ' . implode(', ', array_column((array) $inventory, 'name')));
[$status] = $http($args['app'] . '/reachai/registry/capabilities/sync', $signedHeaders, '');
$step('重放同一个回调被拒绝', $status === 401, 'HTTP ' . $status);
[$status] = $http($args['app'] . '/reachai/registry/capabilities/sync', array_merge($signedHeaders, ['X-ReachAI-Signature' => str_repeat('0', 64)]), '');
$step('错误签名被拒绝', $status === 401, 'HTTP ' . $status);

$jwt = static function (string $capability, array $extra) use ($s): string {
    $b64 = static function (string $v): string {
        return rtrim(strtr(base64_encode($v), '+/', '-_'), '=');
    };
    $now = time();
    $claims = array_merge(['projectCode' => 'demo-php', 'appKey' => $s['appKey'], 'capabilityName' => $capability,
        'tenantId' => '1', 'externalUserId' => '1', 'roles' => ['admin'], 'traceId' => 'e2e', 'iat' => $now, 'exp' => $now + 60], $extra);
    $head = $b64('{"alg":"HS256","typ":"JWT"}');
    $payload = $b64(json_encode($claims, JSON_UNESCAPED_UNICODE));
    return $head . '.' . $payload . '.' . $b64(hash_hmac('sha256', $head . '.' . $payload, $s['appSecret'], true));
};
foreach ($args['invoke'] as $spec) {
    [$name, $json] = explode('=', $spec, 2);
    [$status, $body] = $http($args['app'] . '/reachai/capabilities/' . $name . '/invoke',
        ['X-ReachAI-Invocation-Token' => $jwt($name, []), 'Content-Type' => 'application/json'], $json);
    $step('调用 ' . $name, $status === 200, 'HTTP ' . $status . ' ' . substr($body, 0, 300));
}
if ($args['invoke']) {
    [$name, $json] = explode('=', $args['invoke'][0], 2);
    [$status] = $http($args['app'] . '/reachai/capabilities/' . $name . '/invoke', ['Content-Type' => 'application/json'], $json);
    $step('没有调用令牌被拒绝', $status === 401, 'HTTP ' . $status);
    [$status] = $http($args['app'] . '/reachai/capabilities/' . $name . '/invoke',
        ['X-ReachAI-Invocation-Token' => $jwt($name, ['exp' => time() - 1]), 'Content-Type' => 'application/json'], $json);
    $step('过期令牌被拒绝', $status === 401, 'HTTP ' . $status);
    [$status] = $http($args['app'] . '/reachai/capabilities/' . $name . '/invoke',
        ['X-ReachAI-Invocation-Token' => $jwt($name, []), 'Content-Type' => 'application/json'], '{"undeclared":1}');
    $step('未声明参数返回 400', $status === 400, 'HTTP ' . $status);
}

if (isset($args['origin'])) {
    $embed = '{"agentId":"agent-demo","pageInstanceId":"page-1","pageKey":"customers","route":"/customers"}';
    [$status] = $http($args['app'] . '/reachai/embed/token', ['Origin' => $args['origin'], 'Content-Type' => 'application/json'], $embed);
    $step('未登录换 Embed Token 被拒绝', $status === 401, 'HTTP ' . $status);
    [$status] = $http($args['app'] . '/reachai/embed/token', ['Origin' => 'https://evil.example.com', 'Cookie' => $args['cookie'] ?? '', 'Content-Type' => 'application/json'], $embed);
    $step('外站 Origin 被拒绝', $status === 403, 'HTTP ' . $status);
    [$status, $body] = $http($args['app'] . '/reachai/embed/token', ['Origin' => $args['origin'], 'Cookie' => $args['cookie'] ?? '', 'Content-Type' => 'application/json'], $embed);
    $exchanges = $state()['exchanges'];
    $last = end($exchanges) ?: [];
    $step('登录用户换 Embed Token', $status === 200 && strpos($body, 'emb_') !== false,
        'HTTP ' . $status . ' 员工=' . ($last['principal']['externalUserId'] ?? '?') . ' ' . ($last['principal']['userName'] ?? ''));
}
echo $failures === 0 ? "\n全部通过\n" : "\n{$failures} 项失败\n";
exit($failures === 0 ? 0 : 1);
