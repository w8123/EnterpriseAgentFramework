# ReachAI 一条命令安装（Windows，基于 Docker Desktop）。
#
#   irm https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy/install.ps1 | iex
#
# 用 irm | iex 运行时不能带参数，可以先设环境变量再运行，例如：
#   $env:REACHAI_MODE = 'server'; $env:REACHAI_PORT = '8080'; irm .../install.ps1 | iex
# 下载到本地运行时可以直接带参数：
#   .\install.ps1 -Mode server -Port 8080 -Url https://reachai.example.com -WithMilvus
#
# 装好后安装目录里有 reachai.ps1，用来管理：
#   .\reachai.ps1 status | doctor | logs [服务] | restart [服务] | stop | start | backup | upgrade
#   .\reachai.ps1 uninstall [-KeepData | -Purge] [-Yes]   卸载；不带参数时会问要不要保留数据
#
# 重复执行是安全的：已经装过时保留 reachai.env 和数据，只更新镜像并重启。
# 如果提示“禁止运行脚本”，先执行：Set-ExecutionPolicy -Scope Process Bypass
param(
    [Parameter(Position = 0)][string]$Command = 'install',
    [Parameter(Position = 1)][string]$Name,
    [string]$Mode = $(if ($env:REACHAI_MODE) { $env:REACHAI_MODE } else { 'local' }),
    [string]$Dir = $(if ($env:REACHAI_DIR) { $env:REACHAI_DIR } else { '' }),
    [int]$Port = $(if ($env:REACHAI_PORT) { [int]$env:REACHAI_PORT } else { 8080 }),
    [string]$Url = $env:REACHAI_URL,
    [string]$Version = $(if ($env:REACHAI_VERSION) { $env:REACHAI_VERSION } else { 'dev' }),
    [string]$Registry = $(if ($env:REACHAI_IMAGE_REGISTRY) { $env:REACHAI_IMAGE_REGISTRY } else { 'ghcr.io/w8123' }),
    [switch]$WithMilvus = ($env:REACHAI_WITH_MILVUS -eq 'true'),
    [switch]$NoPull,
    [switch]$Follow,
    [switch]$KeepData,
    [switch]$Purge,
    [switch]$Yes
)
$ErrorActionPreference = 'Stop'
$SourceUrl = if ($env:REACHAI_SOURCE_URL) { $env:REACHAI_SOURCE_URL } else { 'https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy' }
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Say([string]$Text) { Write-Host $Text }
function Step([string]$Text) { Write-Host ''; Write-Host "==> $Text" -ForegroundColor Cyan }
function Warn([string]$Text) { Write-Host $Text -ForegroundColor Yellow }
function Fail([string]$Text) { Write-Host $Text -ForegroundColor Red; exit 1 }

function Random-Secret([int]$Bytes = 16) {
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return ($buffer | ForEach-Object { $_.ToString('x2') }) -join ''
}

# 安装目录：命令行 > 脚本所在目录（已安装的 reachai.ps1）> 默认 %USERPROFILE%\reachai
if (-not $Dir) {
    if ($PSCommandPath -and (Test-Path (Join-Path (Split-Path $PSCommandPath) 'reachai.env'))) { $Dir = Split-Path $PSCommandPath }
    else { $Dir = Join-Path $HOME 'reachai' }
}
$EnvFile = Join-Path $Dir 'reachai.env'

function Get-EnvValue([string]$Key) {
    if (-not (Test-Path $EnvFile)) { return '' }
    foreach ($line in [System.IO.File]::ReadAllLines($EnvFile)) {
        if ($line -match "^$Key=(.*)$") { $value = $Matches[1] }
    }
    return $value
}

function Set-EnvValue([string]$Key, [string]$Value) {
    $lines = [System.Collections.Generic.List[string]]([System.IO.File]::ReadAllLines($EnvFile))
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^$Key=") { $lines[$i] = "$Key=$Value"; $found = $true }
    }
    if (-not $found) { $lines.Add("$Key=$Value") }
    # 不带 BOM 的 UTF-8：docker compose 读带 BOM 的文件会把第一个变量名读错
    [System.IO.File]::WriteAllLines($EnvFile, $lines, $Utf8)
}

