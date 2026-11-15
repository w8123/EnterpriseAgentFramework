<?php

// ReachAI configuration shared by the Laravel and ThinkPHP adapters.
// Secrets (enrollment token, app secret, credential key) come from the environment only.
return [
    'registry_url' => env('REACHAI_REGISTRY_URL', ''),
    'project_code' => env('REACHAI_PROJECT_CODE', ''),
    'project_name' => env('REACHAI_PROJECT_NAME', ''),
    'base_url' => env('REACHAI_BASE_URL', ''),
    'context_path' => env('REACHAI_CONTEXT_PATH', ''),
    'environment' => env('REACHAI_ENVIRONMENT', 'default'),
    'instance_id' => env('REACHAI_INSTANCE_ID', ''),
    'enrollment_token' => env('REACHAI_ENROLLMENT_TOKEN', ''),
    'app_key' => env('REACHAI_APP_KEY', ''),
    'app_secret' => env('REACHAI_APP_SECRET', ''),
    'heartbeat_seconds' => env('REACHAI_HEARTBEAT_SECONDS', 30),
    // Comma-separated business page origins allowed to call /reachai/embed/token.
    'allowed_origins' => env('REACHAI_ALLOWED_ORIGINS', ''),

    // Encrypted credential file outside the web root, and its base64 key (vendor/bin/reachai generate-key).
    'credential_file' => env('REACHAI_CREDENTIAL_FILE', ''),
    'credential_key' => env('REACHAI_CREDENTIAL_KEY', ''),

    // Callback replay protection. The file store covers one server; several servers need
    // a shared ReachAI\NonceStore class here (for example a ReachAI\PdoNonceStore subclass).
    'nonce_dir' => env('REACHAI_NONCE_DIR', ''),
    'nonce_store' => null,

    // Classes implementing ReachAI\CapabilityProvider.
    'capabilities' => [],
    // Set true only when the business system intentionally exposes no capabilities yet.
    'empty_inventory_complete' => false,

    // Invokable class: (ReachAI\Http\Request $request, string $agentId): ?ReachAI\EmbedPrincipal.
    // Must read the logged-in user from the framework session ($request->native), never from the request body.
    'principal_resolver' => null,
    // Invokable class: (ReachAI\InvocationContext $identity, callable $next): mixed.
    // Restore the business user, apply its permissions, then return $next().
    'security_bridge' => null,

    // 主动感知: record which operations and searches logged-in employees make (no request/response content).
    // Add ReachAI\Laravel\RecordActivity (or ReachAI\ThinkPHP\RecordActivity) as middleware, then switch on here.
    'activity_enabled' => env('REACHAI_ACTIVITY_ENABLED', false),
    // Invokable class: ($request) => ['id' => ..., 'name' => ..., 'tenantId' => ...] or null.
    // Laravel falls back to $request->user(); ThinkPHP needs this.
    'activity_user_resolver' => null,
    // GET parameters treated as search words, and response `code` values meaning success (comma-separated).
    'activity_search_parameters' => env('REACHAI_ACTIVITY_SEARCH_PARAMETERS', ''),
    'activity_success_codes' => env('REACHAI_ACTIVITY_SUCCESS_CODES', ''),
    // Only record requests from a browser page (Referer/Origin header).
    'activity_browser_requests_only' => true,

    // Laravel only: middleware for the browser-facing token broker (needs the session).
    'embed_middleware' => ['web'],
];
