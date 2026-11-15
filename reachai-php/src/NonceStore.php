<?php

declare(strict_types=1);

namespace ReachAI;

/** Callback replay protection. claim() must be atomic across every process that serves callbacks. */
interface NonceStore
{
    /** Accept a previously unseen project/nonce pair until $expiresAtMs; false if already seen. */
    public function claim(string $projectCode, string $nonce, int $expiresAtMs): bool;
}
