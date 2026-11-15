<?php

declare(strict_types=1);

namespace ReachAI;

/** ReachAI answered with a non-2xx HTTP status. */
final class HttpStatusException extends ReachAIException
{
    /** @var int */
    private $status;

    public function __construct(int $status)
    {
        parent::__construct('ReachAI request failed with HTTP ' . $status);
        $this->status = $status;
    }

    public function status(): int
    {
        return $this->status;
    }
}
