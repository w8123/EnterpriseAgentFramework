<?php

declare(strict_types=1);

namespace ReachAI;

/** Authorized business-index result; the SDK re-checks it against the verified caller before replying. */
final class BusinessIndexResolution
{
    /** @var BusinessIndexAccessScope */
    public $scope;
    /** @var string */
    public $sourceSystem;
    /** @var string */
    public $sourceVersion;
    /** @var array<string, mixed> */
    public $data;
    /** @var string */
    public $resolvedAt;

    /** @param array<string, mixed> $data */
    private function __construct(BusinessIndexAccessScope $scope, string $sourceSystem, string $sourceVersion, array $data, string $resolvedAt)
    {
        $this->scope = $scope;
        $this->sourceSystem = $sourceSystem;
        $this->sourceVersion = $sourceVersion;
        $this->data = $data;
        $this->resolvedAt = $resolvedAt;
    }

    /** @param array<string, mixed> $data */
    public static function createAuthorized(BusinessIndexAccessScope $scope, string $sourceSystem, string $sourceVersion, array $data): self
    {
        if ($data !== [] && Protocol::isList($data)) {
            throw new \InvalidArgumentException('business index data must be an object');
        }
        return new self($scope, BusinessIndexAccessScope::required($sourceSystem, 96, true),
            BusinessIndexAccessScope::required($sourceVersion, 128, false), $data,
            (new \DateTimeImmutable('now', new \DateTimeZone('UTC')))->format('Y-m-d\TH:i:s.uP'));
    }

    /**
     * @param mixed $result
     * @return array<string, mixed>
     * @internal
     */
    public static function validate($result, BusinessIndexRequest $request, InvocationContext $identity): array
    {
        if (!$result instanceof self) {
            throw new UnauthorizedException('authorized business index resolution required');
        }
        $scope = $result->scope;
        if ($scope->tenantId !== BusinessIndexAccessScope::required((string) $identity->tenantId, 96, true)
            || $scope->userId !== BusinessIndexAccessScope::required((string) $identity->userId(), 128, false)
            || $scope->projectCode !== $identity->projectCode
            || $scope->resourceType !== BusinessIndexAccessScope::required($request->resourceType, 96, true)
            || $scope->resourceId !== BusinessIndexAccessScope::required($request->resourceId, 256, false)) {
            throw new UnauthorizedException('business index resolution scope mismatch');
        }
        BusinessIndexAccessScope::required($request->expectedSourceVersion, 128, false);
        return [
            'schema' => 'reachai-business-index-resolution-v1',
            'tenantId' => $scope->tenantId,
            'projectCode' => $scope->projectCode,
            'sourceSystem' => $result->sourceSystem,
            'resourceType' => $scope->resourceType,
            'resourceId' => $scope->resourceId,
            'sourceVersion' => $result->sourceVersion,
            'data' => (object) $result->data,
            'resolvedAt' => $result->resolvedAt,
        ];
    }
}
