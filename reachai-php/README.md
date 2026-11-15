# PHP 业务系统接入

`reachai/php-sdk` 1.0.0 支持 PHP 7.4–8.4，只依赖 `ext-curl`、`ext-json`、`ext-openssl`，没有第三方 Composer 依赖。SDK 与 Java/C#/Python 共用 [业务系统 SDK 接入协议](../docs/contracts/sdk-protocol.md)，提供项目注册、心跳、签名同步、显式业务方法、调用身份、Embed Token、业务会话撤销和业务索引。内置三种接法：

| 宿主 | 接法 |
| --- | --- |
| 原生 PHP / 其他框架 | 入口文件里调用 `$reach->handleGlobals()`，心跳用 `vendor/bin/reachai` |
| Laravel 8–13 | 自动发现 `ReachAI\Laravel\ReachAIServiceProvider`，心跳用 `php artisan reachai heartbeat` |
| ThinkPHP 6 / 8 | 自动发现 `ReachAI\ThinkPHP\ReachAIService`，心跳用 `php think reachai heartbeat` |

HTTP API 是独立资产，使用平台的 OpenAPI/curl 导入，不要把普通 PHP 接口伪装成 SDK 能力。

## 安装

从接入清单的 `downloadUrl` 下载 `reachai-php-sdk-1.0.0.zip`，先核对 `integritySha256`。下载地址不是 Packagist 仓库。

```bash
mkdir -p third_party/reachai && mv reachai-php-sdk-1.0.0.zip third_party/reachai/
composer config repositories.reachai artifact ./third_party/reachai
composer require reachai/php-sdk:1.0.0
```

没有 Composer 的项目可以解压后 `require 'reachai-php/autoload.php';`，命令行用 `php reachai-php/bin/reachai`。

## PHP 运行模型

PHP-FPM 每个请求一个进程，没有常驻后台线程，所以 SDK 把生命周期拆成两部分：

- **请求处理**：`/reachai/registry/capabilities/sync`、`/reachai/capabilities/{name}/invoke`、`/reachai/embed/token` 三个接口，每次请求从加密凭据文件读取项目凭据，无状态。
- **注册和心跳**：只在命令行执行。首次 `register` 使用一次性 Enrollment Token；之后用 cron 每分钟执行一次 `heartbeat`，或用 Supervisor/systemd 常驻 `heartbeat --loop`（每 30 秒一次，收到 SIGTERM 时下线）。实例 ID 默认由主机名、BaseUrl 和项目编码算出，cron 多次执行不会产生多个实例。

回调防重放默认用 `FileNonceStore`（同一台服务器的所有 FPM 进程共享，原子创建文件）。多台服务器必须改用共享存储，例如 `PdoNonceStore`（建表 SQL 见类注释）；存储故障时拒绝回调。

## 配置与业务能力（原生 PHP）

```php
// reachai.php：返回配置好的实例，入口文件和命令行共用
use ReachAI\{ReachAI, Options, EncryptedFileCredentialStore, EmbedPrincipal, InvocationContext};
use ReachAI\Http\Request;

$reach = new ReachAI(Options::fromEnvironment(), [
    'credentialStore' => new EncryptedFileCredentialStore(getenv('REACHAI_CREDENTIAL_FILE'), getenv('REACHAI_CREDENTIAL_KEY')),
    // 必须从业务系统已登录的会话取员工，不能信任浏览器提交的身份。
    'principalResolver' => function (Request $request, string $agentId): ?EmbedPrincipal {
        $user = current_business_user();            // 宿主自己的登录态
        return $user ? new EmbedPrincipal((string) $user->id, null, $user->name, $user->roles,
            ['tenantId' => (string) $user->tenantId], hash('sha256', session_id()), time() + 7200) : null;
    },
    // 调用前用已验签身份恢复业务用户并执行业务授权，然后 return $next()。
    'securityBridge' => function (InvocationContext $identity, callable $next) {
        login_as_business_user($identity->externalUserId, $identity->tenantId);
        return $next();
    },
]);

$reach->capability('order.query', [$orders, 'find'], [
    'title' => '查询订单', 'sideEffect' => 'READ', 'businessIdentityRequired' => true,
    'parameters' => ['orderId' => ['type' => 'integer', 'description' => '订单号']],
    'returns' => ['type' => 'object', 'properties' => [
        'orderId' => ['type' => 'integer', 'required' => true],
        'status' => ['type' => 'string', 'enum' => ['NEW', 'PAID']],
    ]],
]);
return $reach;
```

