<?php

declare(strict_types=1);

namespace ReachAI\Framework;

use ReachAI\CapabilityProvider;
use ReachAI\EncryptedFileCredentialStore;
use ReachAI\FileNonceStore;
use ReachAI\Options;
use ReachAI\ReachAI;

/** Builds a ReachAI instance from the shared config/reachai.php used by the Laravel and ThinkPHP adapters. */
final class Factory
{
    private const OPTION_KEYS = [
        'registry_url' => 'registryUrl', 'project_code' => 'projectCode', 'project_name' => 'projectName',
        'base_url' => 'baseUrl', 'context_path' => 'contextPath', 'environment' => 'environment',
        'instance_id' => 'instanceId', 'enrollment_token' => 'enrollmentToken', 'app_key' => 'appKey',
        'app_secret' => 'appSecret', 'heartbeat_seconds' => 'heartbeatSeconds', 'allowed_origins' => 'allowedOrigins',
    ];

    private function __construct()
    {
    }

    /**
     * @param array<string, mixed> $config
     * @param callable $make resolves a class name through the framework container
     */
    public static function fromConfig(array $config, callable $make): ReachAI
    {
        $values = [];
        foreach (self::OPTION_KEYS as $key => $option) {
            $value = $config[$key] ?? null;
            if ($value === null || $value === '') {
                continue;
            }
            if ($key === 'allowed_origins' && is_string($value)) {
                $value = array_values(array_filter(array_map('trim', explode(',', $value))));
            }
            if ($key === 'heartbeat_seconds') {
                $value = (int) $value;
            }
            $values[$option] = $value;
        }
        $dependencies = [];
        if (!empty($config['credential_file'])) {
            $dependencies['credentialStore'] = new EncryptedFileCredentialStore((string) $config['credential_file'], (string) ($config['credential_key'] ?? ''));
        }
        $dependencies['nonceStore'] = !empty($config['nonce_store']) ? self::resolve($config['nonce_store'], $make)
            : new FileNonceStore(empty($config['nonce_dir']) ? null : (string) $config['nonce_dir']);
        foreach (['principal_resolver' => 'principalResolver', 'security_bridge' => 'securityBridge'] as $key => $name) {
            if (!empty($config[$key])) {
                $dependencies[$name] = self::resolve($config[$key], $make);
            }
        }
        $reach = new ReachAI(Options::fromArray($values), $dependencies);
        $providers = $config['capabilities'] ?? [];
        foreach ((array) $providers as $provider) {
            $instance = self::resolve($provider, $make);
            if (!$instance instanceof CapabilityProvider) {
                throw new \InvalidArgumentException('capability providers must implement ReachAI\\CapabilityProvider');
            }
            $instance->register($reach);
        }
        if (!empty($config['empty_inventory_complete'])) {
            $reach->markEmptyInventoryComplete();
        }
        return $reach;
    }

    /**
     * @param mixed $value class name, callable or object
     * @return mixed
     */
    private static function resolve($value, callable $make)
    {
        return is_string($value) && class_exists($value) ? $make($value) : $value;
    }
}
