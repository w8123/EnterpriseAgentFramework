<?php

declare(strict_types=1);

namespace ReachAI;

/** Single-process store for tests and long-running servers only; PHP-FPM needs FileNonceStore or PdoNonceStore. */
final class InMemoryNonceStore implements NonceStore
{
    /** @var array<string, int> */
    private $seen = [];

    public function claim(string $projectCode, string $nonce, int $expiresAtMs): bool
    {
        $now = Protocol::nowMillis();
        foreach ($this->seen as $key => $expiry) {
            if ($expiry < $now) {
                unset($this->seen[$key]);
            }
        }
        $key = $projectCode . "\n" . $nonce;
        if (isset($this->seen[$key])) {
            return false;
        }
        $this->seen[$key] = $expiresAtMs;
        return true;
    }
}
