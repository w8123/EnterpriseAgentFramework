<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Explicit capability schemas. PHP arrays cannot express the contract on their
 * own, so every non-scalar parameter or result is declared, for example:
 *
 *   ['type' => 'object', 'properties' => [
 *       'orderId' => ['type' => 'integer', 'required' => true],
 *       'tags'    => ['type' => 'array', 'items' => ['type' => 'string']],
 *   ]]
 *
 * Supported keys: type, nullable, required (inside properties/parameters),
 * description, enum, format, items, properties. Unknown keys fail fast.
 */
final class Schema
{
    private const TYPES = ['string', 'integer', 'number', 'boolean', 'array', 'object'];
    private const KEYS = ['type', 'nullable', 'required', 'description', 'enum', 'format', 'items', 'properties', 'native'];

    private function __construct()
    {
    }

    /**
     * Validate and canonicalize a declared schema.
     *
     * @param mixed $schema
     * @return array<string, mixed>
     */
    public static function normalize($schema, string $path = 'schema'): array
    {
        if (is_string($schema)) {
            $schema = ['type' => $schema];
        }
        if (!is_array($schema) || Protocol::isList($schema) && $schema !== []) {
            throw new \InvalidArgumentException($path . ': schema must be an array');
        }
        $unknown = array_diff(array_keys($schema), self::KEYS);
        if ($unknown) {
            throw new \InvalidArgumentException($path . ': unsupported schema keys ' . implode(', ', $unknown));
        }
        $type = $schema['type'] ?? null;
        if (!in_array($type, self::TYPES, true)) {
            throw new \InvalidArgumentException($path . ': type must be one of ' . implode(', ', self::TYPES));
        }
        $result = [
            'type' => $type,
            'nullable' => (bool) ($schema['nullable'] ?? false),
            'required' => (bool) ($schema['required'] ?? false),
            'description' => (string) ($schema['description'] ?? ''),
        ];
        if (array_key_exists('enum', $schema)) {
            if (!is_array($schema['enum']) || $schema['enum'] === [] || !Protocol::isList($schema['enum'])) {
                throw new \InvalidArgumentException($path . ': enum must be a non-empty list');
            }
            foreach ($schema['enum'] as $item) {
                if (!self::scalarMatches($type, $item)) {
                    throw new \InvalidArgumentException($path . ': enum values must match the declared type');
                }
            }
            $result['enum'] = $schema['enum'];
        }
        if (array_key_exists('format', $schema)) {
            if (!is_string($schema['format']) || $schema['format'] === '') {
                throw new \InvalidArgumentException($path . ': format must be a string');
            }
            $result['format'] = $schema['format'];
        }
        if ($type === 'array') {
            if (!array_key_exists('items', $schema)) {
                throw new \InvalidArgumentException($path . ': array schema requires items');
            }
            $result['items'] = self::normalize($schema['items'], $path . '[]');
        } elseif (array_key_exists('items', $schema)) {
            throw new \InvalidArgumentException($path . ': items is only valid for arrays');
        }
        if ($type === 'object') {
            if (!array_key_exists('properties', $schema) || !is_array($schema['properties'])
                || $schema['properties'] !== [] && Protocol::isList($schema['properties'])) {
                throw new \InvalidArgumentException($path . ': object schema requires named properties; dynamic maps are unsupported');
            }
            $properties = [];
            foreach ($schema['properties'] as $name => $child) {
                self::assertName((string) $name, $path);
                $properties[(string) $name] = self::normalize($child, $path . '.' . $name);
            }
            $result['properties'] = $properties;
        } elseif (array_key_exists('properties', $schema)) {
            throw new \InvalidArgumentException($path . ': properties is only valid for objects');
        }
        return $result;
    }

    public static function assertName(string $name, string $path = 'name'): void
    {
        if (preg_match('/^[A-Za-z0-9_.-]{1,192}$/', $name) !== 1) {
            throw new \InvalidArgumentException($path . ': invalid name ' . $name);
        }
    }

    /**
     * Strictly validate a decoded JSON value (objects as stdClass) and convert it
     * to plain PHP arrays for the handler.
     *
     * @param array<string, mixed> $schema
     * @param mixed $value
     * @return mixed
     */
    public static function coerce(array $schema, $value, string $path = 'value')
    {
        if ($value === null) {
            if ($schema['nullable']) {
                return null;
            }
            throw new \InvalidArgumentException($path . ' must not be null');
        }
        switch ($schema['type']) {
            case 'array':
                if (!is_array($value)) {
                    throw new \InvalidArgumentException($path . ' must be an array');
                }
                $items = [];
                foreach ($value as $index => $item) {
                    $items[] = self::coerce($schema['items'], $item, $path . '[' . $index . ']');
                }
                $converted = $items;
                break;
            case 'object':
                if (!$value instanceof \stdClass) {
                    throw new \InvalidArgumentException($path . ' must be an object');
                }
                $converted = self::coerceObject($schema['properties'], $value, $path);
                break;
            default:
                if (!self::scalarMatches($schema['type'], $value)) {
                    throw new \InvalidArgumentException($path . ' must be ' . $schema['type']);
                }
                if (isset($schema['format']) && is_string($value) && !self::formatMatches($schema['format'], $value)) {
                    throw new \InvalidArgumentException($path . ' must match format ' . $schema['format']);
                }
                $converted = $value;
        }
        if (isset($schema['enum']) && !in_array($converted, $schema['enum'], true)) {
            throw new \InvalidArgumentException($path . ' is not an allowed value');
        }
        return $converted;
    }

