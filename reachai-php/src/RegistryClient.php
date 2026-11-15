<?php

declare(strict_types=1);

namespace ReachAI;

/** Registry and authenticated project requests; writes are never replayed automatically. */
final class RegistryClient
{
    public const SDK_VERSION = '1.0.0';

    /** @var Options */
    private $options;
    /** @var CredentialStore|null */
    private $store;
    /** @var CapabilityCatalog */
    private $catalog;
    /** @var Transport */
    private $transport;
    /** @var NonceStore */
    private $nonceStore;
    /** @var Credentials|null */
    private $credentials;
    /** @var bool */
    private $loaded = false;
    /** @var string */
    private $instanceId;

    public function __construct(Options $options, ?CredentialStore $store, CapabilityCatalog $catalog,
                                Transport $transport, NonceStore $nonceStore)
    {
        $this->options = $options;
        $this->store = $store;
        $this->catalog = $catalog;
        $this->transport = $transport;
        $this->nonceStore = $nonceStore;
        if ($options->appKey !== '') {
            $this->credentials = new Credentials($options->appKey, $options->appSecret);
            $this->loaded = true;
        }
        // PHP has no long-lived process: keep the id stable across cron/CLI runs on one host.
        $this->instanceId = trim($options->instanceId) !== '' ? trim($options->instanceId)
            : 'php-' . substr(hash('sha256', (gethostname() ?: 'host') . "\n" . $options->baseUrl . "\n" . $options->projectCode), 0, 24);
    }

    public function instanceId(): string
    {
        return $this->instanceId;
    }

    public function credentials(): ?Credentials
    {
        if (!$this->loaded) {
            $this->credentials = $this->store !== null ? $this->store->load() : null;
            $this->loaded = $this->credentials !== null;
        }
        return $this->credentials;
    }

    /** Register the project; on first run consumes the one-time enrollment token. */
    public function register(): void
    {
        $credentials = $this->credentials();
        $enrollment = $credentials === null;
        if ($enrollment) {
            if ($this->store === null || $this->options->enrollmentToken === '') {
                throw new ReachAIException('enrollment requires REACHAI_ENROLLMENT_TOKEN and a durable credential store');
            }
            if ($this->store->enrollmentUncertain()) {
                throw new ReachAIException('enrollment outcome uncertain; recover credentials before retrying');
            }
            // Persist before sending: a lost response or crash must never replay a one-time token.
            $this->store->markEnrollmentStarted();
        }
        $response = $this->post('/api/registry/projects/register', [
            'projectCode' => $this->options->projectCode,
            'name' => $this->options->projectName !== '' ? $this->options->projectName : $this->options->projectCode,
            'environment' => $this->options->environment,
            'visibility' => 'PRIVATE',
            'baseUrl' => $this->options->baseUrl,
            'contextPath' => $this->options->contextPath,
            'appKey' => $credentials !== null ? $credentials->appKey() : null,
        ], $enrollment);
        $data = self::payload($response);
        if (!is_array($data) || ($data['projectCode'] ?? null) !== $this->options->projectCode) {
            throw new ReachAIException('registration project mismatch or incomplete response');
        }
        if ($enrollment) {
            $pending = new Credentials((string) ($data['appKey'] ?? ''), (string) ($data['appSecret'] ?? ''));
            assert($this->store !== null);
            $this->store->save($pending);
            $this->credentials = $pending;
            $this->loaded = true;
            $this->store->clearEnrollmentStarted();
            $this->options->enrollmentToken = '';
        }
    }

    /** @return array<string, mixed> */
    public function heartbeat(): array
    {
        $result = $this->post($this->projectPath('instances/heartbeat'), [
            'instanceId' => $this->instanceId,
            'baseUrl' => $this->options->baseUrl,
            'host' => gethostname() ?: 'unknown',
            'sdkVersion' => self::SDK_VERSION,
            'metadata' => [
                'runtimePlacement' => 'CAPABILITY_HOST',
                'runtimeTypes' => ['PHP_CAPABILITY_HOST'],
                'supportsTools' => true,
                'sdkLanguage' => 'php',
                'phpVersion' => PHP_VERSION,
            ],
        ]);
        $data = self::payload($result);
        $instance = is_array($data) ? ($data['instance'] ?? null) : null;
        if (!is_array($instance) || ($instance['instanceId'] ?? null) !== $this->instanceId) {
            throw new ReachAIException('heartbeat response is incomplete');
        }
        return $result;
    }

