<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Proof that the host checked the authoritative row against the verified caller.
 * Can only be created through authorizeRow().
 */
final class BusinessIndexAccessScope
{
    /** @var string */
    public $tenantId;
    /** @var string */
    public $userId;
    /** @var string */
    public $projectCode;
    /** @var string */
    public $resourceType;
    /** @var string */
    public $resourceId;

    private function __construct(string $tenantId, string $userId, string $projectCode, string $resourceType, string $resourceId)
    {
        $this->tenantId = $tenantId;
        $this->userId = $userId;
        $this->projectCode = $projectCode;
        $this->resourceType = $resourceType;
        $this->resourceId = $resourceId;
    }

    public static function authorizeRow(
        string $invocationTenantId,
        string $authoritativeRowTenantId,
        string $currentUserId,
        string $projectCode,
        string $resourceType,
        string $requestedResourceId,
        string $authoritativeResourceId,
        bool $visibleToCurrentUser
    ): self {
        $tenant = self::required($invocationTenantId, 96, true);
        $actualTenant = self::required($authoritativeRowTenantId, 96, true);
        $user = self::required($currentUserId, 128, false);
        $project = self::required($projectCode, 96, true);
        $kind = self::required($resourceType, 96, true);
        $requested = self::required($requestedResourceId, 256, false);
        $actual = self::required($authoritativeResourceId, 256, false);
        if ($tenant !== $actualTenant || $requested !== $actual || $visibleToCurrentUser !== true) {
            throw new UnauthorizedException('business row authorization failed');
        }
        return new self($tenant, $user, $project, $kind, $actual);
    }

    /** @internal */
    public static function required(string $value, int $maximum, bool $identifier): string
    {
        $value = trim($value);
        if ($value === '' || strlen($value) > $maximum
            || $identifier && preg_match('/^[A-Za-z0-9._:-]+$/', $value) !== 1) {
            throw new \InvalidArgumentException('invalid business index field');
        }
        return $value;
    }
}
