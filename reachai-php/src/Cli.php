<?php

declare(strict_types=1);

namespace ReachAI;

/**
 * Lifecycle commands shared by bin/reachai, Laravel artisan and ThinkPHP think.
 * PHP-FPM has no background thread, so registration and heartbeat run here:
 * either from cron every minute (`heartbeat`) or as a supervised loop (`heartbeat --loop`).
 */
final class Cli
{
    /** @var ReachAI */
    private $reach;
    /** @var callable */
    private $out;
    /** @var bool */
    private $stopping = false;

    public function __construct(ReachAI $reach, ?callable $out = null)
    {
        $this->reach = $reach;
        $this->out = $out ?? static function (string $line): void {
            fwrite(STDOUT, $line . PHP_EOL);
        };
    }

    /** @return int process exit code */
    public function run(string $command, bool $loop = false): int
    {
        try {
            switch ($command) {
                case 'register':
                    $this->ensureRegistered(true);
                    return 0;
                case 'heartbeat':
                    return $loop ? $this->loop() : $this->beatOnce();
                case 'sync':
                    $this->ensureRegistered(false);
                    $result = $this->reach->sync('SDK');
                    $data = $result['data'] ?? $result;
                    $this->say('能力同步已提交：' . count($this->reach->catalog()->all()) . ' 个业务方法，快照 '
                        . (is_array($data) ? (string) ($data['syncId'] ?? '') : ''));
                    return 0;
                case 'offline':
                    $this->reach->offline();
                    $this->say('实例已下线：' . $this->reach->registry()->instanceId());
                    return 0;
                case 'inventory':
                    $this->say(Protocol::encodeJson($this->reach->catalog()->registrations()));
                    return 0;
                default:
                    $this->say('用法: reachai <register|heartbeat [--loop]|sync|offline|inventory> --bootstrap=<file>');
                    return 2;
            }
        } catch (\Throwable $e) {
            $this->say('ReachAI ' . $command . ' 失败：' . get_class($e) . ': ' . $e->getMessage());
            return 1;
        }
    }

    private function ensureRegistered(bool $always): void
    {
        $registry = $this->reach->registry();
        if ($always || $registry->credentials() === null) {
            $registry->register();
            $this->say('项目已注册：' . $this->reach->options()->projectCode);
        }
    }

    private function beatOnce(): int
    {
        $this->ensureRegistered(false);
        $this->reach->heartbeat();
        $this->say('心跳成功：' . $this->reach->registry()->instanceId());
        return 0;
    }

    private function loop(): int
    {
        if (function_exists('pcntl_async_signals')) {
            pcntl_async_signals(true);
            $stop = function (): void {
                $this->stopping = true;
            };
            pcntl_signal(SIGTERM, $stop);
            pcntl_signal(SIGINT, $stop);
        }
        $beaten = false;
        while (!$this->stopping) {
            try {
                $this->beatOnce();
                $beaten = true;
            } catch (\Throwable $e) {
                $this->say('ReachAI 心跳失败：' . get_class($e) . ': ' . $e->getMessage());
            }
            $deadline = time() + $this->reach->options()->heartbeatSeconds;
            while (!$this->stopping && time() < $deadline) {
                sleep(1);
            }
        }
        if ($beaten) {
            try {
                $this->reach->offline();
                $this->say('实例已下线');
            } catch (\Throwable $e) {
                $this->say('ReachAI 下线失败：' . get_class($e));
            }
        }
        return 0;
    }

    private function say(string $line): void
    {
        call_user_func($this->out, $line);
    }
}
