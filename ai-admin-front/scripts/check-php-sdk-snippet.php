<?php

declare(strict_types=1);

// Execute the actual wizard's PHP template against the SDK source, without network access.
$root = dirname(__DIR__, 2);
$source = (string) file_get_contents(__DIR__ . '/../src/views/registry/composables/useSdkAccessWizardPhpSnippets.ts');
if (!preg_match('/return `(<\?php.+?)`\s*\n/s', $source, $match)) {
    throw new RuntimeException('PHP wizard template not found');
}
$snippet = $match[1];
// Undo JavaScript template-literal escaping.
$snippet = str_replace('\\\\', '\\', $snippet);
foreach (['registry' => "'http://127.0.0.1:18603'", 'projectCode' => "'snippet-test'", 'projectName' => "'测试项目'",
             'baseUrl' => "'http://127.0.0.1:18790'"] as $key => $value) {
    $placeholder = '${' . $key . '}';
    if (strpos($snippet, $placeholder) === false) {
        throw new RuntimeException('missing ' . $placeholder);
    }
    $snippet = str_replace($placeholder, $value, $snippet);
}
if (strpos($snippet, '${') !== false) {
    throw new RuntimeException('unexpected template interpolation');
}
$dir = sys_get_temp_dir() . '/reachai-php-snippet-' . bin2hex(random_bytes(4));
mkdir($dir . '/vendor', 0700, true);
file_put_contents($dir . '/vendor/autoload.php', "<?php require " . var_export($root . '/reachai-php/autoload.php', true) . ";\n");
file_put_contents($dir . '/reachai.php', $snippet);
putenv('REACHAI_ENROLLMENT_TOKEN=test-only-token');
putenv('REACHAI_ALLOWED_ORIGINS=http://127.0.0.1:18790');
putenv('REACHAI_CREDENTIAL_FILE=' . $dir . '/credentials');
putenv('REACHAI_CREDENTIAL_KEY=' . base64_encode(random_bytes(32)));
$reach = require $dir . '/reachai.php';
if (!$reach instanceof ReachAI\ReachAI) {
    throw new RuntimeException('template must return a ReachAI instance');
}
$registrations = $reach->catalog()->registrations();
if (count($registrations) !== 1 || $registrations[0]['name'] !== 'order.query'
    || $registrations[0]['metadata']['resultSchema']['type'] !== 'object') {
    throw new RuntimeException('unexpected registrations');
}
$response = $reach->handle(new ReachAI\Http\Request('POST', '/reachai/registry/capabilities/sync', [], ''));
if ($response === null || $response->status !== 401) {
    throw new RuntimeException('unsigned callback must be rejected');
}
echo "PHP wizard template registered its typed capability and routes\n";
