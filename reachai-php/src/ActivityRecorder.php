<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * 主动感知: records which operations and searches logged-in employees make, and reports them to ReachAI so managers
 * see the common operations and problems. Request and response bodies are never sent; search words and error
 * messages are masked. Reporting happens after the response (framework terminate hook or shutdown) and failures are
 * swallowed, so the business system is never slowed down or broken by it.
 */
final class ActivityRecorder
{
    public const SDK = 'php-sdk';
    private const MAX_PENDING = 500;
    private const MAX_BODY_SCAN = 65536;

    /** @var RegistryClient */
    private $registry;
    /** @var string */
    private $projectCode;
    /** @var string[] */
    private $searchParameters;
    /** @var int[] */
    private $successCodes;
    /** @var bool */
    private $browserRequestsOnly;
    /** @var int */
    private $timeoutSeconds;
    /** @var callable|null */
    private $logger;
    /** @var array<int, array<string, mixed>> */
    private $pending = [];
    /** @var bool */
    private $shutdownRegistered = false;
    /** @var bool */
    private $disabledByReachAI = false;

    /** @param array<string, mixed> $settings searchParameters, successCodes, browserRequestsOnly, timeoutSeconds */
    public function __construct(RegistryClient $registry, string $projectCode, array $settings = [], ?callable $logger = null)
    {
        $this->registry = $registry;
        $this->projectCode = strtolower(trim($projectCode));
        $this->searchParameters = array_values(array_filter(array_map('strval', (array) ($settings['searchParameters']
            ?? ['keyword', 'keywords', 'q', 'query', 'search', 'searchKey', 'name', 'title', 'code', 'no']))));
        $this->successCodes = array_map('intval', (array) ($settings['successCodes'] ?? [0, 200]));
        $this->browserRequestsOnly = (bool) ($settings['browserRequestsOnly'] ?? true);
        $this->timeoutSeconds = max(1, (int) ($settings['timeoutSeconds'] ?? 3));
        $this->logger = $logger;
    }

    /**
     * Record one handled request. Facts: method, route (path template, e.g. /customer/{id}), path (fallback),
     * name, status, body (response body, optional), contentType, referer, origin, query (array), userId, userName,
     * tenantId, startedAt (microtime(true)). Returns the event, or null when nothing was recorded.
     *
     * @param array<string, mixed> $facts
     * @return array<string, mixed>|null
     */
    public function recordRequest(array $facts): ?array
    {
        try {
            $event = $this->event($facts);
            if ($event !== null) {
                $this->record($event);
            }
            return $event;
        } catch (\Throwable $ignored) {
            return null;
        }
    }

    /** @param array<string, mixed> $event already-built event (operation, userId, outcome…) */
    public function record(array $event): void
    {
        if ($this->disabledByReachAI || count($this->pending) >= self::MAX_PENDING) {
            return;
        }
        $this->pending[] = $event;
    }

    /** @return array<int, array<string, mixed>> */
    public function pending(): array
    {
        return $this->pending;
    }

    /** Send everything recorded in this request. Never throws. */
    public function flush(): void
    {
        if ($this->pending === []) {
            return;
        }
        $events = $this->pending;
        $this->pending = [];
        if (preg_match('/^[a-z0-9][a-z0-9_-]{0,95}$/', $this->projectCode) !== 1) {
            return;
        }
        try {
            $answer = $this->registry->post('/api/activity-ingress/projects/' . $this->projectCode . '/events',
                ['sdk' => self::SDK, 'events' => $events], false, true, true, $this->timeoutSeconds);
            $data = isset($answer['data']) && is_array($answer['data']) ? $answer['data'] : $answer;
            if (array_key_exists('enabled', $data) && $data['enabled'] === false) {
                $this->disabledByReachAI = true;
            }
        } catch (\Throwable $error) {
            if ($this->logger !== null) {
                try {
                    call_user_func($this->logger, 'activity report failed; ' . count($events) . ' event(s) dropped', $error);
                } catch (\Throwable $ignored) {
                    // logging must not break the request
                }
            }
        }
    }

    /** Native PHP: send after the response has gone to the browser. */
    public function flushAtShutdown(): void
    {
        if ($this->shutdownRegistered) {
            return;
        }
        $this->shutdownRegistered = true;
        register_shutdown_function(function (): void {
            if (function_exists('fastcgi_finish_request')) {
                @fastcgi_finish_request();
            }
            $this->flush();
        });
    }

