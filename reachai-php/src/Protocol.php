<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Language-neutral ReachAI wire signatures and invocation-token verification.
 * See docs/contracts/sdk-protocol.md; shared vectors live in ai-runtime-contract.
 */
final class Protocol
{
    private function __construct()
    {
    }

    public static function registrySignature(string $projectCode, string $timestamp, string $nonce, string $secret): string
    {
        $message = implode("\n", [self::field($projectCode), self::field($timestamp), self::field($nonce)]);
        return hash_hmac('sha256', $message, self::field($secret));
    }

    public static function bodySignature(
        string $method,
        string $path,
        string $projectCode,
        string $appKey,
        string $timestamp,
        string $nonce,
        string $body,
        string $secret
    ): string {
        $path = self::field($path);
        if ($path[0] !== '/' || strpbrk($path, '?#') !== false || strpos($path, '..') !== false) {
            throw new \InvalidArgumentException('invalid signed path');
        }
        $fields = [
            'REACHAI_PROJECT_REQUEST_V1',
            strtoupper(self::field($method)),
            $path,
            self::field($projectCode),
            self::field($appKey),
            self::field($timestamp),
            self::field($nonce),
            hash('sha256', $body),
        ];
        return hash_hmac('sha256', implode("\n", $fields), self::field($secret));
    }

    /**
     * Verify the HS256 invocation JWT and return its claims.
     *
     * @return array<string, mixed>
     * @throws UnauthorizedException
     */
    public static function verifyInvocation(
        string $token,
        string $appKey,
        string $secret,
        string $projectCode,
        string $capabilityName,
        ?int $now = null
    ): array {
        try {
            if ($token === '' || strlen($token) > 16384) {
                throw new \UnexpectedValueException();
            }
            $parts = explode('.', $token);
            if (count($parts) !== 3) {
                throw new \UnexpectedValueException();
            }
            [$head, $payload, $signature] = $parts;
            $expected = hash_hmac('sha256', $head . '.' . $payload, self::field($secret), true);
            if (!hash_equals($expected, self::base64UrlDecode($signature))) {
                throw new \UnexpectedValueException();
            }
            $header = json_decode(self::base64UrlDecode($head), true, 32, JSON_BIGINT_AS_STRING);
            $claims = json_decode(self::base64UrlDecode($payload), true, 64, JSON_BIGINT_AS_STRING);
            if (!is_array($header) || ($header['alg'] ?? null) !== 'HS256' || !is_array($claims) || self::isList($claims)) {
                throw new \UnexpectedValueException();
            }
            $current = $now ?? time();
            $expiry = $claims['exp'] ?? null;
            if (!is_int($expiry) || $expiry <= $current) {
                throw new \UnexpectedValueException();
            }
            foreach (['iat', 'nbf'] as $name) {
                if (array_key_exists($name, $claims) && $claims[$name] !== null
                    && (!is_int($claims[$name]) || $claims[$name] > $current)) {
                    throw new \UnexpectedValueException();
                }
            }
            if (($claims['projectCode'] ?? null) !== $projectCode
                || ($claims['appKey'] ?? null) !== $appKey
                || ($claims['capabilityName'] ?? null) !== $capabilityName) {
                throw new \UnexpectedValueException();
            }
            return $claims;
        } catch (\UnexpectedValueException | \InvalidArgumentException $e) {
            throw new UnauthorizedException('invalid invocation token', 0, $e);
        }
    }

    /** @param array<mixed> $value */
    public static function isList(array $value): bool
    {
        $expected = 0;
        foreach ($value as $key => $_) {
            if ($key !== $expected++) {
                return false;
            }
        }
        return true;
    }

    public static function uuid4(): string
    {
        $bytes = random_bytes(16);
        $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
        $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
        $hex = bin2hex($bytes);
        return substr($hex, 0, 8) . '-' . substr($hex, 8, 4) . '-' . substr($hex, 12, 4) . '-'
            . substr($hex, 16, 4) . '-' . substr($hex, 20);
    }

    public static function nowMillis(): int
    {
        return (int) floor(microtime(true) * 1000);
    }

    /** Serialize once; the exact UTF-8 bytes are what get signed and sent. */
    public static function encodeJson($value): string
    {
        $json = json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION);
        if ($json === false) {
            throw new \InvalidArgumentException('value is not JSON serializable: ' . json_last_error_msg());
        }
        return $json;
    }

    private static function field(string $value): string
    {
        $value = trim($value);
        if ($value === '' || strpbrk($value, "\r\n") !== false) {
            throw new \InvalidArgumentException('invalid signature field');
        }
        return $value;
    }

    private static function base64UrlDecode(string $value): string
    {
        if ($value === '' || preg_match('/^[A-Za-z0-9_-]+$/', $value) !== 1) {
            throw new \UnexpectedValueException();
        }
        $decoded = base64_decode(strtr($value, '-_', '+/') . str_repeat('=', (4 - strlen($value) % 4) % 4), true);
        if ($decoded === false) {
            throw new \UnexpectedValueException();
        }
        return $decoded;
    }
}
