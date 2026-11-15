<?php

declare(strict_types=1);

require __DIR__ . '/../autoload.php';

use ReachAI\Transport;

/** Records requests and replies with scripted responses. */
final class FakeTransport implements Transport
{
    /** @var array<int, array{url: string, headers: array<string, string>, body: string}> */
    public $requests = [];
    /** @var array<int, callable|array{0: int, 1: mixed}> */
    public $replies = [];

    public function post(string $url, array $headers, string $body, int $timeoutSeconds): array
    {
        $this->requests[] = ['url' => $url, 'headers' => $headers, 'body' => $body];
        $reply = array_shift($this->replies);
        if ($reply === null) {
            throw new RuntimeException('unexpected request to ' . $url);
        }
        if (is_callable($reply)) {
            $reply = $reply($url, $headers, $body);
        }
        return [$reply[0], is_string($reply[1]) ? $reply[1] : json_encode($reply[1], JSON_UNESCAPED_UNICODE)];
    }

    /** @return array<string, mixed> */
    public function lastJson(): array
    {
        return json_decode($this->requests[count($this->requests) - 1]['body'], true);
    }
}

final class TestFailure extends RuntimeException
{
}

function check(bool $condition, string $message): void
{
    if (!$condition) {
        throw new TestFailure($message);
    }
}

function same($expected, $actual, string $message): void
{
    if ($expected !== $actual) {
        throw new TestFailure($message . "\n  expected: " . var_export($expected, true) . "\n  actual:   " . var_export($actual, true));
    }
}

/** @param class-string<Throwable> $class */
function throws(string $class, callable $fn, string $message): void
{
    try {
        $fn();
    } catch (Throwable $e) {
        if ($e instanceof $class) {
            return;
        }
        throw new TestFailure($message . ': expected ' . $class . ', got ' . get_class($e) . ' ' . $e->getMessage());
    }
    throw new TestFailure($message . ': expected ' . $class . ', nothing thrown');
}

function b64url(string $value): string
{
    return rtrim(strtr(base64_encode($value), '+/', '-_'), '=');
}

/** @param array<string, mixed> $claims */
function jwt(array $claims, string $secret, array $header = ['alg' => 'HS256', 'typ' => 'JWT']): string
{
    $head = b64url(json_encode($header));
    $body = b64url(json_encode($claims, JSON_UNESCAPED_UNICODE));
    return $head . '.' . $body . '.' . b64url(hash_hmac('sha256', $head . '.' . $body, $secret, true));
}

function tempDir(): string
{
    $dir = sys_get_temp_dir() . '/reachai-php-test-' . bin2hex(random_bytes(6));
    mkdir($dir, 0700, true);
    return $dir;
}
