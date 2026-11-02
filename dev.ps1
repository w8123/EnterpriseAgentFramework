# ReachAI 本地开发一条命令启动（Windows PowerShell 5.1 / PowerShell 7）。
#
#   .\dev.ps1 up [-Build] [-NoFront]   启动 MySQL（Docker）、5 个服务和管理端
#   .\dev.ps1 down [-All]              停止服务；-All 连 Docker 里的 MySQL 一起停
#   .\dev.ps1 status                   查看各服务状态
#   .\dev.ps1 logs <model|knowledge|capability|runtime|control|front>
#
# 第一次运行会从 reachai.env.example 生成 reachai.env，并随机生成数据库密码。
# 空库自动建表、密钥自动生成，都由服务启动时自己完成，这个脚本只负责按顺序拉起进程。
# 如果提示“禁止运行脚本”，先执行：Set-ExecutionPolicy -Scope Process Bypass
param(
    [Parameter(Position = 0)][string]$Command = 'up',
    [Parameter(Position = 1)][string]$Name,
    [switch]$Build,
    [switch]$NoFront,
    [switch]$All
)
$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
$Config = Join-Path $Root 'reachai.env'
$State = Join-Path $Root '.reachai'
$Logs = Join-Path $State 'logs'
$Pids = Join-Path $State 'run'
$Services = @('model', 'knowledge', 'capability', 'runtime', 'control')
$Ports = @{ model = 18601; knowledge = 18602; control = 18603; runtime = 18604; capability = 18605; front = 5200 }

function Say([string]$Text) { Write-Host $Text }
function Fail([string]$Text) { Write-Host $Text -ForegroundColor Red; exit 1 }

function Health-Url([string]$Service) {
    if ($Service -eq 'knowledge') { return 'http://127.0.0.1:18602/ai/actuator/health' }
    return "http://127.0.0.1:$($Ports[$Service])/actuator/health"
}

function Random-Secret {
    $bytes = New-Object byte[] 16
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
}

function Read-Config {
    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Config)) {
        $text = $line.Trim()
        if ($text -eq '' -or $text.StartsWith('#')) { continue }
        $index = $text.IndexOf('=')
        if ($index -le 0) { continue }
        $values[$text.Substring(0, $index).Trim()] = $text.Substring($index + 1).Trim().Trim('"', "'")
    }
    return $values
}

function Set-ConfigValue([string]$Key, [string]$Value) {
    $lines = [System.Collections.Generic.List[string]]([System.IO.File]::ReadAllLines($Config))
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^$Key=") { $lines[$i] = "$Key=$Value"; $found = $true }
    }
    if (-not $found) { $lines.Add("$Key=$Value") }
    # 不带 BOM 的 UTF-8，Docker Compose 和服务都能直接读。
    [System.IO.File]::WriteAllLines($Config, $lines, (New-Object System.Text.UTF8Encoding $false))
}

function Uses-BundledMysql($Values) {
    $hostName = if ($Values['AI_MYSQL_HOST']) { $Values['AI_MYSQL_HOST'] } else { 'localhost' }
    return $hostName -eq 'localhost' -or $hostName -eq '127.0.0.1'
}

function Ensure-Config {
    if (-not (Test-Path $Config)) {
        Copy-Item (Join-Path $Root 'reachai.env.example') $Config
        Say '已生成配置文件 reachai.env'
    }
    $values = Read-Config
    if (-not $values['AI_MYSQL_PASSWORD'] -and (Uses-BundledMysql $values)) {
        Set-ConfigValue 'AI_MYSQL_PASSWORD' (Random-Secret)
        Set-ConfigValue 'REACHAI_MYSQL_ROOT_PASSWORD' (Random-Secret)
        Say '已为本机 MySQL 生成数据库密码（写在 reachai.env）'
        $values = Read-Config
    }
    if (-not $values['AI_MYSQL_PASSWORD']) { Fail '请在 reachai.env 里填写 AI_MYSQL_PASSWORD（你自己的 MySQL 的密码）' }
    $dataHome = if ($values['REACHAI_HOME']) { $values['REACHAI_HOME'] } elseif ($env:REACHAI_HOME) { $env:REACHAI_HOME } else { $State }
    $env:REACHAI_HOME = $dataHome
    $env:REACHAI_CONFIG_FILE = $Config
    return $values
}