    /**
     * @param array<string, mixed> $facts
     * @return array<string, mixed>|null
     */
    public function event(array $facts): ?array
    {
        $userId = trim((string) ($facts['userId'] ?? ''));
        if ($userId === '') {
            return null;
        }
        $referer = trim((string) ($facts['referer'] ?? ''));
        $origin = trim((string) ($facts['origin'] ?? ''));
        if ($this->browserRequestsOnly && $referer === '' && $origin === '') {
            return null;
        }
        $method = strtoupper(trim((string) ($facts['method'] ?? 'GET'))) ?: 'GET';
        $route = trim((string) ($facts['route'] ?? ''));
        if ($route === '') {
            $route = (string) parse_url((string) ($facts['path'] ?? ''), PHP_URL_PATH);
        }
        if ($route === '' || strpos($route, 'reachai/') === 0 || strpos($route, '/reachai/') === 0) {
            return null;
        }
        if ($route[0] !== '/') {
            $route = '/' . $route;
        }
        $event = ['type' => 'OPERATION', 'operation' => $method . ' ' . $route];
        $name = trim((string) ($facts['name'] ?? ''));
        if ($name !== '') {
            $event['operationName'] = self::truncate($name, 100);
        }
        $page = self::pagePath($referer);
        if ($page !== null) {
            $event['page'] = $page;
        }
        if ($method === 'GET') {
            $query = (array) ($facts['query'] ?? []);
            foreach ($this->searchParameters as $parameter) {
                $value = $query[$parameter] ?? null;
                if (is_string($value) && trim($value) !== '') {
                    $event['keyword'] = self::mask(self::truncate(trim($value), 64));
                    $event['type'] = 'SEARCH';
                    break;
                }
            }
        }
        $failure = null;
        if (!empty($facts['error'])) {
            $failure = (string) $facts['error'];
        }
        if ($failure === null) {
            $failure = $this->businessFailure($facts['body'] ?? null, (string) ($facts['contentType'] ?? ''));
        }
        $status = (int) ($facts['status'] ?? 200);
        if ($failure === null && $status >= 400) {
            $failure = 'HTTP ' . $status;
        }
        $event['outcome'] = $failure === null ? 'SUCCESS' : 'FAILED';
        if ($failure !== null) {
            $event['error'] = self::mask(self::truncate($failure, 200));
        }
        if (isset($facts['startedAt']) && is_numeric($facts['startedAt'])) {
            $event['durationMs'] = max(0, (int) round((microtime(true) - (float) $facts['startedAt']) * 1000));
        }
        $event['userId'] = self::truncate($userId, 128);
        if (!empty($facts['userName'])) {
            $event['userName'] = self::truncate((string) $facts['userName'], 128);
        }
        if (!empty($facts['tenantId'])) {
            $event['tenantId'] = self::truncate((string) $facts['tenantId'], 96);
        }
        $event['occurredAt'] = Protocol::nowMillis();
        return $event;
    }

    /** Reads {"code": 1002, "msg": "..."} style answers; HTTP 200 does not always mean success. */
    public function businessFailure($body, string $contentType = ''): ?string
    {
        if (is_string($body)) {
            if ($body === '' || strlen($body) > self::MAX_BODY_SCAN
                || ($contentType !== '' && stripos($contentType, 'json') === false)) {
                return null;
            }
            $body = json_decode($body, true);
        }
        if (!is_array($body)) {
            return null;
        }
        $failed = false;
        if (array_key_exists('success', $body) && $body['success'] === false) {
            $failed = true;
        }
        $code = $body['code'] ?? null;
        if ((is_int($code) || (is_string($code) && preg_match('/^-?\d{1,9}$/', $code) === 1))
            && !in_array((int) $code, $this->successCodes, true)) {
            $failed = true;
        }
        if (!$failed) {
            return null;
        }
        foreach (['msg', 'message', 'error'] as $key) {
            if (isset($body[$key]) && is_string($body[$key]) && trim($body[$key]) !== '') {
                return trim($body[$key]);
            }
        }
        return is_int($code) || is_string($code) ? '业务返回码 ' . $code : '业务处理失败';
    }

    public static function pagePath(string $referer): ?string
    {
        if ($referer === '') {
            return null;
        }
        $path = strpos($referer, '://') !== false ? (string) parse_url($referer, PHP_URL_PATH) : $referer;
        $path = preg_split('/[?#]/', $path)[0] ?? '';
        return $path === '' ? null : self::truncate($path, 300);
    }

    public static function mask(string $value): string
    {
        $value = (string) preg_replace('/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/', '***@***', $value);
        $value = (string) preg_replace('/(?<!\d)(1[3-9]\d)\d{4}(\d{4})(?!\d)/', '$1****$2', $value);
        return (string) preg_replace('/(?<!\d)\d{15,}[\dXx]?(?!\d)/', '******', $value);
    }

    private static function truncate(string $value, int $max): string
    {
        if (function_exists('mb_substr')) {
            return mb_strlen($value, 'UTF-8') <= $max ? $value : mb_substr($value, 0, $max, 'UTF-8');
        }
        return strlen($value) <= $max ? $value : substr($value, 0, $max);
    }
}