function Compose {
    Push-Location $Dir
    try {
        & docker compose --env-file reachai.env @args
        if ($LASTEXITCODE -ne 0) { throw "docker compose $($args -join ' ') 失败" }
    } finally { Pop-Location }
}

function Ask([string]$Prompt, [string]$Default) {
    if ($env:REACHAI_YES -eq 'true') { return $Default }
    $answer = Read-Host "$Prompt [$Default]"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim()
}

function Check-Docker {
    Step '检查 Docker'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Fail '没有找到 Docker。请先安装并打开 Docker Desktop：https://www.docker.com/products/docker-desktop/ ，然后重新运行这条命令'
    }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { Fail 'Docker Desktop 没有在运行。请打开 Docker Desktop，等它显示 Engine running 后重新运行' }
    $composeVersion = (& docker compose version --short 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $composeVersion) { Fail '缺少 docker compose（v2），请升级 Docker Desktop' }
    $parts = $composeVersion.TrimStart('v').Split('.')
    if ([int]$parts[0] -lt 2 -or ([int]$parts[0] -eq 2 -and [int]$parts[1] -lt 24)) {
        Fail "docker compose 版本太旧（$composeVersion），需要 2.24 或更新，请升级 Docker Desktop"
    }
    Say "Docker 已就绪，compose $composeVersion"
}

function Fetch([string]$FileName, [string]$Target) {
    $local = if ($PSCommandPath) { Join-Path (Split-Path $PSCommandPath) $FileName } else { '' }
    if ($local -and (Test-Path $local) -and ((Resolve-Path $local).Path -ne (Resolve-Path -LiteralPath $Target -ErrorAction SilentlyContinue).Path)) {
        Copy-Item $local $Target -Force; return
    }
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri "$SourceUrl/$FileName" -OutFile $Target
    } catch { Fail "下载 $FileName 失败，请检查网络能否访问 $SourceUrl" }
}

