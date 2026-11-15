<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Project and registry configuration. Secrets come from the host's protected
 * environment or secret manager, never from source code or browser input.
 */
final class Options
{
    /** @var string */
    public $registryUrl;
    /** @var string */
    public $projectCode;
    /** @var string */
    public $baseUrl;
    /** @var string */
    public $projectName = '';
    /** @var string */
    public $contextPath = '';
    /** @var string */
    public $environment = 'default';
    /** @var string */
    public $instanceId = '';
    /** @var string */
    public $enrollmentToken = '';
    /** @var string */
    public $appKey = '';
    /** @var string */
    public $appSecret = '';
    /** @var int */
    public $heartbeatSeconds = 30;
    /** @var string[] */
    public $allowedOrigins = [];
    /** @var int */
    public $timeoutSeconds = 30;

    /** @param array<string, mixed> $values camelCase keys matching the public properties */
    public static function fromArray(array $values): self
    {
        $options = new self();
        foreach ($values as $key => $value) {
            if (!property_exists($options, (string) $key)) {
                throw new \InvalidArgumentException('unknown ReachAI option: ' . $key);
            }
            $options->{$key} = $value;
        }
        $options->validate();
        return $options;
    }

    /**
     * Build options from REACHAI_* environment variables.
     *
     * @param array<string, mixed> $overrides
     */
    public static function fromEnvironment(array $overrides = []): self
    {
        $env = static function (string $name): string {
            $value = getenv($name);
            if ($value === false && isset($_SERVER[$name]) && is_string($_SERVER[$name])) {
                $value = $_SERVER[$name];
            }
            return $value === false ? '' : trim((string) $value);
        };
        $origins = array_values(array_filter(array_map('trim', explode(',', $env('REACHAI_ALLOWED_ORIGINS')))));
        $values = array_filter([
            'registryUrl' => $env('REACHAI_REGISTRY_URL'),
            'projectCode' => $env('REACHAI_PROJECT_CODE'),
            'projectName' => $env('REACHAI_PROJECT_NAME'),
            'baseUrl' => $env('REACHAI_BASE_URL'),
            'contextPath' => $env('REACHAI_CONTEXT_PATH'),
            'environment' => $env('REACHAI_ENVIRONMENT'),
            'instanceId' => $env('REACHAI_INSTANCE_ID'),
            'enrollmentToken' => $env('REACHAI_ENROLLMENT_TOKEN'),
            'appKey' => $env('REACHAI_APP_KEY'),
            'appSecret' => $env('REACHAI_APP_SECRET'),
        ], static function ($value) {
            return $value !== '';
        });
        if ($origins) {
            $values['allowedOrigins'] = $origins;
        }
        return self::fromArray(array_merge($values, $overrides));
    }

    public function validate(): void
    {
        foreach (['registryUrl', 'projectCode', 'baseUrl', 'projectName', 'contextPath', 'environment',
                     'instanceId', 'enrollmentToken', 'appKey', 'appSecret'] as $name) {
            if (!is_string($this->{$name})) {
                throw new \InvalidArgumentException($name . ' must be a string');
            }
        }
        if (preg_match('/^[a-z0-9_]+(?:-[a-z0-9_]+)*$/', $this->projectCode) !== 1) {
            throw new \InvalidArgumentException('projectCode must already be lowercase and normalized');
        }
        foreach (['registryUrl' => $this->registryUrl, 'baseUrl' => $this->baseUrl] as $name => $url) {
            $parts = parse_url($url);
            if (!is_array($parts) || !in_array(strtolower($parts['scheme'] ?? ''), ['http', 'https'], true)
                || empty($parts['host']) || isset($parts['user']) || isset($parts['pass'])
                || isset($parts['query']) || isset($parts['fragment'])) {
                throw new \InvalidArgumentException($name . ' must be an absolute HTTP(S) URL without credentials');
            }
        }
        if ($this->contextPath !== '' && ($this->contextPath[0] !== '/' || substr($this->contextPath, -1) === '/')) {
            throw new \InvalidArgumentException('contextPath must start with / and must not end with /');
        }
        if (($this->appKey === '') !== ($this->appSecret === '')) {
            throw new \InvalidArgumentException('both project credentials are required');
        }
        if ($this->enrollmentToken !== '' && $this->appKey !== '') {
            throw new \InvalidArgumentException('do not mix enrollment and project credentials');
        }
        if (!is_array($this->allowedOrigins)) {
            throw new \InvalidArgumentException('allowedOrigins must be a list of origins');
        }
        $this->allowedOrigins = array_values(array_map([self::class, 'normalizeOrigin'], $this->allowedOrigins));
        $this->heartbeatSeconds = max(5, (int) $this->heartbeatSeconds);
        $this->timeoutSeconds = max(1, (int) $this->timeoutSeconds);
    }

    /** Canonical scheme://host[:port] form; throws for anything that is not a bare origin. */
    public static function normalizeOrigin($value): string
    {
        if (!is_string($value) || $value === '' || strlen($value) > 2048) {
            throw new \InvalidArgumentException('invalid origin');
        }
        $parts = parse_url($value);
        $scheme = strtolower(is_array($parts) ? ($parts['scheme'] ?? '') : '');
        if (!is_array($parts) || !in_array($scheme, ['http', 'https'], true) || empty($parts['host'])
            || isset($parts['user']) || isset($parts['pass']) || isset($parts['query']) || isset($parts['fragment'])
            || !in_array($parts['path'] ?? '', ['', '/'], true)) {
            throw new \InvalidArgumentException('invalid origin');
        }
        $port = $parts['port'] ?? null;
        if ($port === ($scheme === 'https' ? 443 : 80)) {
            $port = null;
        }
        return $scheme . '://' . strtolower($parts['host']) . ($port ? ':' . $port : '');
    }

    /** @return array<string, mixed> */
    public function __debugInfo(): array
    {
        $values = get_object_vars($this);
        foreach (['enrollmentToken', 'appSecret'] as $secret) {
            $values[$secret] = $values[$secret] === '' ? '' : '***';
        }
        return $values;
    }
}
