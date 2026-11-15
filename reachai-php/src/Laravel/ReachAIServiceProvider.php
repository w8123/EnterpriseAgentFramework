<?php

declare(strict_types=1);

namespace ReachAI\Laravel;

use Illuminate\Support\Facades\Route;
use Illuminate\Support\ServiceProvider;
use ReachAI\Framework\Factory;
use ReachAI\ReachAI;

/**
 * Laravel 8–12 provider (auto-discovered through composer extra.laravel.providers).
 * Registers the /reachai/* routes and `php artisan reachai <action>`.
 */
class ReachAIServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->mergeConfigFrom(__DIR__ . '/../../config/reachai.php', 'reachai');
        $this->app->singleton(ReachAI::class, function ($app) {
            return Factory::fromConfig((array) $app['config']->get('reachai', []), static function (string $class) use ($app) {
                return $app->make($class);
            });
        });
    }

    public function boot(): void
    {
        $this->publishes([__DIR__ . '/../../config/reachai.php' => $this->app->configPath('reachai.php')], 'reachai-config');
        if ($this->app->runningInConsole()) {
            $this->commands([ReachAICommand::class]);
        }
        if (method_exists($this->app, 'routesAreCached') && $this->app->routesAreCached()) {
            return;
        }
        // Server-to-server endpoints: authenticated by ReachAI signatures, no session or CSRF.
        Route::post('reachai/registry/capabilities/sync', [ReachAIController::class, 'handle']);
        Route::post('reachai/capabilities/{name}/invoke', [ReachAIController::class, 'handle'])
            ->where('name', '[A-Za-z0-9_.\-]+');
        // Browser token broker: needs the business session. The SDK enforces the Origin allowlist,
        // which is the CSRF defence for this JSON endpoint, so the CSRF token middleware is skipped.
        $csrf = array_values(array_filter([
            'Illuminate\\Foundation\\Http\\Middleware\\VerifyCsrfToken',
            'Illuminate\\Foundation\\Http\\Middleware\\ValidateCsrfToken',
            'Illuminate\\Foundation\\Http\\Middleware\\PreventRequestForgery',
            'App\\Http\\Middleware\\VerifyCsrfToken',
        ], 'class_exists'));
        Route::middleware((array) $this->app['config']->get('reachai.embed_middleware', ['web']))
            ->post('reachai/embed/token', [ReachAIController::class, 'handle'])
            ->withoutMiddleware($csrf);
    }
}