function Memory-Mb {
    try { return [int]((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1MB) } catch { return 8192 }
}

function Tune-Memory {
    # Docker Desktop 的内存上限通常是电脑内存的一半，按一半来算
    $mem = [int]((Memory-Mb) / 2)
    if ($mem -lt 3500) { $heap = @(256, 384, 256, 448, 448); Warn "Docker 可用内存约 ${mem}MB，偏小，会按最省内存的方式启动。可以在 Docker Desktop 设置里调大内存。" }
    elseif ($mem -lt 7000) { $heap = @(384, 512, 384, 640, 640) }
    else { $heap = @(512, 768, 512, 1024, 1024) }
    $names = @('MODEL', 'KNOWLEDGE', 'CAPABILITY', 'RUNTIME', 'CONTROL')
    for ($i = 0; $i -lt 5; $i++) {
        $min = if ($i -eq 0 -or $i -eq 2) { 96 } else { 128 }
        Set-EnvValue "REACHAI_JAVA_OPTS_$($names[$i])" "-Xms${min}m -Xmx$($heap[$i])m -XX:+UseSerialGC"
    }
}

function Install-ReachAI {
    Say "ReachAI 安装（$(if ($Mode -eq 'server') { '服务器模式' } else { '本机试用模式' })）"
    Check-Docker
    Step "准备安装目录 $Dir"
    New-Item -ItemType Directory -Force -Path (Join-Path $Dir 'data'), (Join-Path $Dir 'backups') | Out-Null
    Fetch 'docker-compose.yml' (Join-Path $Dir 'docker-compose.yml')
    Fetch 'milvus-embedEtcd.yaml' (Join-Path $Dir 'milvus-embedEtcd.yaml')
    Fetch 'install.ps1' (Join-Path $Dir 'reachai.ps1')
    $firstInstall = -not (Test-Path $EnvFile)
    if ($firstInstall) {
        Fetch 'reachai.env.example' $EnvFile
        Step '生成配置'
        if ($Mode -eq 'server') {
            $Port = [int](Ask '访问端口' "$Port")
            if (-not $Url) { $Url = Ask '浏览器访问 ReachAI 的地址（有域名就填域名）' "http://$($env:COMPUTERNAME):$Port" }
        }
        if (-not $Url) { $Url = "http://localhost:$Port" }
        Set-EnvValue 'REACHAI_BOOTSTRAP_ADMIN_PASSWORD' (Random-Secret 8)
        Set-EnvValue 'AI_MYSQL_PASSWORD' (Random-Secret)
        Set-EnvValue 'REACHAI_MYSQL_ROOT_PASSWORD' (Random-Secret)
        Set-EnvValue 'REACHAI_PUBLIC_URL' $Url.TrimEnd('/')
        Set-EnvValue 'REACHAI_PORT' "$Port"
        Set-EnvValue 'REACHAI_VERSION' $Version
        Set-EnvValue 'REACHAI_IMAGE_REGISTRY' $Registry.TrimEnd('/')
        Set-EnvValue 'REACHAI_INSTALL_MODE' $Mode
        Set-EnvValue 'REACHAI_BIND' $(if ($Mode -eq 'server') { '0.0.0.0' } else { '127.0.0.1' })
        if ($Mode -ne 'server') { Set-EnvValue 'RUNTIME_HTTP_EGRESS_ALLOW_PRIVATE' 'true' }
        Set-EnvValue 'COMPOSE_PROFILES' $(if ($WithMilvus) { 'mysql,milvus' } else { 'mysql' })
        Tune-Memory
    } else {
        Say '已经装过，保留现有配置 reachai.env 和数据'
    }
    if (-not $NoPull) {
        Step '下载镜像（第一次需要几分钟）'
        try { Compose pull --quiet } catch { Fail "镜像下载失败：检查网络能否访问 $(Get-EnvValue 'REACHAI_IMAGE_REGISTRY')，或用 -Registry 换一个镜像地址" }
    }
    Step '启动 ReachAI（第一次启动会自动建表，约 1～2 分钟）'
    try { Compose up -d --remove-orphans --wait --wait-timeout 600 }
    catch { Compose ps; Fail "有服务没有按时就绪，运行 $Dir\reachai.ps1 doctor 查看原因" }

    Write-Host ''
    Write-Host 'ReachAI 已经装好' -ForegroundColor Green
    Say "  访问地址：$(Get-EnvValue 'REACHAI_PUBLIC_URL')"
    Say '  管理员：  admin'
    if ($firstInstall) { Say "  初始密码：$(Get-EnvValue 'REACHAI_BOOTSTRAP_ADMIN_PASSWORD')   （也保存在 $EnvFile，建议登录后修改）" }
    else { Say '  密码：    沿用原来的密码' }
    Say ''
    Say '下一步：登录后打开「开始使用」，按提示接入模型、接入业务系统、发布给员工。'
    Say ''
    Say "常用命令（在 $Dir 里运行）："
    Say '  .\reachai.ps1 status     查看各服务状态'
    Say '  .\reachai.ps1 doctor     体检，出问题时先跑它'
    Say '  .\reachai.ps1 logs       查看日志'
    Say '  .\reachai.ps1 backup     备份数据库和密钥'
    Say '  .\reachai.ps1 upgrade    升级到最新版本'
    Say '  .\reachai.ps1 uninstall  卸载（会问要不要保留数据）'
}

function Service-Name([string]$Short) {
    if (-not $Short) { return @() }
    if (@('model', 'knowledge', 'capability', 'runtime', 'control', 'web') -contains $Short) { return @("reachai-$Short") }
    return @($Short)
}

function Backup-ReachAI {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $work = Join-Path $Dir "backups\tmp-$stamp"
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    Say '备份数据库...'
    if ((Get-EnvValue 'COMPOSE_PROFILES') -match '(^|,)mysql(,|$)') {
        Push-Location $Dir
        try {
            # 先在容器里导出再拷出来，避免 PowerShell 管道改动文件编码
            & docker compose --env-file reachai.env exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers --default-character-set=utf8mb4 --databases "$MYSQL_DATABASE" > /tmp/reach_ai.sql'
            if ($LASTEXITCODE -ne 0) { Fail '数据库备份失败：确认 mysql 服务正在运行（.\reachai.ps1 status）' }
            & docker compose --env-file reachai.env cp mysql:/tmp/reach_ai.sql (Join-Path $work 'reach_ai.sql') | Out-Null
            & docker compose --env-file reachai.env exec -T mysql rm -f /tmp/reach_ai.sql
        } finally { Pop-Location }
    } else {
        Say '  用的是你自己的 MySQL，请用你平时的方式备份数据库。这里只备份密钥和配置。'
    }
    Copy-Item $EnvFile $work
    $secrets = Join-Path $Dir 'data\secrets.properties'
    if (Test-Path $secrets) { Copy-Item $secrets $work }
    $archive = Join-Path $Dir "backups\reachai-$stamp.zip"
    Compress-Archive -Path (Join-Path $work '*') -DestinationPath $archive
    Remove-Item -Recurse -Force $work
    Say "已备份到 $archive"
    Say '里面有数据库密码和密钥，请放到安全的地方。'
}

function Doctor-ReachAI {
    Say "ReachAI 体检（$Dir）"
    $problems = 0
    & docker info *> $null
    if ($LASTEXITCODE -eq 0) { Say '  ✓ Docker 正在运行' } else { Say '  ✗ Docker Desktop 没有运行：请打开它'; $problems++ }
    Push-Location $Dir
    try { $rows = & docker compose --env-file reachai.env ps -a --format '{{.Service}}|{{.State}}|{{.Health}}' } finally { Pop-Location }
    foreach ($row in $rows) {
        $service, $state, $health = $row.Split('|')
        if ($state -ne 'running') { Say "  ✗ $service 没有运行（$state）：运行 .\reachai.ps1 logs $($service -replace '^reachai-', '')"; $problems++ }
        elseif ($health -eq 'unhealthy') { Say "  ✗ $service 健康检查失败：运行 .\reachai.ps1 logs $($service -replace '^reachai-', '')"; $problems++ }
        elseif ($health -eq 'starting') { Say "  ! $service 正在启动，稍等一两分钟" }
        else { Say "  ✓ $service 正常" }
    }
    $port = Get-EnvValue 'REACHAI_PORT'
    try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "http://127.0.0.1:$port/" | Out-Null; Say "  ✓ http://127.0.0.1:$port 可以打开" }
    catch { Say "  ✗ http://127.0.0.1:$port 打不开：检查 reachai-web 是否运行、端口是否被占用"; $problems++ }
    if ($problems -eq 0) { Write-Host '没有发现问题。更详细的检查在管理台「开始使用 → 系统体检」。' -ForegroundColor Green }
    else { Write-Host "发现 $problems 个问题，按上面的提示处理。" -ForegroundColor Red }
}

