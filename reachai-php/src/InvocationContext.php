<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Verified caller identity for one capability invocation. Available inside the
 * handler and security bridge through ReachAI::currentInvocation(). The host
 * must still check the business meaning of every field against its own data.
 */
final class InvocationContext
{
    /** @var string */
    public $projectCode;
    /** @var string */
    public $capabilityName;
    /** @var string|null */
    public $externalUserId;
    /** @var string|null */
    public $globalUserId;
    /** @var string|null */
    public $tenantId;
    /** @var string[] */
    public $roles;
    /** @var string|null */
    public $traceId;
    /** @var array<string, mixed> verified token claims, read-only by convention */
    public $claims;

    /**
     * @param string[] $roles
     * @param array<string, mixed> $claims
     */
    public function __construct(
        string $projectCode,
        string $capabilityName,
        ?string $externalUserId,
        ?string $globalUserId,
        ?string $tenantId,
        array $roles,
        ?string $traceId,
        array $claims
    ) {
        $this->projectCode = $projectCode;
        $this->capabilityName = $capabilityName;
        $this->externalUserId = $externalUserId;
        $this->globalUserId = $globalUserId;
        $this->tenantId = $tenantId;
        $this->roles = $roles;
        $this->traceId = $traceId;
        $this->claims = $claims;
    }

    /** @param array<string, mixed> $claims */
    public static function fromClaims(string $projectCode, string $capabilityName, array $claims): self
    {
        $text = static function (string $key) use ($claims): ?string {
            $value = $claims[$key] ?? null;
            return is_string($value) && trim($value) !== '' ? $value : null;
        };
        $roles = $claims['roles'] ?? [];
        if (!is_array($roles) || !Protocol::isList($roles)) {
            throw new UnauthorizedException('invalid roles claim');
        }
        foreach ($roles as $role) {
            if (!is_string($role)) {
                throw new UnauthorizedException('invalid roles claim');
            }
        }
        return new self($projectCode, $capabilityName, $text('externalUserId'), $text('globalUserId'),
            $text('tenantId'), $roles, $text('traceId'), $claims);
    }

    public function hasUser(): bool
    {
        return $this->externalUserId !== null || $this->globalUserId !== null;
    }

    public function userId(): ?string
    {
        return $this->externalUserId ?? $this->globalUserId;
    }
}
