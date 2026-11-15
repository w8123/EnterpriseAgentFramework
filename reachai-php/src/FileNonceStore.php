<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Atomic per-host store: each nonce is an exclusively created file, so every
 * PHP-FPM worker on the same machine shares it. Multiple servers need a shared
 * store such as PdoNonceStore. Failures reject the callback (fail closed).
 */
final class FileNonceStore implements NonceStore
{
    /** @var string */
    private $directory;

    public function __construct(?string $directory = null)
    {
        $this->directory = rtrim($directory ?? (sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'reachai-nonces'), '/\\');
    }

    public function claim(string $projectCode, string $nonce, int $expiresAtMs): bool
    {
        if (!is_dir($this->directory) && !@mkdir($this->directory, 0700, true) && !is_dir($this->directory)) {
            return false;
        }
        if (random_int(1, 50) === 1) {
            $this->purge();
        }
        $file = $this->directory . DIRECTORY_SEPARATOR . hash('sha256', $projectCode . "\n" . $nonce) . '.nonce';
        $handle = @fopen($file, 'x');
        if ($handle === false) {
            clearstatcache(true, $file);
            $expiry = @file_get_contents($file);
            if (is_string($expiry) && ctype_digit(trim($expiry)) && (int) trim($expiry) < Protocol::nowMillis()
                && @unlink($file)) {
                return $this->claim($projectCode, $nonce, $expiresAtMs);
            }
            return false;
        }
        $ok = fwrite($handle, (string) $expiresAtMs) !== false;
        fclose($handle);
        return $ok;
    }

    private function purge(): void
    {
        $now = Protocol::nowMillis();
        foreach (glob($this->directory . DIRECTORY_SEPARATOR . '*.nonce') ?: [] as $file) {
            $expiry = @file_get_contents($file);
            if (is_string($expiry) && ctype_digit(trim($expiry)) && (int) trim($expiry) < $now) {
                @unlink($file);
            }
        }
    }
}
