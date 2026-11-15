<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Shared store for multi-server deployments. Table (MySQL example):
 *
 *   CREATE TABLE reachai_callback_nonce (
 *     nonce_hash CHAR(64) NOT NULL PRIMARY KEY,
 *     expires_at BIGINT NOT NULL,
 *     KEY idx_reachai_nonce_expiry (expires_at)
 *   );
 *
 * The primary key makes the insert atomic; any database error rejects the callback.
 */
final class PdoNonceStore implements NonceStore
{
    /** @var \PDO */
    private $pdo;
    /** @var string */
    private $table;

    public function __construct(\PDO $pdo, string $table = 'reachai_callback_nonce')
    {
        if (preg_match('/^[A-Za-z_][A-Za-z0-9_]{0,63}$/', $table) !== 1) {
            throw new \InvalidArgumentException('invalid nonce table name');
        }
        $this->pdo = $pdo;
        $this->table = $table;
    }

    public function claim(string $projectCode, string $nonce, int $expiresAtMs): bool
    {
        try {
            if (random_int(1, 50) === 1) {
                $this->pdo->prepare('DELETE FROM ' . $this->table . ' WHERE expires_at < ?')
                    ->execute([Protocol::nowMillis()]);
            }
            $statement = $this->pdo->prepare('INSERT INTO ' . $this->table . ' (nonce_hash, expires_at) VALUES (?, ?)');
            return $statement->execute([hash('sha256', $projectCode . "\n" . $nonce), $expiresAtMs]);
        } catch (\PDOException $e) {
            return false;
        }
    }
}