function Uninstall-ReachAI {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { Fail 'Docker Desktop 没有运行，没法删除容器。请先打开它' }
    if ($Yes -and -not ($KeepData -or $Purge)) { Fail '-Yes 需要和 -KeepData 或 -Purge 一起用，说清楚要不要保留数据' }
    $project = Get-EnvValue 'REACHAI_PROJECT_NAME'; if (-not $project) { $project = 'reachai' }
    $registry = Get-EnvValue 'REACHAI_IMAGE_REGISTRY'; if (-not $registry) { $registry = 'ghcr.io/w8123' }
    $dataPaths = @('data', 'mysql', 'milvus', 'redis', 'backups') | ForEach-Object { Join-Path $Dir $_ } | Where-Object { Test-Path $_ }
    Say "卸载 ReachAI（$Dir）"
    Say '会删除：所有 ReachAI 容器、网络和镜像'
    $purgeAll = [bool]$Purge
    if (-not ($KeepData -or $Purge)) {
        Say '你的数据（数据库、密钥、上传的文件、备份）在：'
        $dataPaths | ForEach-Object { Say "  $_" }
        $answer = Read-Host '要保留这些数据吗？保留的话以后重新运行 install.ps1 可以接着用。(y=保留 / n=全部删除) [y]'
        $purgeAll = @('n', 'N', 'no', '否') -contains $answer
    }
    if ($purgeAll -and -not $Yes) {
        Warn "还会删除整个安装目录 $Dir，包括数据库和密钥，无法恢复。"
        if ((Read-Host '确认全部删除请输入 delete') -ne 'delete') { Say '已取消，什么都没删'; return }
        if ((Test-Path $EnvFile) -and ((Get-EnvValue 'COMPOSE_PROFILES') -match '(^|,)mysql(,|$)')) {
            if (@('n', 'N', 'no', '否') -notcontains (Read-Host '删除前先导出一份备份到上一级目录吗？(y/n) [y]')) {
                Backup-ReachAI
                $latest = Get-ChildItem (Join-Path $Dir 'backups') -Filter 'reachai-*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
                Move-Item $latest.FullName (Split-Path $Dir)
                Say "已导出最后一份备份：$(Join-Path (Split-Path $Dir) $latest.Name)（里面有密码和密钥，不需要了就删掉）"
            }
        }
    }
    if ((Test-Path $EnvFile) -and (Test-Path (Join-Path $Dir 'docker-compose.yml'))) {
        $env:COMPOSE_PROFILES = 'mysql,milvus,redis'
        Push-Location $Dir
        try { & docker compose --env-file reachai.env down --remove-orphans --volumes --timeout 30 *> $null } finally { Pop-Location; Remove-Item Env:COMPOSE_PROFILES }
    }
    # 兜底：按项目标签清理
    $ids = & docker ps -aq --filter "label=com.docker.compose.project=$project"; if ($ids) { & docker rm -f $ids *> $null }
    $ids = & docker network ls -q --filter "label=com.docker.compose.project=$project"; if ($ids) { & docker network rm $ids *> $null }
    Say '  ✓ 已停止并删除所有容器和网络'
    $ids = @(& docker image ls -q "$registry/reachai-server") + @(& docker image ls -q "$registry/reachai-web") | Where-Object { $_ } | Sort-Object -Unique
    if ($ids) { & docker image rm -f $ids *> $null; Say '  ✓ 已删除 ReachAI 镜像' }
    if ($purgeAll) {
        $profiles = ",$(Get-EnvValue 'COMPOSE_PROFILES'),"
        foreach ($pair in @(@('mysql', 'mysql:8.0'), @('milvus', 'milvusdb/milvus:v2.4.4'), @('redis', 'redis:7-alpine'))) {
            if ($profiles -notlike "*,$($pair[0]),*") { continue }
            & docker image inspect $pair[1] *> $null
            if ($LASTEXITCODE -ne 0) { continue }
            & docker image rm $pair[1] *> $null
            if ($LASTEXITCODE -eq 0) { Say "  ✓ 已删除镜像 $($pair[1])" } else { Say "  ! 镜像 $($pair[1]) 还有别的容器在用，保留" }
        }
        # 脚本自己也在安装目录里，先离开再删
        Set-Location (Split-Path $Dir)
        Remove-Item -Recurse -Force $Dir
        Write-Host 'ReachAI 已经卸载干净。' -ForegroundColor Green
    } else {
        Write-Host "ReachAI 已经卸载，数据保留在 $Dir。" -ForegroundColor Green
        Say "以后想接着用：重新运行 install.ps1 -Dir $Dir；想彻底删掉：$Dir\reachai.ps1 uninstall -Purge"
    }
    Say 'Docker Desktop 本身没有卸载（别的程序可能也在用）。'
}

switch ($Command) {
    'install' { Install-ReachAI }
    'status' { Compose ps }
    'doctor' { Doctor-ReachAI }
    'logs' { $extra = @('--tail', '200'); if ($Follow) { $extra += '--follow' }; Compose logs @extra @(Service-Name $Name) }
    'restart' { Compose up -d --force-recreate --wait @(Service-Name $Name) }
    'stop' { Compose stop }
    'start' { Compose up -d --wait }
    'backup' { Backup-ReachAI }
    'upgrade' {
        if ($Name) { Set-EnvValue 'REACHAI_VERSION' $Name }
        Backup-ReachAI
        Compose pull --quiet
        Compose up -d --remove-orphans --wait --wait-timeout 600
        Doctor-ReachAI
    }
    'uninstall' { Uninstall-ReachAI }
    default { Fail "不认识的命令：$Command（可用：install status doctor logs restart stop start backup upgrade uninstall）" }
}
