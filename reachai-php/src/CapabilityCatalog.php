<?php

declare(strict_types=1);

namespace ReachAI;

/** Explicit capability declarations; unsupported contracts fail before synchronization. */
final class CapabilityCatalog
{
    private const OPTION_KEYS = ['title', 'description', 'sideEffect', 'businessIdentityRequired', 'requiredRoles',
        'parameters', 'returns', 'businessIndexResolver'];
    private const SIDE_EFFECTS = ['READ', 'WRITE', 'IDEMPOTENT_WRITE', 'IRREVERSIBLE'];

    /** @var array<string, Capability> lowercase name => capability */
    private $items = [];
    /** @var bool */
    private $configured = false;
    /** @var string */
    private $baseUrl;
    /** @var string */
    private $contextPath;

    public function __construct(string $baseUrl, string $contextPath)
    {
        $this->baseUrl = $baseUrl;
        $this->contextPath = $contextPath;
    }

    public function markEmptyInventoryComplete(): void
    {
        $this->configured = true;
    }

    /** @param array<string, mixed> $options */
    public function register(string $name, callable $handler, array $options = []): Capability
    {
        $unknown = array_diff(array_keys($options), self::OPTION_KEYS);
        if ($unknown) {
            throw new \InvalidArgumentException('unknown capability options: ' . implode(', ', $unknown));
        }
        Schema::assertName($name, 'capability');
        if (isset($this->items[strtolower($name)])) {
            throw new \InvalidArgumentException('duplicate capability name ' . $name);
        }
        $sideEffect = strtoupper((string) ($options['sideEffect'] ?? 'WRITE'));
        if (!in_array($sideEffect, self::SIDE_EFFECTS, true)) {
            throw new \InvalidArgumentException('invalid side effect ' . $sideEffect);
        }
        $identityRequired = (bool) ($options['businessIdentityRequired'] ?? false);
        $roles = $options['requiredRoles'] ?? [];
        if (!is_array($roles) || !Protocol::isList($roles)) {
            throw new \InvalidArgumentException('requiredRoles must be a list of strings');
        }
        foreach ($roles as $role) {
            if (!is_string($role) || trim($role) === '') {
                throw new \InvalidArgumentException('requiredRoles must be non-empty strings');
            }
        }
        if ($roles && !$identityRequired) {
            throw new \InvalidArgumentException('required roles need businessIdentityRequired');
        }
        $resolver = (bool) ($options['businessIndexResolver'] ?? false);
        $reflection = self::reflect($handler);
        foreach ($reflection->getParameters() as $parameter) {
            if ($parameter->isVariadic() || $parameter->isPassedByReference()) {
                throw new \InvalidArgumentException($name . ': variadic or by-reference parameters are unsupported');
            }
        }

        if ($resolver) {
            [$parameters, $plan, $resultSchema, $responseType] = $this->resolverContract($name, $reflection, $options, $identityRequired);
        } else {
            [$parameters, $plan] = $this->parameterContract($name, $reflection, $options['parameters'] ?? null);
            [$resultSchema, $responseType] = $this->resultContract($name, $reflection, $options);
        }

        $registered = [];
        foreach ($parameters as $wire => $schema) {
            $registered[] = Schema::parameter($wire, $schema, true);
        }
        $metadata = [
            'assetType' => 'BUSINESS_METHOD',
            'declared' => true,
            'source' => 'ReachCapability',
            'requiredRoles' => array_values($roles),
            'businessIdentityRequired' => $identityRequired,
            'className' => $reflection instanceof \ReflectionMethod ? $reflection->getDeclaringClass()->getName() : 'Closure',
            'methodName' => $reflection->getName(),
            'invokeProtocol' => 'REACHAI_CAPABILITY_HTTP',
            'timeoutMs' => 0,
            'retryLimit' => -1,
        ];
        if ($resultSchema !== null) {
            $metadata['resultSchema'] = Schema::jsonSchema($resultSchema);
        }
        if ($resolver) {
            $metadata['tags'] = ['business-index-resolver'];
        }
        $registration = [
            'name' => $name,
            'title' => (string) ($options['title'] ?? ''),
            'description' => (string) ($options['description'] ?? ''),
            'httpMethod' => 'POST',
            'baseUrl' => $this->baseUrl,
            'contextPath' => $this->contextPath,
            'endpointPath' => '/reachai/capabilities/' . $name . '/invoke',
            'requestBodyType' => 'object',
            'responseType' => $responseType,
            'sideEffect' => $sideEffect,
            'enabled' => true,
            'parameters' => $registered,
            'metadata' => $metadata,
        ];
        $capability = new Capability($name, $handler, $registration, $parameters, $plan,
            $resolver ? null : $resultSchema, array_values($roles), $identityRequired, $resolver);
        $this->items[strtolower($name)] = $capability;
        $this->configured = true;
        return $capability;
    }