```php
// public/index.php
$reach = require __DIR__ . '/../reachai.php';
if ($reach->handleGlobals()) {
    return;                                       // /reachai/* 已处理
}
// ……宿主原有路由
```

`Options::fromEnvironment()` 读取 `REACHAI_REGISTRY_URL`、`REACHAI_PROJECT_CODE`、`REACHAI_BASE_URL`、`REACHAI_ALLOWED_ORIGINS`（逗号分隔）、`REACHAI_ENROLLMENT_TOKEN` 或 `REACHAI_APP_KEY`/`REACHAI_APP_SECRET` 等变量。`projectCode` 必须已经是平台规范的小写编码。凭据密钥用 `vendor/bin/reachai generate-key` 生成，放在环境变量或密钥管理器里，不能和凭据文件放在一起，也不能提交源码；凭据文件放在 Web 根目录之外，目录只允许运行账号访问。

```bash
vendor/bin/reachai register  --bootstrap=reachai.php   # 首次注册，消费一次性 token
vendor/bin/reachai heartbeat --bootstrap=reachai.php   # cron: * * * * *
vendor/bin/reachai heartbeat --bootstrap=reachai.php --loop   # 或常驻
```

SDK 在发送首次注册前写入 `.enrollment-pending` 标记；响应丢失、进程中断或凭据保存失败后不会自动重放 token。应先到平台核对结果并恢复凭据，确认平台没有消费 token 后再由运维删除标记。

## 能力声明

PHP 数组本身表达不出契约，所以参数和返回值需要显式声明。只有 `int`、`float`、`string`、`bool` 类型的参数可以省略 `parameters` 由反射推断。

- `parameters`：线上参数名 => 结构。支持 `type`（string/integer/number/boolean/array/object）、`required`、`nullable`、`description`、`enum`、`format`（`date`、`date-time`、`uuid` 会校验）、数组的 `items`、对象的 `properties`；`native` 把线上名映射到 PHP 参数名。未声明字段、类型不符、缺少必填项一律 400，不进入业务方法。
- `returns`：返回值结构，或 `'void'`。声明为对象的空数组会按 `{}` 输出。
- `sideEffect`：`READ`、`WRITE`（默认）、`IDEMPOTENT_WRITE`、`IRREVERSIBLE`。
- `requiredRoles`：区分大小写，必须同时 `businessIdentityRequired => true`。SDK 的身份和角色检查不替代行级权限、事务或业务规则。

只同步显式登记的能力；确实没有能力时调用 `$reach->markEmptyInventoryComplete()`。业务方法内用 `ReachAI::currentInvocation()` 取已验签的调用者；`claims` 是令牌原始字段，业务含义由宿主核实。业务方法抛出的异常统一返回 500 `CAPABILITY_EXECUTION_FAILED`，不会泄露异常信息，宿主日志里能看到异常类名。

## Laravel

```bash
php artisan vendor:publish --tag=reachai-config
```

在 `config/reachai.php` 里填 `capabilities`（实现 `ReachAI\CapabilityProvider` 的类）、`principal_resolver`、`security_bridge`（可调用类，经容器创建），其余配置读 `.env`。换票接口挂在 `web` 中间件组下以便读取登录会话，并跳过 CSRF Token 中间件，由 SDK 的 Origin 白名单拦截跨站请求；签名回调和调用接口不经过会话。`$request->native` 是 Laravel 的 `Illuminate\Http\Request`，解析员工可用 `$request->native->user()`。心跳加到调度器：

