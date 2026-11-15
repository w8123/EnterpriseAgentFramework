<?php

declare(strict_types=1);

namespace ReachAI;

/** Project credentials issued on first enrollment. The secret is never printed. */
final class Credentials
{
    /** @var string */
    private $appKey;
    /** @var string */
    private $appSecret;

    public function __construct(string $appKey, string $appSecret)
    {
        if (trim($appKey) === '' || trim($appSecret) === '') {
            throw new \InvalidArgumentException('both project credentials are required');
        }
        $this->appKey = $appKey;
        $this->appSecret = $appSecret;
    }

    public function appKey(): string
    {
        return $this->appKey;
    }

    public function appSecret(): string
    {
        return $this->appSecret;
    }

    /** @return array<string, string> */
    public function __debugInfo(): array
    {
        return ['appKey' => $this->appKey, 'appSecret' => '***'];
    }
}
