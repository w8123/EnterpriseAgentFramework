<?php

declare(strict_types=1);

namespace ReachAI;

use ReachAI\Http\Request;
use ReachAI\Http\Response;

/**
 * ReachAI business-system integration for PHP.
 *
 * Request handling is stateless (PHP-FPM friendly): handle() serves the
 * /reachai/* endpoints, while registration and heartbeat run from the CLI
 * (cron or a supervised loop) through register()/heartbeat() or bin/reachai.
 */
final class ReachAI
{
    public const MAX_INVOCATION_BYTES = 1048576;
    private const DEPENDENCY_KEYS = ['credentialStore', 'principalResolver', 'securityBridge', 'transport', 'nonceStore', 'logger'];

    /** @var InvocationContext[] */
    private static $invocations = [];

    /** @var Options */
    private $options;
    /** @var CapabilityCatalog */
    private $catalog;
    /** @var RegistryClient */
    private $registry;
    /** @var callable|null */
    private $principalResolver;
    /** @var callable|null */
    private $securityBridge;
    /** @var callable */
    private $logger;
    /** @var ActivityRecorder|null */
    private $activity;

    /**
     * @param array<string, mixed> $dependencies credentialStore, principalResolver, securityBridge,
     *        transport, nonceStore, logger
     */
    public function __construct(Options $options, array $dependencies = [])
    {
        $unknown = array_diff(array_keys($dependencies), self::DEPENDENCY_KEYS);
        if ($unknown) {
            throw new \InvalidArgumentException('unknown ReachAI dependencies: ' . implode(', ', $unknown));
        }
        $options->validate();
        $this->options = $options;
        $this->catalog = new CapabilityCatalog($options->baseUrl, $options->contextPath);
        $store = $dependencies['credentialStore'] ?? null;
        if ($store !== null && !$store instanceof CredentialStore) {
            throw new \InvalidArgumentException('credentialStore must implement ReachAI\\CredentialStore');
        }
        $transport = $dependencies['transport'] ?? new CurlTransport();
        $nonceStore = $dependencies['nonceStore'] ?? new FileNonceStore();
        if (!$transport instanceof Transport || !$nonceStore instanceof NonceStore) {
            throw new \InvalidArgumentException('transport/nonceStore have the wrong type');
        }
        $this->registry = new RegistryClient($options, $store, $this->catalog, $transport, $nonceStore);
        foreach (['principalResolver', 'securityBridge', 'logger'] as $name) {
            if (isset($dependencies[$name]) && !is_callable($dependencies[$name])) {
                throw new \InvalidArgumentException($name . ' must be callable');
            }
        }
        $this->principalResolver = $dependencies['principalResolver'] ?? null;
        $this->securityBridge = $dependencies['securityBridge'] ?? null;
        $this->logger = $dependencies['logger'] ?? static function (string $message): void {
            error_log('[reachai] ' . $message);
        };
    }

    /**
     * Declare a business method. Options: title, description, sideEffect (READ|WRITE|IDEMPOTENT_WRITE|IRREVERSIBLE,
     * default WRITE), businessIdentityRequired, requiredRoles, parameters, returns, businessIndexResolver.
     *
     * @param array<string, mixed> $options
     */
    public function capability(string $name, callable $handler, array $options = []): Capability
    {
        return $this->catalog->register($name, $handler, $options);
    }

    /** Confirm that an empty capability inventory is intentional. */
    public function markEmptyInventoryComplete(): void
    {
        $this->catalog->markEmptyInventoryComplete();
    }

    /** Verified caller of the capability currently executing, or null outside an invocation. */
    public static function currentInvocation(): ?InvocationContext
    {
        return self::$invocations ? self::$invocations[count(self::$invocations) - 1] : null;
    }

    public function options(): Options
    {
        return $this->options;
    }

    public function catalog(): CapabilityCatalog
    {
        return $this->catalog;
    }

    public function registry(): RegistryClient
    {
        return $this->registry;
    }

    public function register(): void
    {
        $this->registry->register();
    }

    /** @return array<string, mixed> */
    public function heartbeat(): array
    {
        return $this->registry->heartbeat();
    }

    /** @return array<string, mixed> */
    public function offline(): array
    {
        return $this->registry->offline();
    }

