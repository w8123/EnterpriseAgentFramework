<?php

declare(strict_types=1);

namespace ReachAI;

/** Groups capability declarations for framework adapters (listed in config 'capabilities'). */
interface CapabilityProvider
{
    public function register(ReachAI $reach): void;
}