function Port-Open([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait(500) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Compose {
    & docker compose --env-file $Config -f (Join-Path $Root 'deploy/docker-compose.infra.yml') @args
}

function Ensure-Mysql($Values) {
    $port = if ($Values['AI_MYSQL_PORT']) { [int]$Values['AI_MYSQL_PORT'] } else { 3306 }
    if (-not (Uses-BundledMysql $Values)) { Say "使用你配置的 MySQL：$($Values['AI_MYSQL_HOST']):$port"; return }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        if (Port-Open $port) { Say "本机 $port 端口已有 MySQL，直接使用"; return }
        Fail '没有找到 Docker。请安装 Docker Desktop，或在 reachai.env 里填写你自己的 MySQL 地址和密码'
    }
    $running = (Compose ps --status running mysql 2>$null) -match 'mysql'
    if ((Port-Open $port) -and -not $running) {
        $database = if ($Values['AI_MYSQL_DATABASE']) { $Values['AI_MYSQL_DATABASE'] } else { 'reach_ai' }
        Say "本机 $port 端口已有 MySQL，直接使用（数据库 $database 需已存在）"
        return
    }
    Say '启动 MySQL（Docker）...'
    Compose up -d mysql | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'MySQL 容器启动失败，请确认 Docker Desktop 已运行' }
    $waited = 0
    while ($true) {
        Compose exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot "-p$($Values['REACHAI_MYSQL_ROOT_PASSWORD'])" --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        $waited += 2
        if ($waited -gt 120) { Fail 'MySQL 两分钟内没有就绪，请查看：docker compose -f deploy/docker-compose.infra.yml logs mysql' }
        Start-Sleep -Seconds 2
    }
    Say 'MySQL 已就绪'
}

function Jar-Of([string]$Service) { Join-Path $Root "reachai-$Service-service/target/reachai-$Service-service-1.0.0-SNAPSHOT.jar" }

function Ensure-Build {
    $missing = $Services | Where-Object { -not (Test-Path (Jar-Of $_)) }
    if ($missing -or $Build) {
        if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Fail '没有找到 Maven（mvn），无法编译。请安装 JDK 17 和 Maven' }
        Say '编译后端（第一次需要几分钟）...'
        Push-Location $Root
        try { & mvn -q -B -DskipTests install } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { Fail '编译失败，请查看上面的 Maven 输出' }
    }
}

function Pid-File([string]$Service) { Join-Path $Pids "$Service.pid" }

function Running([string]$Service) {
    $file = Pid-File $Service
    if (-not (Test-Path $file)) { return $false }
    return [bool](Get-Process -Id ([int](Get-Content $file)) -ErrorAction SilentlyContinue)
}

