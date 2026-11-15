# PHP（原生 PHP / Laravel / ThinkPHP）后端接入

> 验证情况：已在原生 PHP、ThinkPHP 8、Laravel 13（PHP 8.3）上用模拟平台跑通注册、心跳、签名同步、调用验签和 Embed 换票；尚未连真实 ReachAI 做端到端验收。

## 适用范围

PHP 7.4–8.4，需要 `ext-curl`、`ext-json`、`ext-openssl`，没有第三方依赖。框架：Laravel 8 及以上、ThinkPHP 6/8，其他框架按原生 PHP 接。

## 准备

1. 调用 `reachai_onboarding_resources` 获取 `sdkArtifacts`，选择 `language=php` 的制品。注册地址使用 AI 所连接的 ReachAI 平台根地址 `{{reachaiUrl}}`，从业务服务器确认该地址可达。首次接入先确认项目编码「{{projectCode}}」，用 `reachai_onboarding_enrollment_request` 申请并由本人在「待办与审核」批准，再用 `reachai_onboarding_enrollment_execute` 获取一次性令牌。
2. 所有密钥（Enrollment Token、AppSecret、凭据加密密钥）只放环境变量或密钥管理器，不写进代码、配置文件、日志和浏览器。
3. ReachAI 的制品地址不是 Packagist，按 `downloadUrl` 下载并校验 `integritySha256`。

## 步骤

1. 把核验过的 `reachai-php-sdk-1.0.0.zip` 原样放到 `third_party/reachai/`，执行：

```bash
composer config repositories.reachai artifact ./third_party/reachai
composer require reachai/php-sdk:1.0.0
vendor/bin/reachai generate-key        # 生成 REACHAI_CREDENTIAL_KEY，放进环境变量
```

2. 在 `.env` 或环境变量里配置：

```bash
REACHAI_REGISTRY_URL={{reachaiUrl}}
REACHAI_PROJECT_CODE={{projectCode}}
REACHAI_BASE_URL=https://erp.example.com        # ReachAI 能访问到的业务系统地址
REACHAI_ALLOWED_ORIGINS=https://erp.example.com # 业务页面来源，逗号分隔
REACHAI_ENROLLMENT_TOKEN=...                    # 仅首次注册
REACHAI_CREDENTIAL_FILE=/var/lib/myapp/reachai.cred
REACHAI_CREDENTIAL_KEY=...
```

3. 声明业务能力并接入身份：
   - **Laravel**：`php artisan vendor:publish --tag=reachai-config`，在 `config/reachai.php` 填 `capabilities`（实现 `ReachAI\CapabilityProvider` 的类）、`principal_resolver`、`security_bridge`。
   - **ThinkPHP**：安装后自动生成 `config/reachai.php`，同样填这三项；在 `app/middleware.php` 启用 `\think\middleware\SessionInit::class`。
   - **原生 PHP**：写一个返回 `$reach` 的 `reachai.php`，入口文件最前面 `if ($reach->handleGlobals()) return;`。

```php
$reach->capability('order.query', [$orders, 'find'], [
    'title' => '查询订单', 'sideEffect' => 'READ', 'businessIdentityRequired' => true,
    'parameters' => ['orderId' => ['type' => 'integer', 'description' => '订单号']],
]);
```

4. 首次注册并配置心跳（PHP-FPM 没有后台线程，心跳靠命令行）：

```bash
php artisan reachai register      # ThinkPHP: php think reachai register；原生: vendor/bin/reachai register --bootstrap=reachai.php
# crontab
* * * * * cd /www/myapp && php artisan reachai heartbeat
```

## 自检

- [ ] `reachai heartbeat` 输出「心跳成功」，ReachAI 项目详情里实例在线，语言显示 php。
- [ ] 在 ReachAI 里触发一次 SDK 同步，业务方法出现在能力目录里。
- [ ] 用一个只读业务方法试调用成功，结果和业务系统页面上一致。
- [ ] 换一个权限更低的账号，看不到 / 调不了没有权限的数据。
- [ ] 让 AI 调用 `reachai_doctor`（projectCode="{{projectCode}}"），「业务系统 SDK 实例」为 PASS。

## 常见问题

- **注册失败 401 / 403**：一次性令牌只能用一次。如果出现 `.enrollment-pending` 文件，说明上次注册结果不确定，先到平台核对，不要直接删标记重试。
- **实例时有时无**：cron 没配或执行失败；心跳间隔超过平台超时也会显示离线，可改用 `heartbeat --loop` 常驻。
- **同步回调 401**：凭据文件读不到（FPM 运行账号没有权限，或 `REACHAI_CREDENTIAL_KEY` 与注册时不同），或多台服务器没有共享 nonce 存储。
- **Laravel 换票 419**：自定义了 CSRF 中间件类名，把 `reachai/embed/token` 加进 CSRF 例外。
- **普通 HTTP 接口**：不是 SDK 能力的接口用 HTTP API 导入（OpenAPI / curl），不要伪装成 SDK 能力。