    /** @return array<string, mixed> */
    public function sync(string $source = 'SDK'): array
    {
        $this->assertReady();
        return $this->registry->sync($source);
    }

    /**
     * Exchange an Embed token for an authenticated employee.
     *
     * @param array<string, mixed> $input validated page context (agentId, pageInstanceId, pageKey, route)
     * @return array<string, mixed>
     */
    public function exchangeEmbedToken(array $input, EmbedPrincipal $principal, string $origin): array
    {
        $result = $this->registry->post('/api/embed/token/exchange', [
            'projectCode' => $this->options->projectCode,
            'agentId' => $input['agentId'],
            'pageKey' => $input['pageKey'] ?? null,
            'pageInstanceId' => $input['pageInstanceId'],
            'route' => $input['route'] ?? null,
            'origin' => $origin,
            'principal' => [
                'externalUserId' => $principal->externalUserId,
                'globalUserId' => $principal->globalUserId,
                'userName' => $principal->userName,
                'roles' => array_values($principal->roles),
                'attributes' => (object) $principal->attributes,
            ],
            'businessSessionId' => $principal->businessSessionId,
            'businessSessionExpiresAt' => $principal->businessSessionExpiresAt,
        ], false, true);
        $data = array_key_exists('data', $result) ? $result['data'] : $result;
        if (!is_array($data) || !is_string($data['token'] ?? null) || $data['token'] === '') {
            throw new ReachAIException('invalid Embed response');
        }
        $expiresIn = $data['expiresIn'] ?? null;
        if (!(is_int($expiresIn) || is_float($expiresIn)) || $expiresIn <= 0) {
            throw new ReachAIException('invalid Embed expiry');
        }
        if ($principal->businessSessionId !== null && $principal->businessSessionId !== ''
            && ($data['businessSessionBound'] ?? null) !== true) {
            throw new ReachAIException('session binding not confirmed');
        }
        if ($principal->businessSessionExpiresAt !== null && ($data['businessSessionExpiryApplied'] ?? null) !== true) {
            throw new ReachAIException('session expiry not confirmed');
        }
        return $data;
    }

    /** Call on business logout; only a confirmed response means the ReachAI session is revoked. */
    public function revokeBusinessSession(string $businessSessionId): void
    {
        if (trim($businessSessionId) === '') {
            throw new \InvalidArgumentException('business session required');
        }
        $result = $this->registry->post('/api/embed/token/business-session/revoke', [
            'projectCode' => $this->options->projectCode,
            'businessSessionId' => $businessSessionId,
        ], false, true);
        if ((string) ($result['code'] ?? '') !== '200') {
            throw new ReachAIException('business session revocation not confirmed');
        }
    }

    /**
     * @param array<string, mixed> $record
     * @return array<string, mixed>
     */
    public function indexUpsert(string $indexCode, array $record): array
    {
        return $this->indexSend($indexCode, 'upsert', $record);
    }

    /**
     * @param array<int, array<string, mixed>> $records
     * @return array<string, mixed>
     */
    public function indexBatch(string $indexCode, array $records): array
    {
        return $this->indexSend($indexCode, 'batch', ['items' => array_values($records)]);
    }

    /** @return array<string, mixed> */
    public function indexDelete(string $indexCode, string $bizId): array
    {
        return $this->indexSend($indexCode, 'delete', ['bizId' => $bizId]);
    }

    /**
     * 主动感知 recorder for this process (see ActivityRecorder). The framework middlewares call it for you.
     *
     * @param array<string, mixed> $settings searchParameters, successCodes, browserRequestsOnly, timeoutSeconds
     */
    public function activity(array $settings = []): ActivityRecorder
    {
        if ($this->activity === null) {
            $this->activity = new ActivityRecorder($this->registry, $this->options->projectCode, $settings,
                function (string $message, ?\Throwable $error = null): void {
                    $this->log($message, $error);
                });
        }
        return $this->activity;
    }

