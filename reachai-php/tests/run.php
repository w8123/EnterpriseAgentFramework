<?php

declare(strict_types=1);

// Zero-dependency test runner: php tests/run.php
require __DIR__ . '/bootstrap.php';

$tests = [];
foreach (glob(__DIR__ . '/*Test.php') as $file) {
    $tests = array_merge($tests, require $file);
}
$failed = 0;
foreach ($tests as $name => $test) {
    try {
        $test();
        echo "ok   {$name}\n";
    } catch (Throwable $e) {
        $failed++;
        echo "FAIL {$name}\n  " . get_class($e) . ': ' . $e->getMessage() . "\n  at " . $e->getFile() . ':' . $e->getLine() . "\n";
    }
}
echo "\n" . (count($tests) - $failed) . '/' . count($tests) . " passed\n";
exit($failed === 0 ? 0 : 1);