    public function find(string $name): ?Capability
    {
        $item = $this->items[strtolower($name)] ?? null;
        return $item !== null && $item->name === $name ? $item : null;
    }

    /** @return Capability[] */
    public function all(): array
    {
        return array_values($this->items);
    }

    /** @return array<int, array<string, mixed>> */
    public function registrations(): array
    {
        if (!$this->configured) {
            throw new ReachAIException('capability inventory has not been configured');
        }
        return array_map(static function (Capability $item): array {
            return $item->registration();
        }, array_values($this->items));
    }

    /** @return \ReflectionFunction|\ReflectionMethod */
    private static function reflect(callable $handler): \ReflectionFunctionAbstract
    {
        if (is_array($handler)) {
            return new \ReflectionMethod($handler[0], $handler[1]);
        }
        if (is_string($handler) && strpos($handler, '::') !== false) {
            return new \ReflectionMethod($handler);
        }
        if (is_object($handler) && !$handler instanceof \Closure) {
            return new \ReflectionMethod($handler, '__invoke');
        }
        return new \ReflectionFunction(\Closure::fromCallable($handler));
    }

    /**
     * @param mixed $declared
     * @return array{0: array<string, array<string, mixed>>, 1: array<int, array{wire: ?string, default: mixed}>}
     */
    private function parameterContract(string $name, \ReflectionFunctionAbstract $reflection, $declared): array
    {
        $native = $reflection->getParameters();
        $byNative = [];
        foreach ($native as $parameter) {
            $byNative[$parameter->getName()] = $parameter;
        }
        $parameters = [];
        $wireByNative = [];
        if ($declared === null) {
            foreach ($native as $parameter) {
                $schema = self::inferSchema($parameter->getType(), $name . '.' . $parameter->getName());
                $schema['required'] = !$parameter->isOptional();
                $parameters[$parameter->getName()] = $schema;
                $wireByNative[$parameter->getName()] = $parameter->getName();
            }
        } else {
            if (!is_array($declared) || $declared !== [] && Protocol::isList($declared)) {
                throw new \InvalidArgumentException($name . ': parameters must map wire names to schemas');
            }
            foreach ($declared as $wire => $raw) {
                $wire = (string) $wire;
                Schema::assertName($wire, $name . ' parameter');
                $rawArray = is_string($raw) ? ['type' => $raw] : $raw;
                $schema = Schema::normalize($rawArray, $name . '.' . $wire);
                $nativeName = is_array($rawArray) && isset($rawArray['native']) ? (string) $rawArray['native'] : $wire;
                if (!isset($byNative[$nativeName])) {
                    throw new \InvalidArgumentException($name . ': parameter ' . $wire . ' has no matching handler argument $' . $nativeName);
                }
                if (isset($wireByNative[$nativeName])) {
                    throw new \InvalidArgumentException($name . ': handler argument $' . $nativeName . ' is declared twice');
                }
                $parameter = $byNative[$nativeName];
                if (!is_array($rawArray) || !array_key_exists('required', $rawArray)) {
                    $schema['required'] = !$parameter->isOptional();
                }
                if (!$schema['required'] && !$parameter->isOptional() && !$parameter->allowsNull()) {
                    throw new \InvalidArgumentException($name . ': optional parameter ' . $wire . ' needs a default value or nullable argument');
                }
                $parameters[$wire] = $schema;
                $wireByNative[$nativeName] = $wire;
            }
        }
        $plan = [];
        foreach ($native as $parameter) {
            $wire = $wireByNative[$parameter->getName()] ?? null;
            if ($wire === null && !$parameter->isOptional()) {
                throw new \InvalidArgumentException($name . ': handler argument $' . $parameter->getName() . ' is not declared');
            }
            $plan[] = ['wire' => $wire, 'default' => $parameter->isDefaultValueAvailable() ? $parameter->getDefaultValue() : null];
        }
        return [$parameters, $plan];
    }

