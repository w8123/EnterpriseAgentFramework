<?php

declare(strict_types=1);

namespace ReachAI;

/** ReachAI asks the host to resolve one indexed business record. */
final class BusinessIndexRequest
{
    /** @var string */
    public $resourceType;
    /** @var string */
    public $resourceId;
    /** @var string */
    public $expectedSourceVersion;

    public function __construct(string $resourceType, string $resourceId, string $expectedSourceVersion)
    {
        $this->resourceType = $resourceType;
        $this->resourceId = $resourceId;
        $this->expectedSourceVersion = $expectedSourceVersion;
    }

    /** @param array<string, mixed> $values */
    public static function fromArray(array $values): self
    {
        return new self((string) $values['resourceType'], (string) $values['resourceId'], (string) $values['expectedSourceVersion']);
    }
}