```php
Schedule::command('reachai heartbeat')->everyMinute()->withoutOverlapping();
```

## ThinkPHP

安装后 `config/reachai.php` 会自动生成，配置项与 Laravel 相同，读 `.env`。换票接口需要会话：在 `app/middleware.php` 启用 `\think\middleware\SessionInit::class`。`$request->native` 是 `think\Request`。心跳用 cron：`* * * * * cd /www/myapp && php think reachai heartbeat`。

## 嵌入和退出

`principalResolver` 的 `attributes['tenantId']`、用户、角色和业务会话都必须来自宿主验证过的会话，不能来自浏览器字段。把业务页面来源写入 `allowedOrigins`，浏览器请求必须带匹配的 `Origin`。业务退出时调用 `$reach->revokeBusinessSession($sessionId)`，只有收到平台确认才算撤销完成，网络失败由宿主重试。浏览器只请求本系统 `/reachai/embed/token` 拿短期 Token，接触不到项目密钥。前端对话组件按[嵌入指南](../docs/developer/embed-chat.md)接入。

## 业务索引

`indexUpsert($indexCode, $record)`、`indexBatch($indexCode, $records)`、`indexDelete($indexCode, $bizId)` 对实际发送的 UTF-8 正文签名。回源方法声明 `businessIndexResolver => true`、`businessIdentityRequired => true`，唯一参数是 `BusinessIndexRequest`，返回 `BusinessIndexResolution`：先查询真实业务记录，用 `BusinessIndexAccessScope::authorizeRow(...)` 校验当前用户对该租户和对象的可见性，再 `BusinessIndexResolution::createAuthorized(...)`。SDK 会把结果与已验签身份、请求对象逐项核对。

## 记录员工操作（主动感知）

打开后，SDK 记录已登录员工在业务系统里调用了哪些接口、搜了什么、有没有失败，上报给 ReachAI，管理者在「AI 使用情况 · 用户操作」里看高频操作和常见问题。只发接口路径模板、搜索词和错误信息（手机号、邮箱、长数字已打码），不发请求和返回内容；只记带 Referer / Origin 的浏览器请求；在响应发出后再上报，失败直接丢弃。

- Laravel：把 `ReachAI\Laravel\RecordActivity` 加到 `web` / `api` 中间件组（放在登录中间件之后），设置 `REACHAI_ACTIVITY_ENABLED=true`。员工默认取 `$request->user()`。
- ThinkPHP：在 `app/middleware.php` 注册 `ReachAI\ThinkPHP\RecordActivity`，设置 `REACHAI_ACTIVITY_ENABLED=true`，并在 `config/reachai.php` 的 `activity_user_resolver` 填一个可调用类，返回 `['id' => ..., 'name' => ...]`。
- 原生 PHP：请求处理完后调用

```php
$activity = $reach->activity();
$activity->recordRequest([
    'method' => $_SERVER['REQUEST_METHOD'], 'route' => '/customer/{id}', 'name' => '查看客户',
    'status' => http_response_code(), 'referer' => $_SERVER['HTTP_REFERER'] ?? '', 'query' => $_GET,
    'userId' => $currentUser->id, 'userName' => $currentUser->name, 'startedAt' => $startedAt,
]);
$activity->flushAtShutdown();
```

返回体是 `{"code": 1002, "msg": "..."}` 这种格式时，`code` 不在 `activity_success_codes`（默认 0、200）里就算失败。ReachAI 里关掉这个业务系统的采集后，SDK 自动停止上报。

## 验证

`php tests/run.php` 运行零依赖单元测试，覆盖共享协议向量、篡改/过期/越权令牌、严格参数绑定、安全桥必须执行一次、回调重放、Embed 来源和会话绑定、一次性注册不重放、加密凭据和业务索引作用域。`tests/e2e/` 里的模拟平台和驱动脚本可以对真实 Laravel/ThinkPHP 应用跑一遍注册、同步、调用和换票。单元测试和模拟平台不代替真实业务系统、平台联调和目标环境验收。
