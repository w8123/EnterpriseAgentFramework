<?php

declare(strict_types=1);

namespace ReachAI\Laravel;

use Closure;
use ReachAI\Framework\ActivitySettings;
use ReachAI\ReachAI;

/**
 * 主动感知 middleware for Laravel. Add it to the `web` / `api` groups after authentication and set
 * REACHAI_ACTIVITY_ENABLED=true. It records after the response is sent (terminate), so it never slows requests.
 */
class RecordActivity
{
    /** @var ReachAI */
    private $reach;

    public function __construct(ReachAI $reach)
    {
        $this->reach = $reach;
    }

    /** @return mixed */
    public function handle($request, Closure $next)
    {
        $request->attributes->set('reachai.activity.started', microtime(true));
        return $next($request);
    }

    public function terminate($request, $response): void
    {
        try {
            $config = (array) config('reachai', []);
            if (empty($config['activity_enabled'])) {
                return;
            }
            $user = $this->user($request, $config);
            if ($user === null) {
                return;
            }
            $route = $request->route();
            $body = null;
            $contentType = (string) $response->headers->get('Content-Type', '');
            if (stripos($contentType, 'json') !== false && method_exists($response, 'getContent')) {
                $body = $response->getContent();
            }
            $recorder = $this->reach->activity(ActivitySettings::fromConfig($config));
            $recorder->recordRequest([
                'method' => $request->getMethod(),
                'route' => $route !== null && method_exists($route, 'uri') ? '/' . ltrim((string) $route->uri(), '/') : null,
                'path' => '/' . ltrim((string) $request->path(), '/'),
                'name' => $route !== null && method_exists($route, 'getName') ? $route->getName() : null,
                'status' => $response->getStatusCode(),
                'body' => is_string($body) ? $body : null,
                'contentType' => $contentType,
                'referer' => (string) $request->headers->get('Referer', ''),
                'origin' => (string) $request->headers->get('Origin', ''),
                'query' => (array) $request->query(),
                'userId' => $user['id'],
                'userName' => $user['name'] ?? null,
                'tenantId' => $user['tenantId'] ?? null,
                'startedAt' => $request->attributes->get('reachai.activity.started'),
            ]);
            $recorder->flush();
        } catch (\Throwable $ignored) {
            // recording must never affect the business system
        }
    }

    /**
     * @param array<string, mixed> $config
     * @return array<string, mixed>|null
     */
    private function user($request, array $config): ?array
    {
        if (!empty($config['activity_user_resolver'])) {
            $resolver = is_string($config['activity_user_resolver']) && class_exists($config['activity_user_resolver'])
                ? app($config['activity_user_resolver']) : $config['activity_user_resolver'];
            $resolved = is_callable($resolver) ? $resolver($request) : null;
            return is_array($resolved) && !empty($resolved['id']) ? $resolved : null;
        }
        $user = $request->user();
        if ($user === null) {
            return null;
        }
        $id = method_exists($user, 'getAuthIdentifier') ? $user->getAuthIdentifier() : ($user->id ?? null);
        if ($id === null || $id === '') {
            return null;
        }
        return ['id' => (string) $id, 'name' => $user->name ?? ($user->username ?? null)];
    }
}