    /** @return array<string, mixed> */
    public function offline(): array
    {
        return $this->post($this->projectPath('instances/offline'), ['instanceId' => $this->instanceId]);
    }

    /** @return array<string, mixed> */
    public function sync(string $source = 'SDK'): array
    {
        $inventory = $this->catalog->registrations();
        $result = $this->post($this->projectPath('capabilities/sync'), [
            'syncId' => Protocol::uuid4(),
            'source' => $source,
            'apply' => false,
            'capabilities' => $inventory,
        ]);
        $data = self::payload($result);
        // Identical inventories reuse the earlier snapshot id; check project, id shape and count instead.
        $returned = is_array($data) ? ($data['syncId'] ?? null) : null;
        $received = is_array($data) ? ($data['received'] ?? null) : null;
        if (!is_string($returned) || trim($returned) === '' || strlen($returned) > 64
            || ($data['projectCode'] ?? null) !== $this->options->projectCode
            || !is_int($received) || $received !== count($inventory)) {
            throw new ReachAIException('capability sync response is incomplete');
        }
        return $result;
    }

    /**
     * @param mixed $body
     * @return array<string, mixed>
     */
    public function post(string $path, $body, bool $enrollment = false, bool $bodySigned = false, bool $projectOnly = false,
                         ?int $timeoutSeconds = null): array
    {
        $data = Protocol::encodeJson($body);
        $headers = ['Content-Type' => 'application/json', 'Accept' => 'application/json'];
        if ($enrollment) {
            $headers['X-ReachAI-Registry-Enrollment-Token'] = $this->options->enrollmentToken;
        } else {
            $credentials = $this->credentials();
            if ($credentials === null) {
                throw new ReachAIException('project credentials unavailable; run the register command first');
            }
            $timestamp = (string) Protocol::nowMillis();
            $nonce = Protocol::uuid4();
            $headers['X-ReachAI-App-Key'] = $credentials->appKey();
            $headers['X-ReachAI-Timestamp'] = $timestamp;
            $headers['X-ReachAI-Nonce'] = $nonce;
            $headers['X-ReachAI-Signature'] = Protocol::registrySignature(
                $this->options->projectCode, $timestamp, $nonce, $credentials->appSecret());
            if ($bodySigned) {
                $signed = Protocol::bodySignature('POST', $path, $this->options->projectCode, $credentials->appKey(),
                    $timestamp, $nonce, $data, $credentials->appSecret());
                $headers['X-ReachAI-Body-SHA256'] = hash('sha256', $data);
                $headers[$projectOnly ? 'X-ReachAI-Signature' : 'X-ReachAI-Body-Signature'] = $signed;
            }
        }
        [$status, $raw] = $this->transport->post(rtrim($this->options->registryUrl, '/') . $path, $headers, $data,
            $timeoutSeconds ?? $this->options->timeoutSeconds);
        if ($status < 200 || $status >= 300) {
            throw new HttpStatusException($status);
        }
        $value = json_decode($raw === '' ? '{}' : $raw, true, 512, JSON_BIGINT_AS_STRING);
        if (!is_array($value) || $value !== [] && Protocol::isList($value)) {
            throw new ReachAIException('invalid ReachAI response');
        }
        if (array_key_exists('code', $value) && (string) $value['code'] !== '200') {
            throw new ReachAIException('ReachAI application request failed');
        }
        return $value;
    }

    public function verifyCallback(string $key, string $timestamp, string $nonce, string $signature): bool
    {
        try {
            $credentials = $this->credentials();
            if ($credentials === null || $key !== $credentials->appKey() || $nonce === '' || strlen($nonce) > 256
                || preg_match('/^\d{1,16}$/', $timestamp) !== 1) {
                return false;
            }
            $sent = (int) $timestamp;
            if (abs(Protocol::nowMillis() - $sent) > 300000) {
                return false;
            }
            $expected = Protocol::registrySignature($this->options->projectCode, $timestamp, $nonce, $credentials->appSecret());
            if (!hash_equals($expected, strtolower($signature))) {
                return false;
            }
            return $this->nonceStore->claim($this->options->projectCode, $nonce, $sent + 300000);
        } catch (\InvalidArgumentException $e) {
            return false;
        }
    }

    /**
     * @param array<string, mixed> $response
     * @return mixed
     */
    private static function payload(array $response)
    {
        return array_key_exists('data', $response) ? $response['data'] : $response;
    }

    private function projectPath(string $suffix): string
    {
        return '/api/registry/projects/' . rawurlencode($this->options->projectCode) . '/' . $suffix;
    }
}
