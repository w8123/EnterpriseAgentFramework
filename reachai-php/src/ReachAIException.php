<?php

declare(strict_types=1);

namespace ReachAI;

/** Base class for ReachAI SDK failures. Messages never contain secrets. */
class ReachAIException extends \RuntimeException
{
}
