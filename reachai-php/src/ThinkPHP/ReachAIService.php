<?php

declare(strict_types=1);

namespace ReachAI\ThinkPHP;

use ReachAI\Framework\Factory;
use ReachAI\ReachAI;
use think\Route;
use think\Service;

/**
 * ThinkPHP 6/8 service (auto-discovered through composer extra.think.services).
 * Registers the /reachai/* routes and the `php think reachai <action>` command.
 */
class ReachAIService extends Service
{
    public function register(): void
    {
        $this->app->bind(ReachAI::class, function () {
            $config = (array) $this->app->config->get('reachai', []);
            return Factory::fromConfig($config, function (string $class) {
                return $this->app->make($class);
            });
        });
    }

    public function boot(): void
    {
        $this->registerRoutes(static function (Route $route): void {
            $route->post('reachai/registry/capabilities/sync', ReachAIController::class . '@handle');
            $route->post('reachai/capabilities/:name/invoke', ReachAIController::class . '@handle')
                ->pattern(['name' => '[A-Za-z0-9_.\-]+']);
            $route->post('reachai/embed/token', ReachAIController::class . '@handle');
        });
        $this->commands(['reachai' => ReachAICommand::class]);
    }
}
