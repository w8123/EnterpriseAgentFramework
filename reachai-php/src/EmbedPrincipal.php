<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * The employee behind an Embed token. Must come from the host's authenticated
 * session; never from fields the browser submitted.
 */
final class EmbedPrincipal
{
    /** @var string */
    public $externalUserId;
    /** @var string|null */
    public $globalUserId;
    /** @var string|null */
    public $userName;
    /** @var string[] */
    public $roles;
    /** @var array<string, mixed> e.g. ['tenantId' => '1'] */
    public $attributes;
    /** @var string|null high-entropy, never reused business session id */
    public $businessSessionId;
    /** @var int|null Unix seconds */
    public $businessSessionExpiresAt;

    /**
     * @param string[] $roles
     * @param array<string, mixed> $attributes
     */
    public function __construct(
        string $externalUserId,
        ?string $globalUserId = null,
        ?string $userName = null,
        array $roles = [],
        array $attributes = [],
        ?string $businessSessionId = null,
        ?int $businessSessionExpiresAt = null
    ) {
        $this->externalUserId = $externalUserId;
        $this->globalUserId = $globalUserId;
        $this->userName = $userName;
        $this->roles = array_values($roles);
        $this->attributes = $attributes;
        $this->businessSessionId = $businessSessionId;
        $this->businessSessionExpiresAt = $businessSessionExpiresAt;
    }

    /** @return array<string, mixed> */
    public function __debugInfo(): array
    {
        $values = get_object_vars($this);
        $values['businessSessionId'] = $this->businessSessionId === null ? null : '***';
        return $values;
    }
}
