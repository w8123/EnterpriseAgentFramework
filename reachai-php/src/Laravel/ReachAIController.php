<?php

declare(strict_types=1);

namespace ReachAI\Laravel;

use Illuminate\Http\Request;
use Illuminate\Http\Response;
use ReachAI\Http\Request as ReachRequest;
use ReachAI\ReachAI;

/** Bridges Laravel requests to the framework-neutral ReachAI handler. */
class ReachAIController
{
    public function handle(Request $request, ReachAI $reach): Response
    {
        $headers = [];
        foreach ($request->headers->all() as $name => $values) {
            $headers[$name] = (string) ($values[0] ?? '');
        }
        $path = '/' . ltrim($request->path(), '/');
        $response = $reach->handle(new ReachRequest($request->getMethod(), $path, $headers, (string) $request->getContent(), $request));
        if ($response === null) {
            return new Response('{"error":"NOT_FOUND"}', 404, ['Content-Type' => 'application/json']);
        }
        return new Response($response->body, $response->status, $response->headers);
    }
}
