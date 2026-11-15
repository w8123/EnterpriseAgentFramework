<?php

declare(strict_types=1);

namespace ReachAI\ThinkPHP;

use Closure;
use ReachAI\Framework\ActivitySettings;
use ReachAI\ReachAI;

/**
 * 主动感知 middleware for ThinkPHP 6/8. Register it globally (app/middleware.php) and set
 * REACHAI_ACTIVITY_ENABLED=true plus `activity_user_resolver` (ThinkPHP has no standard login user).
 * Recording happens in end(), after the response is sent.
 */
class RecordActivity
{
    /** @var float */
    private $startedAt = 0.0;
    /** @var mixed */
    private $request;

    /** @return mixed */
    public function handle($request, Closure $next)
    {
        $this->startedAt = microtime(true);
        $this->request = $request;
        return $next($request);
    }

    public function end($response): void
    {
        try {
            $app = app();
            $config = (array) $app->config->get('reachai', []);
            if (empty($config['activity_enabled']) || $this->request === null || empty($config['activity_user_resolver'])) {
                return;
            }
            $resolver = is_string($config['activity_user_resolver']) && class_exists($config['activity_user_resolver'])
                ? $app->make($config['activity_user_resolver']) : $config['activity_user_resolver'];
            $user = is_callable($resolver) ? $resolver($this->request) : null;
            if (!is_array($user) || empty($user['id'])) {
                return;
            }
            $request = $this->request;
            $rule = method_exists($request, 'rule') && $request->rule() !== null ? $request->rule() : null;
            $route = $rule !== null && method_exists($rule, 'getRule') ? (string) $rule->getRule() : '';
            $name = $rule !== null && method_exists($rule, 'getName') ? $rule->getName() : null;
            $contentType = '';
            $body = null;
            if (method_exists($response, 'getHeader')) {
                $contentType = (string) ($response->getHeader('Content-Type') ?? '');
            }
            if (method_exists($response, 'getContent') && stripos($contentType, 'json') !== false) {
                $body = $response->getContent();
            }
            /** @var ReachAI $reach */
            $reach = $app->make(ReachAI::class);
            $recorder = $reach->activity(ActivitySettings::fromConfig($config));
            $recorder->recordRequest([
                'method' => $request->method(),
                'route' => $route === '' ? null : '/' . ltrim(preg_replace('/<(\w+)\??>|:(\w+)/', '{$1$2}', $route), '/'),
                'path' => '/' . ltrim((string) $request->pathinfo(), '/'),
                'name' => is_string($name) ? $name : null,
                'status' => method_exists($response, 'getCode') ? $response->getCode() : 200,
                'body' => is_string($body) ? $body : null,
                'contentType' => $contentType,
                'referer' => (string) $request->header('referer', ''),
                'origin' => (string) $request->header('origin', ''),
                'query' => (array) $request->get(),
                'userId' => (string) $user['id'],
                'userName' => $user['name'] ?? null,
                'tenantId' => $user['tenantId'] ?? null,
                'startedAt' => $this->startedAt,
            ]);
            $recorder->flush();
        } catch (\Throwable $ignored) {
            // recording must never affect the business system
        }
    }
}
