<?php

declare(strict_types=1);

namespace ReachAI;

/** One explicitly declared business method. Built by CapabilityCatalog. */
final class Capability
{
    /** @var string */
    public $name;
    /** @var callable */
    private $handler;
    /** @var array<string, mixed> */
    private $registration;
    /** @var array<string, array<string, mixed>> wire name => normalized schema */
    private $parameters;
    /** @var array<int, array{wire: ?string, default: mixed}> positional binding plan */
    private $plan;
    /** @var array<string, mixed>|null */
    private $result;
    /** @var string[] */
    public $requiredRoles;
    /** @var bool */
    public $businessIdentityRequired;
    /** @var bool */
    public $businessIndexResolver;

    /**
     * @param array<string, mixed> $registration
     * @param array<string, array<string, mixed>> $parameters
     * @param array<int, array{wire: ?string, default: mixed}> $plan
     * @param array<string, mixed>|null $result
     * @param string[] $requiredRoles
     */
    public function __construct(
        string $name,
        callable $handler,
        array $registration,
        array $parameters,
        array $plan,
        ?array $result,
        array $requiredRoles,
        bool $businessIdentityRequired,
        bool $businessIndexResolver
    ) {
        $this->name = $name;
        $this->handler = $handler;
        $this->registration = $registration;
        $this->parameters = $parameters;
        $this->plan = $plan;
        $this->result = $result;
        $this->requiredRoles = $requiredRoles;
        $this->businessIdentityRequired = $businessIdentityRequired;
        $this->businessIndexResolver = $businessIndexResolver;
    }

    /** @return array<string, mixed> */
    public function registration(): array
    {
        return $this->registration;
    }

    /**
     * Validate the decoded request object and build positional handler arguments.
     *
     * @param mixed $values decoded JSON (objects as stdClass)
     * @return array<int, mixed>
     */
    public function bind($values): array
    {
        if ($values instanceof \stdClass && $this->businessIndexResolver && !property_exists($values, 'request')) {
            $wrapped = new \stdClass();
            $wrapped->request = $values;
            $values = $wrapped;
        }
        if (!$values instanceof \stdClass) {
            throw new \InvalidArgumentException('capability input must be a JSON object');
        }
        $converted = Schema::coerceObject($this->parameters, $values, 'input');
        if ($this->businessIndexResolver) {
            return [BusinessIndexRequest::fromArray($converted['request'])];
        }
        $arguments = [];
        foreach ($this->plan as $step) {
            $wire = $step['wire'];
            if ($wire !== null && array_key_exists($wire, $converted)) {
                $arguments[] = $converted[$wire];
            } else {
                $arguments[] = $step['default'];
            }
        }
        return $arguments;
    }

    /** @param array<int, mixed> $arguments */
    public function call(array $arguments)
    {
        return call_user_func_array($this->handler, $arguments);
    }

    /**
     * @param mixed $value
     * @return mixed
     */
    public function prepareResult($value)
    {
        return Schema::prepareResult($this->result, $value);
    }
}
