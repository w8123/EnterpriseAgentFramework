<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Durable credential storage. Enrollment tokens are single-use: an implementation
 * must persist the "enrollment started" marker before the request is sent.
 */
interface CredentialStore
{
    public function load(): ?Credentials;

    /** Must refuse to overwrite existing credentials. */
    public function save(Credentials $credentials): void;

    public function enrollmentUncertain(): bool;

    public function markEnrollmentStarted(): void;

    public function clearEnrollmentStarted(): void;
}
