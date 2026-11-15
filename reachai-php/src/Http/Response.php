<?php

declare(strict_types=1);

namespace ReachAI\Http;

use ReachAI\Protocol;

/** JSON response produced by the SDK; adapters copy it into the framework's response. */
final class Response
{
    /** @var int */
    public $status;
    /** @var array<string, string> */
    public $headers;
    /** @var string */
    public $body;

    /** @param array<string, string> $headers */
    public function __construct(int $status, string $body, array $headers = [])
    {
        $this->status = $status;
        $this->body = $body;
        $this->headers = array_merge(['Content-Type' => 'application/json; charset=utf-8', 'Cache-Control' => 'no-store'], $headers);
    }

    /** @param mixed $value */
    public static function json(int $status, $value): self
    {
        return new self($status, Protocol::encodeJson($value));
    }

    public static function error(int $status, string $code): self
    {
        return self::json($status, ['error' => $code]);
    }

    /** Emit with native PHP (header()/echo). */
    public function send(): void
    {
        if (!headers_sent()) {
            http_response_code($this->status);
            foreach ($this->headers as $name => $value) {
                header($name . ': ' . $value);
            }
        }
        echo $this->body;
    }
}