    /**
     * Serve a /reachai/* request. Returns null when the path is not a ReachAI endpoint,
     * so the host can fall through to its own routing.
     */
    public function handle(Request $request): ?Response
    {
        $path = $request->path;
        if ($path === '/reachai/registry/capabilities/sync') {
            return $request->method === 'POST' ? $this->handleSync($request) : Response::error(405, 'METHOD_NOT_ALLOWED');
        }
        if ($path === '/reachai/embed/token') {
            return $request->method === 'POST' ? $this->handleEmbed($request) : Response::error(405, 'METHOD_NOT_ALLOWED');
        }
        if (preg_match('#^/reachai/capabilities/([^/]+)/invoke$#', $path, $match) === 1) {
            return $request->method === 'POST' ? $this->handleInvoke(rawurldecode($match[1]), $request)
                : Response::error(405, 'METHOD_NOT_ALLOWED');
        }
        return null;
    }

    /** Native PHP front controller helper: serves and returns true when the request was a ReachAI endpoint. */
    public function handleGlobals(): bool
    {
        $response = $this->handle(Request::fromGlobals($this->options->contextPath, self::MAX_INVOCATION_BYTES));
        if ($response === null) {
            return false;
        }
        $response->send();
        return true;
    }

    private function assertReady(): void
    {
        $this->catalog->registrations();
        foreach ($this->catalog->all() as $item) {
            if ($item->businessIdentityRequired && $this->securityBridge === null) {
                throw new ReachAIException('business capabilities requiring identity need a securityBridge');
            }
        }
    }

    private function handleSync(Request $request): Response
    {
        if (!$this->registry->verifyCallback($request->header('X-ReachAI-App-Key'), $request->header('X-ReachAI-Timestamp'),
            $request->header('X-ReachAI-Nonce'), $request->header('X-ReachAI-Signature'))) {
            return Response::error(401, 'UNAUTHORIZED');
        }
        try {
            $result = $this->sync('SDK_CALLBACK');
            return Response::json(200, [
                'instanceId' => $this->registry->instanceId(),
                'capabilityCount' => count($this->catalog->all()),
                'httpApiCount' => 0,
                'registryResponse' => Protocol::encodeJson($result),
            ]);
        } catch (\Throwable $e) {
            $this->log('capability sync failed: ' . get_class($e), $e);
            return Response::error(502, 'SYNC_FAILED');
        }
    }

    private function handleInvoke(string $name, Request $request): Response
    {
        $handler = $this->catalog->find($name);
        if ($handler === null) {
            return Response::error(404, 'NOT_FOUND');
        }
        try {
            $this->assertReady();
        } catch (ReachAIException $e) {
            $this->log($e->getMessage(), $e);
            return Response::error(503, 'UNAVAILABLE');
        }
        try {
            $credentials = $this->registry->credentials();
            if ($credentials === null) {
                throw new UnauthorizedException('credentials unavailable');
            }
            $claims = Protocol::verifyInvocation($request->header('X-ReachAI-Invocation-Token'), $credentials->appKey(),
                $credentials->appSecret(), $this->options->projectCode, $name);
            $identity = InvocationContext::fromClaims($this->options->projectCode, $name, $claims);
            if ($handler->businessIdentityRequired && !$identity->hasUser()) {
                throw new UnauthorizedException('business identity required');
            }
            if ($handler->requiredRoles && !array_intersect($handler->requiredRoles, $identity->roles)) {
                return Response::error(403, 'FORBIDDEN');
            }
        } catch (UnauthorizedException $e) {
            return Response::error(401, 'UNAUTHORIZED');
        } catch (\Throwable $e) {
            $this->log('credential load failed: ' . get_class($e), $e);
            return Response::error(401, 'UNAUTHORIZED');
        }
        if (strlen($request->body) > self::MAX_INVOCATION_BYTES) {
            return Response::error(413, 'INVALID_ARGUMENTS');
        }
        try {
            $values = json_decode($request->body, false, 64, JSON_BIGINT_AS_STRING);
            if (json_last_error() !== JSON_ERROR_NONE) {
                throw new \InvalidArgumentException('invalid JSON');
            }
            $arguments = $handler->bind($values);
        } catch (\Throwable $e) {
            return Response::error(400, 'INVALID_ARGUMENTS');
        }
        $invoked = false;
        $result = null;
        $execute = static function () use ($handler, $arguments, &$invoked, &$result) {
            if ($invoked) {
                throw new ReachAIException('capability may only execute once');
            }
            $invoked = true;
            $result = $handler->call($arguments);
            return $result;
        };
        self::$invocations[] = $identity;
        try {
            $bridged = $this->securityBridge !== null ? call_user_func($this->securityBridge, $identity, $execute) : $execute();
            // A bridge that skips execution is a failure, not a business result.
            if (!$invoked) {
                throw new ReachAIException('security bridge did not invoke the capability');
            }
            if ($handler->businessIndexResolver) {
                return Response::json(200, BusinessIndexResolution::validate($bridged, $arguments[0], $identity));
            }
            return Response::json(200, $handler->prepareResult($bridged));
        } catch (\Throwable $e) {
            $this->log('capability ' . $name . ' failed: ' . get_class($e), $e);
            return Response::json(500, ['success' => false, 'code' => 'CAPABILITY_EXECUTION_FAILED', 'message' => '业务方法执行失败']);
        } finally {
            array_pop(self::$invocations);
        }
    }

