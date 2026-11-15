<?php

declare(strict_types=1);

namespace ReachAI\Framework;

/** Turns config/reachai.php activity_* keys into ActivityRecorder settings (shared with ThinkPHP). */
final class ActivitySettings
{
    private function __construct()
    {
    }

    /**
     * @param array<string, mixed> $config
     * @return array<string, mixed>
     */
    public static function fromConfig(array $config): array
    {
        $settings = [];
        foreach (['activity_search_parameters' => 'searchParameters', 'activity_success_codes' => 'successCodes'] as $key => $name) {
            if (!empty($config[$key])) {
                $value = $config[$key];
                $settings[$name] = is_string($value) ? array_values(array_filter(array_map('trim', explode(',', $value)), 'strlen')) : (array) $value;
            }
        }
        if (array_key_exists('activity_browser_requests_only', $config)) {
            $settings['browserRequestsOnly'] = filter_var($config['activity_browser_requests_only'], FILTER_VALIDATE_BOOLEAN);
        }
        return $settings;
    }
}
