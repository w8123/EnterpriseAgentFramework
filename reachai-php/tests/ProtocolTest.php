<?php

declare(strict_types=1);

use ReachAI\Protocol;
use ReachAI\UnauthorizedException;

$vectorFile = __DIR__ . '/../../ai-runtime-contract/src/main/resources/contracts/reachai-sdk-protocol-v1.vectors.json';

return [
    'shared protocol vectors' => static function () use ($vectorFile): void {
        $vectors = json_decode((string) file_get_contents($vectorFile), true);
        check(is_array($vectors), 'vector file missing');
        foreach ($vectors['registry'] as $item) {
            same($item['signature'], Protocol::registrySignature($item['projectCode'], $item['timestamp'], $item['nonce'], $item['appSecret']), $item['id']);
        }
        foreach ($vectors['projectRequest'] as $item) {
            same($item['bodySha256'], hash('sha256', $item['body']), $item['id'] . ' body hash');
            same($item['signature'], Protocol::bodySignature($item['method'], $item['path'], $item['projectCode'], $item['appKey'],
                $item['timestamp'], $item['nonce'], $item['body'], $item['appSecret']), $item['id']);
        }
        foreach ($vectors['invocation'] as $item) {
            $c = $item['claims'];
            $claims = Protocol::verifyInvocation($item['token'], $c['appKey'], $item['appSecret'], $c['projectCode'], $c['capabilityName'], $c['iat']);
            same('U001', $claims['externalUserId'], 'invocation user');
            same('张三', $claims['userName'], 'unicode claim');
        }
    },
    'invocation rejects tampering, expiry and wrong scope' => static function () use ($vectorFile): void {
        $item = json_decode((string) file_get_contents($vectorFile), true)['invocation'][0];
        $c = $item['claims'];
        $token = $item['token'];
        $tampered = substr($token, 0, -1) . (substr($token, -1) === 'A' ? 'B' : 'A');
        $cases = [
            [$tampered, $c['appKey'], 'ras_secret', 'orders', 'order.query', $c['iat']],
            [$token, $c['appKey'], 'ras_secret', 'orders', 'order.query', $c['exp']],
            [$token, 'other', 'ras_secret', 'orders', 'order.query', $c['iat']],
            [$token, $c['appKey'], 'ras_secret', 'other', 'order.query', $c['iat']],
            [$token, $c['appKey'], 'ras_secret', 'orders', 'other', $c['iat']],
            [$token, $c['appKey'], 'wrong_secret', 'orders', 'order.query', $c['iat']],
            ['', $c['appKey'], 'ras_secret', 'orders', 'order.query', $c['iat']],
            ['a.b', $c['appKey'], 'ras_secret', 'orders', 'order.query', $c['iat']],
        ];
        foreach ($cases as $i => $args) {
            throws(UnauthorizedException::class, static function () use ($args) {
                Protocol::verifyInvocation(...$args);
            }, 'case ' . $i);
        }
    },
    'invocation rejects bad algorithm and time claims' => static function () use ($vectorFile): void {
        $c = json_decode((string) file_get_contents($vectorFile), true)['invocation'][0]['claims'];
        $cases = [
            [['alg' => 'none'], $c],
            [['alg' => 'HS256'], array_merge($c, ['exp' => '1700000060'])],
            [['alg' => 'HS256'], array_merge($c, ['nbf' => $c['iat'] + 1])],
            [['alg' => 'HS256'], array_merge($c, ['iat' => $c['iat'] + 1])],
        ];
        foreach ($cases as $i => [$header, $claims]) {
            throws(UnauthorizedException::class, static function () use ($header, $claims, $c) {
                Protocol::verifyInvocation(jwt($claims, 'ras_secret', $header), $c['appKey'], 'ras_secret', $c['projectCode'], $c['capabilityName'], $c['iat']);
            }, 'case ' . $i);
        }
    },
    'body signer rejects ambiguous paths and fields' => static function (): void {
        foreach (['x', '/a?x=1', '/x#y', '/x/../y', "/x\nY"] as $path) {
            throws(InvalidArgumentException::class, static function () use ($path) {
                Protocol::bodySignature('POST', $path, 'p', 'key', '1', 'n', '', 's');
            }, 'path ' . json_encode($path));
        }
        throws(InvalidArgumentException::class, static function () {
            Protocol::registrySignature('p', '1', "n\nx", 's');
        }, 'newline nonce');
    },
];
