# PHP 接入（原生 PHP / Laravel / ThinkPHP）

请求项目接入清单时选择 `language=php`。从 `sdkArtifacts` 选择 `language=php`、`packageName=reachai/php-sdk`，下载清单提供的 ZIP，核验 `integritySha256` 后原样放到业务工程的 `third_party/reachai/` 目录（不要解压），执行清单的安装命令：`composer config repositories.reachai artifact ./third_party/reachai && composer require reachai/php-sdk:1.0.0`。平台的制品地址不是 Packagist，不依赖公网上存在该包。没有 Composer 的项目解压后 `require reachai-php/autoload.php`。

先识别宿主：`composer.json` 里有 `laravel/framework` 是 Laravel（Provider 自动发现，`php artisan vendor:publish --tag=reachai-config`）；有 `topthink/framework` 是 ThinkPHP 6/8（Service 自动发现，安装后自动生成 `config/reachai.php`，并在 `app/middleware.php` 启用 `\think\middleware\SessionInit`）；其他情况按原生 PHP 写一个返回 `$reach` 的 `reachai.php`，并在入口文件最前面调用 `$reach->handleGlobals()`。

PHP-FPM 没有常驻后台：Web 请求只处理 `/reachai/registry/capabilities/sync`、`/reachai/capabilities/{name}/invoke`、`/reachai/embed/token`。首次注册和心跳只在命令行执行：`vendor/bin/reachai register --bootstrap=reachai.php`（Laravel `php artisan reachai register`，ThinkPHP `php think reachai register`），之后 cron 每分钟 `heartbeat`，或 Supervisor 常驻 `heartbeat --loop`。不要在 Web 请求里注册，避免多个 FPM 进程同时消费一次性令牌。

一次性令牌、`REACHAI_APP_SECRET`、凭据加密密钥（`vendor/bin/reachai generate-key` 生成）只放环境变量或秘密管理服务，不写入源码、日志或浏览器。`REACHAI_CREDENTIAL_FILE` 放在 Web 根目录之外。回调防重放默认是单机文件存储；多台服务器必须配置共享 `NonceStore`（如 `PdoNonceStore`）。

明确声明业务能力：参数和返回值写 `parameters`/`returns` 结构，只有标量参数可省略。`principal_resolver` 从框架登录会话（`$request->native`）解析员工、租户和角色，`security_bridge` 用已验签身份恢复业务用户（Laravel 可用 `Auth::onceUsingId`），然后 `return $next()`；业务方法内仍校验数据权限与事务。业务 HTTP API 使用平台 OpenAPI 导入。业务退出调用 `$reach->revokeBusinessSession()`，失败重试由宿主负责。

浏览器继续使用平台的 Embed Chat 和 Page Bridge，token 从本系统 `/reachai/embed/token` 获取；Laravel 的换票接口挂在 `web` 中间件组下并由 SDK 校验 Origin 白名单。平台项目级自检不能证明回调或 Embed 会话来自 PHP 实例，应分别验证签名、角色、跨用户数据隔离、业务读写和真实浏览器页面行为。详细 API 以 SDK 包内 README 为准。
