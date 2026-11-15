<?php

declare(strict_types=1);

namespace ReachAI\Http;

/** Framework-neutral view of an incoming request to a /reachai/* endpoint. */
final class Request
{
    /** @var string */
    public $method;
    /** @var string path relative to the application (context path removed) */
    public $path;
    /** @var array<string, string> lowercase header name => value */
    public $headers;
    /** @var string */
    public $body;
    /** @var mixed the framework's own request object (Laravel/ThinkPHP), for principal resolvers */
    public $native;

    /**
     * @param array<string, string> $headers
     * @param mixed $native
     */
    public function __construct(string $method, string $path, array $headers, string $body, $native = null)
    {
        $this->method = strtoupper($method);
        $this->path = $path;
        $normalized = [];
        foreach ($headers as $name => $value) {
            $normalized[strtolower((string) $name)] = is_array($value) ? (string) reset($value) : (string) $value;
        }
        $this->headers = $normalized;
        $this->body = $body;
        $this->native = $native;
    }

    public function header(string $name): string
    {
        return $this->headers[strtolower($name)] ?? '';
    }

    /** Build from PHP superglobals; reads at most $maxBody + 1 bytes of the body. */
    public static function fromGlobals(string $contextPath = '', int $maxBody = 1048576): self
    {
        $headers = [];
        foreach ($_SERVER as $key => $value) {
            if (strpos((string) $key, 'HTTP_') === 0) {
                $headers[str_replace('_', '-', substr((string) $key, 5))] = (string) $value;
            } elseif (in_array($key, ['CONTENT_TYPE', 'CONTENT_LENGTH'], true)) {
                $headers[str_replace('_', '-', (string) $key)] = (string) $value;
            }
        }
        $path = (string) parse_url((string) ($_SERVER['REQUEST_URI'] ?? '/'), PHP_URL_PATH);
        if ($contextPath !== '' && strpos($path, $contextPath . '/') === 0) {
            $path = substr($path, strlen($contextPath));
        }
        $body = '';
        $stream = fopen('php://input', 'rb');
        if ($stream !== false) {
            $body = (string) stream_get_contents($stream, $maxBody + 1);
            fclose($stream);
        }
        return new self((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'), $path, $headers, $body);
    }
}