    /**
     * @param array<string, mixed> $options
     * @return array{0: ?array<string, mixed>, 1: string}
     */
    private function resultContract(string $name, \ReflectionFunctionAbstract $reflection, array $options): array
    {
        $returns = $options['returns'] ?? null;
        if ($returns === 'void') {
            return [null, 'void'];
        }
        if ($returns !== null) {
            $schema = Schema::normalize($returns, $name . '.returns');
            return [$schema, $schema['type']];
        }
        $type = $reflection->getReturnType();
        if ($type instanceof \ReflectionNamedType && $type->getName() === 'void') {
            return [null, 'void'];
        }
        if ($type instanceof \ReflectionNamedType && $type->isBuiltin()
            && in_array($type->getName(), ['int', 'float', 'string', 'bool'], true)) {
            $schema = self::inferSchema($type, $name . '.returns');
            return [$schema, $schema['type']];
        }
        // Undeclared structured results are passed through unchanged; declare "returns" for a typed contract.
        return [null, $type instanceof \ReflectionNamedType ? $type->getName() : 'mixed'];
    }

    /**
     * @param array<string, mixed> $options
     * @return array{0: array<string, array<string, mixed>>, 1: array<int, array{wire: ?string, default: mixed}>, 2: null, 3: string}
     */
    private function resolverContract(string $name, \ReflectionFunctionAbstract $reflection, array $options, bool $identityRequired): array
    {
        $native = $reflection->getParameters();
        $type = count($native) === 1 ? $native[0]->getType() : null;
        if (!$identityRequired || count($native) !== 1 || isset($options['parameters']) || isset($options['returns'])
            || ($type !== null && (!$type instanceof \ReflectionNamedType || $type->getName() !== BusinessIndexRequest::class))) {
            throw new \InvalidArgumentException($name . ': business index resolver requires businessIdentityRequired, one BusinessIndexRequest argument and no custom parameters/returns');
        }
        $string = ['type' => 'string', 'required' => true];
        $request = Schema::normalize(['type' => 'object', 'required' => true, 'properties' => [
            'resourceType' => $string, 'resourceId' => $string, 'expectedSourceVersion' => $string,
        ]], $name . '.request');
        return [['request' => $request], [['wire' => 'request', 'default' => null]], null, BusinessIndexResolution::class];
    }

    /** @return array<string, mixed> */
    private static function inferSchema(?\ReflectionType $type, string $path): array
    {
        if (!$type instanceof \ReflectionNamedType || !$type->isBuiltin()) {
            throw new \InvalidArgumentException($path . ': only int, float, string and bool arguments can be inferred; declare "parameters" explicitly');
        }
        $map = ['int' => 'integer', 'float' => 'number', 'string' => 'string', 'bool' => 'boolean'];
        if (!isset($map[$type->getName()])) {
            throw new \InvalidArgumentException($path . ': ' . $type->getName() . ' cannot be inferred; declare "parameters" explicitly');
        }
        return Schema::normalize(['type' => $map[$type->getName()], 'nullable' => $type->allowsNull()], $path);
    }
}
