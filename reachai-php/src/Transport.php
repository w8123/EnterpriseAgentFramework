<?php

declare(strict_types=1);

namespace ReachAI;

/** Minimal HTTP client used for ReachAI registry calls; replaceable in tests or by the host. */
interface Transport
{
    /**
     * Send a POST and return [statusCode, responseBody]. Must not follow redirects.
     *
     * @param array<string, string> $headers
     * @return array{0: int, 1: string}
     */
    public function post(string $url, array $headers, string $body, int $timeoutSeconds): array;
}
