<?php

declare(strict_types=1);

namespace ReachAI\ThinkPHP;

use ReachAI\Cli;
use ReachAI\ReachAI;
use think\console\Command;
use think\console\Input;
use think\console\input\Argument;
use think\console\input\Option;
use think\console\Output;

/** php think reachai register|heartbeat [--loop]|sync|offline|inventory */
class ReachAICommand extends Command
{
    protected function configure(): void
    {
        $this->setName('reachai')
            ->addArgument('action', Argument::REQUIRED, 'register|heartbeat|sync|offline|inventory')
            ->addOption('loop', null, Option::VALUE_NONE, 'keep sending heartbeats until stopped')
            ->setDescription('ReachAI registration, heartbeat and capability sync');
    }

    protected function execute(Input $input, Output $output): int
    {
        $reach = $this->app->make(ReachAI::class);
        $cli = new Cli($reach, static function (string $line) use ($output): void {
            $output->writeln($line);
        });
        return $cli->run((string) $input->getArgument('action'), (bool) $input->getOption('loop'));
    }
}