    /**
     * @param array<string, array<string, mixed>> $properties
     * @return array<string, mixed>
     */
    public static function coerceObject(array $properties, \stdClass $value, string $path = 'value'): array
    {
        $values = get_object_vars($value);
        $unknown = array_diff(array_map('strval', array_keys($values)), array_keys($properties));
        if ($unknown) {
            throw new \InvalidArgumentException($path . ' contains undeclared fields');
        }
        $result = [];
        foreach ($properties as $name => $child) {
            if (!array_key_exists($name, $values)) {
                if ($child['required']) {
                    throw new \InvalidArgumentException($path . '.' . $name . ' is required');
                }
                continue;
            }
            $result[$name] = self::coerce($child, $values[$name], $path . '.' . $name);
        }
        return $result;
    }

    /**
     * Prepare a business result for JSON: declared empty objects stay objects.
     *
     * @param array<string, mixed>|null $schema
     * @param mixed $value
     * @return mixed
     */
    public static function prepareResult(?array $schema, $value)
    {
        if ($value instanceof \JsonSerializable) {
            $value = $value->jsonSerialize();
        }
        if ($schema === null || $value === null) {
            return $value;
        }
        if ($schema['type'] === 'object') {
            if (is_object($value) && !$value instanceof \stdClass) {
                $value = get_object_vars($value);
            }
            $fields = $value instanceof \stdClass ? get_object_vars($value) : (is_array($value) ? $value : null);
            if ($fields === null) {
                return $value;
            }
            $result = new \stdClass();
            foreach ($fields as $name => $item) {
                $result->{$name} = isset($schema['properties'][$name])
                    ? self::prepareResult($schema['properties'][$name], $item) : $item;
            }
            return $result;
        }
        if ($schema['type'] === 'array' && is_array($value)) {
            return array_map(static function ($item) use ($schema) {
                return self::prepareResult($schema['items'], $item);
            }, array_values($value));
        }
        return $value;
    }

    /**
     * Registry parameter entry, matching the shape the Java and Python SDKs report.
     *
     * @param array<string, mixed> $schema
     * @return array<string, mixed>
     */
    public static function parameter(string $name, array $schema, bool $withDescription): array
    {
        $entry = ['name' => $name, 'type' => $schema['type']];
        if ($withDescription) {
            $entry['description'] = $schema['description'];
        }
        $entry['required'] = $schema['required'];
        $entry['location'] = 'BODY';
        $entry['children'] = self::children($schema);
        $entry['metadata'] = self::metadata($schema);
        return $entry;
    }

    /**
     * JSON-schema-like description used for metadata.resultSchema.
     *
     * @param array<string, mixed> $schema
     * @return array<string, mixed>
     */
    public static function jsonSchema(array $schema): array
    {
        $result = ['type' => $schema['nullable'] ? [$schema['type'], 'null'] : $schema['type']];
        if ($schema['description'] !== '') {
            $result['description'] = $schema['description'];
        }
        foreach (['enum', 'format'] as $key) {
            if (isset($schema[$key])) {
                $result[$key] = $schema[$key];
            }
        }
        if ($schema['type'] === 'array') {
            $result['items'] = self::jsonSchema($schema['items']);
        }
        if ($schema['type'] === 'object') {
            $properties = new \stdClass();
            $required = [];
            foreach ($schema['properties'] as $name => $child) {
                $properties->{$name} = self::jsonSchema($child);
                if ($child['required']) {
                    $required[] = $name;
                }
            }
            $result['properties'] = $properties;
            $result['required'] = $required;
        }
        return $result;
    }

    /** @param array<string, mixed> $schema */
    private static function children(array $schema): array
    {
        if ($schema['type'] === 'array') {
            $schema = $schema['items'];
        }
        $children = [];
        foreach ($schema['properties'] ?? [] as $name => $child) {
            $children[] = self::parameter((string) $name, $child, false);
        }
        return $children;
    }

    /** @param array<string, mixed> $schema */
    private static function metadata(array $schema): array
    {
        $metadata = ['nullable' => $schema['nullable']];
        foreach (['format', 'enum'] as $key) {
            if (isset($schema[$key])) {
                $metadata[$key] = $schema[$key];
            }
        }
        if ($schema['type'] === 'array') {
            $metadata['itemsType'] = $schema['items']['type'];
            $metadata['itemsMetadata'] = self::metadata($schema['items']);
        }
        return $metadata;
    }

    /** @param mixed $value */
    private static function scalarMatches(string $type, $value): bool
    {
        switch ($type) {
            case 'string':
                return is_string($value);
            case 'integer':
                return is_int($value);
            case 'number':
                return is_int($value) || is_float($value) && is_finite($value);
            case 'boolean':
                return is_bool($value);
            default:
                return false;
        }
    }

    private static function formatMatches(string $format, string $value): bool
    {
        switch ($format) {
            case 'date':
                $date = \DateTime::createFromFormat('!Y-m-d', $value);
                return $date !== false && $date->format('Y-m-d') === $value;
            case 'date-time':
                return preg_match('/^\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})$/', $value) === 1;
            case 'uuid':
                return preg_match('/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/', $value) === 1;
            default:
                return true;
        }
    }
}
