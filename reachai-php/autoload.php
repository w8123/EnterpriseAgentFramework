<?php

declare(strict_types=1);

// Autoloader for hosts that do not use Composer: require this file once.
spl_autoload_register(static function (string $class): void {
    if (strpos($class, 'ReachAI\\') !== 0) {
        return;
    }
    $file = __DIR__ . '/src/' . str_replace('\\', '/', substr($class, strlen('ReachAI\\'))) . '.php';
    if (is_file($file)) {
        require $file;
    }
});
