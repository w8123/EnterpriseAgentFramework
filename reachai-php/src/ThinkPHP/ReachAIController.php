<?php

declare(strict_types=1);

namespace ReachAI\ThinkPHP;

use ReachAI\Http\Request as ReachRequest;
use ReachAI\ReachAI;
use think\App;
use think\Request;
use think\Response;

/** Bridges ThinkPHP requests to the framework-neutral ReachAI handler. */
class ReachAIController
{
    /** @var App */
    private $app;

    public function __construct(App $app)
    {
        $this->app = $app;
    }

    public function handle(Request $request): Response
    {
        $reach = $this->app->make(ReachAI::class);
        $body = (string) $request->getInput();
        $path = '/' . ltrim($request->pathinfo(), '/');
        $response = $reach->handle(new ReachRequest($request->method(), $path, (array) $request->header(), $body, $request));
        if ($response === null) {
            return Response::create(json_encode(['error' => 'NOT_FOUND']), 'html', 404)->contentType('application/json');
        }
        return Response::create($response->body, 'html', $response->status)->header($response->headers);
    }
}
