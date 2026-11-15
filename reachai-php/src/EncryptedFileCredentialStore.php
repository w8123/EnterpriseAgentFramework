<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * AES-256-GCM encrypted credential file, created atomically and never overwritten.
 *
 * The key (32 random bytes, base64) must come from the host's secret manager or
 * environment, never from a file next to the credential file or from source code.
 * The directory must be private to the service account. Only one process performs
 * enrollment; PHP-FPM workers then only read the file.
 */
final class EncryptedFileCredentialStore implements CredentialStore
{
    private const CIPHER = 'aes-256-gcm';

    /** @var string */
    private $path;
    /** @var string */
    private $key;

    public function __construct(string $path, string $base64Key)
    {
        if (trim($path) === '') {
            throw new \InvalidArgumentException('credential file path required');
        }
        $key = base64_decode(trim($base64Key), true);
        if ($key === false || strlen($key) !== 32) {
            throw new \InvalidArgumentException('credential key must be 32 random bytes encoded as base64');
        }
        if (!in_array(self::CIPHER, openssl_get_cipher_methods(), true)) {
            throw new \RuntimeException('OpenSSL does not support ' . self::CIPHER);
        }
        $this->path = $path;
        $this->key = $key;
    }

    /** Helper for operators: prints a new random key. */
    public static function generateKey(): string
    {
        return base64_encode(random_bytes(32));
    }

    public function load(): ?Credentials
    {
        clearstatcache(true, $this->path);
        if (!is_file($this->path)) {
            return null;
        }
        $raw = file_get_contents($this->path);
        $envelope = is_string($raw) ? json_decode($raw, true) : null;
        if (!is_array($envelope) || ($envelope['v'] ?? null) !== 1) {
            throw new ReachAIException('credential file is not a ReachAI credential envelope');
        }
        $iv = base64_decode((string) ($envelope['iv'] ?? ''), true);
        $tag = base64_decode((string) ($envelope['tag'] ?? ''), true);
        $data = base64_decode((string) ($envelope['data'] ?? ''), true);
        if ($iv === false || $tag === false || $data === false) {
            throw new ReachAIException('credential file is corrupt');
        }
        $plain = openssl_decrypt($data, self::CIPHER, $this->key, OPENSSL_RAW_DATA, $iv, $tag, 'reachai-credentials-v1');
        $values = is_string($plain) ? json_decode($plain, true) : null;
        if (!is_array($values) || !is_string($values['appKey'] ?? null) || !is_string($values['appSecret'] ?? null)) {
            throw new ReachAIException('credential file cannot be decrypted with the configured key');
        }
        return new Credentials($values['appKey'], $values['appSecret']);
    }

    public function save(Credentials $credentials): void
    {
        $directory = dirname($this->path);
        if (!is_dir($directory) && !mkdir($directory, 0700, true) && !is_dir($directory)) {
            throw new ReachAIException('cannot create credential directory');
        }
        $iv = random_bytes(12);
        $tag = '';
        $plain = Protocol::encodeJson(['appKey' => $credentials->appKey(), 'appSecret' => $credentials->appSecret()]);
        $data = openssl_encrypt($plain, self::CIPHER, $this->key, OPENSSL_RAW_DATA, $iv, $tag, 'reachai-credentials-v1');
        if ($data === false) {
            throw new ReachAIException('credential encryption failed');
        }
        $payload = Protocol::encodeJson(['v' => 1, 'iv' => base64_encode($iv), 'tag' => base64_encode($tag),
            'data' => base64_encode($data)]);
        $temp = tempnam($directory, '.reachai-');
        if ($temp === false) {
            throw new ReachAIException('cannot create temporary credential file');
        }
        try {
            chmod($temp, 0600);
            if (file_put_contents($temp, $payload, LOCK_EX) !== strlen($payload)) {
                throw new ReachAIException('cannot write credential file');
            }
            clearstatcache(true, $this->path);
            // link() fails if the target exists, so concurrent writers cannot overwrite each other.
            if (file_exists($this->path) || !@link($temp, $this->path)) {
                if (file_exists($this->path)) {
                    throw new ReachAIException('credential store already exists');
                }
                $this->exclusiveWrite($payload);
            }
        } finally {
            @unlink($temp);
        }
    }

    public function enrollmentUncertain(): bool
    {
        clearstatcache(true, $this->marker());
        return file_exists($this->marker());
    }

    public function markEnrollmentStarted(): void
    {
        $directory = dirname($this->path);
        if (!is_dir($directory) && !mkdir($directory, 0700, true) && !is_dir($directory)) {
            throw new ReachAIException('cannot create credential directory');
        }
        $handle = @fopen($this->marker(), 'x');
        if ($handle === false) {
            throw new ReachAIException('enrollment marker already exists; recover credentials before retrying');
        }
        fwrite($handle, "Enrollment outcome may be uncertain; recover credentials before retrying.\n");
        fflush($handle);
        fclose($handle);
        @chmod($this->marker(), 0600);
    }

    public function clearEnrollmentStarted(): void
    {
        @unlink($this->marker());
    }

    private function marker(): string
    {
        return $this->path . '.enrollment-pending';
    }

    /** Fallback for filesystems without hard links: exclusive create, still never overwrites. */
    private function exclusiveWrite(string $payload): void
    {
        $handle = @fopen($this->path, 'x');
        if ($handle === false) {
            throw new ReachAIException('credential store already exists');
        }
        @chmod($this->path, 0600);
        $ok = fwrite($handle, $payload) === strlen($payload) && fflush($handle);
        fclose($handle);
        if (!$ok) {
            @unlink($this->path);
            throw new ReachAIException('cannot write credential file');
        }
    }
}
