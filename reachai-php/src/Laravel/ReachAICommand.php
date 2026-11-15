<?php

declare(strict_types=1);

namespace ReachAI\Laravel;

use Illuminate\Console\Command;
use ReachAI\Cli;
use ReachAI\ReachAI;

/** php artisan reachai register|heartbeat [--loop]|sync|offline|inventory */
class ReachAICommand extends Command
{
    /** @var string */
    protected $signature = 'reachai {action : register|heartbeat|sync|offline|inventory} {--loop : keep sending heartbeats until stopped}';

    /** @var string */
    protected $description = 'ReachAI registration, heartbeat and capability sync';

    public function handle(ReachAI $reach): int
    {
        $cli = new Cli($reach, function (string $line): void {
            $this->line($line);
        });
        return $cli->run((string) $this->argument('action'), (bool) $this->option('loop'));
    }
}
