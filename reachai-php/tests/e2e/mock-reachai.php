<?php

declare(strict_types=1);

// Minimal ReachAI registry stand-in for framework end-to-end checks:
//   REACHAI_MOCK_STATE=/tmp/state.json php -S 127.0.0.1:9100 tests/e2e/mock-reachai.php
// It verifies the same signatures as the real platform and records what it receives.
require __DIR__ . '/../../autoload.php';

use ReachAI\Protocol;

$stateFile = getenv('REACHAI_MOCK_STATE') ?: sys_get_temp_dir() . '/reachai-mock-state.json';
$state = is_file($stateFile) ? json_decode((string) file_get_contents($stateFile), true) : [];
$state += ['enrollmentToken' => 'enroll-ok', 'enrolled' => false, 'appKey' => 'rak_mock', 'appSecret' => 'ras_' . bin2hex(random_bytes(8)),
    'heartbeats' => 0, 'inventory' => null, 'instance' => null, 'exchanges' => [], 'revoked' => []];
$path = (string) parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$body = (string) file_get_contents('php://input');
$header = static function (string $name): string {
    return (string) ($_SERVER['HTTP_' . strtoupper(str_replace('-', '_', $name))] ?? '');
};
$reply = static function (int $status, array $value) use (&$state, $stateFile): void {
    file_put_contents($stateFile, json_encode($state, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
    http_response_code($status);
    header('Content-Type: application/json');
    echo json_encode($value, JSON_UNESCAPED_UNICODE);
};
$signed = static function () use ($header, &$state): bool {
    $timestamp = $header('X-ReachAI-Timestamp');
    $nonce = $header('X-ReachAI-Nonce');
    return $state['enrolled'] && $header('X-ReachAI-App-Key') === $state['appKey'] && $timestamp !== '' && $nonce !== ''
        && hash_equals(Protocol::registrySignature('demo-php', $timestamp, $nonce, $state['appSecret']), $header('X-ReachAI-Signature'));
};
$bodySigned = static function (string $signatureHeader) use ($header, $path, $body, &$state): bool {
    return hash('sha256', $body) === $header('X-ReachAI-Body-SHA256') && hash_equals(
        Protocol::bodySignature('POST', $path, 'demo-php', $state['appKey'], $header('X-ReachAI-Timestamp'), $header('X-ReachAI-Nonce'), $body, $state['appSecret']),
        $header($signatureHeader));
};
$json = json_decode($body, true);

if ($path === '/__state') {
    $reply(200, $state);
} elseif ($path === '/api/registry/projects/register') {
    if ($header('X-ReachAI-Registry-Enrollment-Token') !== '') {
        if ($state['enrolled'] || $header('X-ReachAI-Registry-Enrollment-Token') !== $state['enrollmentToken']) {
            $reply(401, ['code' => 401, 'message' => 'enrollment token invalid or used']);
            return;
        }
        $state['enrolled'] = true;
        $reply(200, ['code' => 200, 'data' => ['projectCode' => $json['projectCode'], 'appKey' => $state['appKey'], 'appSecret' => $state['appSecret']]]);
    } elseif ($signed()) {
        $reply(200, ['code' => 200, 'data' => ['projectCode' => $json['projectCode']]]);
    } else {
        $reply(401, ['code' => 401]);
    }
} elseif (!$signed()) {
    $reply(401, ['code' => 401, 'message' => 'bad signature']);
} elseif ($path === '/api/registry/projects/demo-php/instances/heartbeat') {
    $state['heartbeats']++;
    $state['instance'] = $json;
    $reply(200, ['code' => 200, 'data' => ['instance' => ['instanceId' => $json['instanceId'], 'status' => 'ONLINE']]]);
} elseif ($path === '/api/registry/projects/demo-php/instances/offline') {
    $reply(200, ['code' => 200, 'data' => true]);
} elseif ($path === '/api/registry/projects/demo-php/capabilities/sync') {
    $state['inventory'] = $json['capabilities'];
    $reply(200, ['code' => 200, 'data' => ['syncId' => $json['syncId'], 'projectCode' => 'demo-php', 'received' => count($json['capabilities'])]]);
} elseif ($path === '/api/embed/token/exchange' && $bodySigned('X-ReachAI-Body-Signature')) {
    $state['exchanges'][] = $json;
    $reply(200, ['code' => 200, 'data' => ['token' => 'emb_' . bin2hex(random_bytes(6)), 'expiresIn' => 900,
        'businessSessionBound' => !empty($json['businessSessionId']), 'businessSessionExpiryApplied' => isset($json['businessSessionExpiresAt'])]]);
} elseif ($path === '/api/embed/token/business-session/revoke' && $bodySigned('X-ReachAI-Body-Signature')) {
    $state['revoked'][] = $json['businessSessionId'];
    $reply(200, ['code' => 200, 'data' => true]);
} else {
    $reply(404, ['code' => 404, 'path' => $path]);
}