    private function handleEmbed(Request $request): Response
    {
        try {
            $origin = Options::normalizeOrigin($request->header('Origin'));
        } catch (\InvalidArgumentException $e) {
            return Response::error(403, 'FORBIDDEN');
        }
        if (!in_array($origin, $this->options->allowedOrigins, true)) {
            return Response::error(403, 'FORBIDDEN');
        }
        $input = $this->embedInput($request->body);
        if ($input === null) {
            return Response::error(400, 'INVALID_REQUEST');
        }
        try {
            if (isset($input['origin']) && Options::normalizeOrigin($input['origin']) !== $origin) {
                return Response::error(403, 'FORBIDDEN');
            }
        } catch (\InvalidArgumentException $e) {
            return Response::error(403, 'FORBIDDEN');
        }
        if ($this->principalResolver === null) {
            return Response::error(503, 'UNAVAILABLE');
        }
        try {
            $principal = call_user_func($this->principalResolver, $request, $input['agentId']);
        } catch (\Throwable $e) {
            $this->log('principal resolver failed: ' . get_class($e), $e);
            return Response::error(401, 'UNAUTHORIZED');
        }
        if (!$principal instanceof EmbedPrincipal || trim($principal->externalUserId) === '') {
            return Response::error(401, 'UNAUTHORIZED');
        }
        try {
            return Response::json(200, $this->exchangeEmbedToken($input, $principal, $origin));
        } catch (HttpStatusException $e) {
            return Response::error(in_array($e->status(), [401, 403], true) ? 403 : 502, 'EXCHANGE_FAILED');
        } catch (\Throwable $e) {
            $this->log('embed exchange failed: ' . get_class($e), $e);
            return Response::error(502, 'EXCHANGE_FAILED');
        }
    }

    /** @return array<string, mixed>|null */
    private function embedInput(string $body): ?array
    {
        if (strlen($body) > 65536) {
            return null;
        }
        $value = json_decode($body, true, 8);
        if (!is_array($value) || $value === [] || Protocol::isList($value)
            || array_diff(array_keys($value), ['agentId', 'pageInstanceId', 'pageKey', 'route', 'origin'])) {
            return null;
        }
        foreach (['agentId', 'pageInstanceId'] as $required) {
            $item = $value[$required] ?? null;
            if (!is_string($item) || $item === '' || strlen($item) > 192) {
                return null;
            }
        }
        foreach (['pageKey', 'route', 'origin'] as $optional) {
            if (isset($value[$optional]) && (!is_string($value[$optional]) || strlen($value[$optional]) > 2048)) {
                return null;
            }
        }
        return $value;
    }

    /**
     * @param array<string, mixed> $body
     * @return array<string, mixed>
     */
    private function indexSend(string $indexCode, string $operation, array $body): array
    {
        if (preg_match('/^[a-z0-9][a-z0-9_-]{0,95}$/', $this->options->projectCode) !== 1
            || preg_match('/^[A-Za-z][A-Za-z0-9_]{1,62}$/', $indexCode) !== 1) {
            throw new \InvalidArgumentException('invalid business index path');
        }
        $path = '/api/knowledge-ingress/projects/' . $this->options->projectCode . '/biz-index/' . $indexCode . '/' . $operation;
        return $this->registry->post($path, $body, false, true, true);
    }

    private function log(string $message, ?\Throwable $error = null): void
    {
        try {
            call_user_func($this->logger, $message, $error);
        } catch (\Throwable $ignored) {
            // Logging must never change the response.
        }
    }
}