function Start-ReachAiService([string]$Service) {
    if (Running $Service) { Say "  $Service 已在运行"; return }
    if (Port-Open $Ports[$Service]) { Fail "端口 $($Ports[$Service]) 已被占用，$Service 无法启动。请先停掉占用它的程序" }
    $options = @(if ($env:REACHAI_JAVA_OPTS) { $env:REACHAI_JAVA_OPTS -split '\s+' } else { '-Xmx512m' })
    $process = Start-Process java -ArgumentList ($options + @('-jar', "`"$(Jar-Of $Service)`"")) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $Logs "$Service.log") -RedirectStandardError (Join-Path $Logs "$Service.err.log")
    Set-Content (Pid-File $Service) $process.Id
}

function Wait-Healthy([string]$Service) {
    $waited = 0
    while ($true) {
        try { Invoke-WebRequest (Health-Url $Service) -UseBasicParsing -TimeoutSec 2 | Out-Null; break } catch { }
        if (-not (Running $Service)) {
            Say "$Service 启动失败，日志最后几行："
            Get-Content (Join-Path $Logs "$Service.log") -Tail 30
            exit 1
        }
        $waited += 2
        if ($waited -gt 240) { Fail "$Service 4 分钟内没有就绪，请查看 .reachai\logs\$Service.log" }
        Start-Sleep -Seconds 2
    }
    Say "  $Service 已就绪（端口 $($Ports[$Service])）"
}

function Start-Front {
    if ($NoFront) { return }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Say '没有找到 Node.js，跳过管理端。安装 Node.js 20+ 后重新运行即可'; return }
    if (Running 'front') { Say '  管理端已在运行'; return }
    $front = Join-Path $Root 'ai-admin-front'
    if (-not (Test-Path (Join-Path $front 'node_modules'))) {
        Say '安装管理端依赖（第一次需要一两分钟）...'
        Push-Location $front
        try { & npm ci --no-audit --no-fund *> (Join-Path $Logs 'front-install.log') } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { Fail 'npm ci 失败，请查看 .reachai\logs\front-install.log' }
    }
    # 直接运行 vite，记下的就是它自己的进程号，down 时能停干净。
    $process = Start-Process node -ArgumentList @('node_modules/vite/bin/vite.js', '--host', '127.0.0.1') -WorkingDirectory $front `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Logs 'front.log') -RedirectStandardError (Join-Path $Logs 'front.err.log')
    Set-Content (Pid-File 'front') $process.Id
    $waited = 0
    while (-not (Port-Open $Ports['front'])) {
        if (-not (Running 'front')) { Say '管理端启动失败，日志最后几行：'; Get-Content (Join-Path $Logs 'front.err.log') -Tail 30; exit 1 }
        $waited += 1
        if ($waited -gt 60) { Fail '管理端一分钟内没有就绪，请查看 .reachai\logs\front.log' }
        Start-Sleep -Seconds 1
    }
    Say "  管理端已就绪（端口 $($Ports['front'])）"
}

function Up {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Fail '没有找到 Java。请安装 JDK 17' }
    New-Item -ItemType Directory -Force $Logs, $Pids | Out-Null
    $values = Ensure-Config
    Ensure-Mysql $values
    Ensure-Build
    Say '启动服务...'
    foreach ($service in $Services) { Start-ReachAiService $service }
    foreach ($service in $Services) { Wait-Healthy $service }
    Start-Front
    Say ''
    Say 'ReachAI 已启动：http://localhost:5200  账号 admin，密码见 reachai.env 的 REACHAI_BOOTSTRAP_ADMIN_PASSWORD（默认 admin123）'
    Say '日志在 .reachai\logs，停止用 .\dev.ps1 down'
}

function Down {
    foreach ($service in @('front') + $Services) {
        if (Running $service) {
            Stop-Process -Id ([int](Get-Content (Pid-File $service))) -Force -ErrorAction SilentlyContinue
            Say "已停止 $service"
        }
        Remove-Item (Pid-File $service) -ErrorAction SilentlyContinue
    }
    if ($All -and (Test-Path $Config)) { Compose stop mysql 2>$null | Out-Null; Say '已停止 MySQL' }
}

function Status {
    foreach ($service in $Services + @('front')) {
        $state = '未运行'
        if (Running $service) {
            $state = '启动中或异常'
            if ($service -eq 'front') { $state = '运行中' }
            else { try { Invoke-WebRequest (Health-Url $service) -UseBasicParsing -TimeoutSec 2 | Out-Null; $state = '运行中' } catch { } }
        }
        Say ('  {0,-11} {1,-6} {2}' -f $service, $Ports[$service], $state)
    }
}

switch ($Command) {
    'up' { Up }
    'down' { Down }
    'status' { Status }
    'logs' {
        if (-not $Name) { Fail '用法：.\dev.ps1 logs <服务名>' }
        Get-Content (Join-Path $Logs "$Name.log") -Tail 200 -Wait
    }
    default { Get-Content $PSCommandPath -TotalCount 9 | Select-Object -Skip 1; exit 1 }
}
