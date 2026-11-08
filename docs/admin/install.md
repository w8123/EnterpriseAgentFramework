# 安装 ReachAI

> 适合：负责安装和维护 ReachAI 的运维、开发同学。读完你能用一条命令在服务器、自己电脑或 Windows 上装好 ReachAI。
>
> 只想先看看效果？用 [在自己电脑上体验 ReachAI](../start/try-in-10-minutes.md)，它自带一套接好的芋道 ERP。

## 做完你会得到什么

一套能用的 ReachAI：浏览器打开访问地址，用 `admin` 和初始密码登录管理台。之后按 [第一次登录](first-login.md) 接模型、接业务系统。

## 开始前你需要

| 项目 | 最低 | 推荐 |
| --- | --- | --- |
| CPU / 内存 | 2 核 4G（不开 Milvus） | 4 核 8G（开 Milvus） |
| 磁盘 | 20G 可用 | 50G 以上 |
| 系统 | 能装 Docker 的 Linux（x86_64 / arm64）、macOS、Windows 10/11 | Ubuntu 22.04 / Debian 12 / CentOS Stream 9 |
| 软件 | Docker 24+，docker compose 2.24+ | — |

- 服务器只需要对外开放一个端口（默认 8080）；配了反向代理就只开 80 / 443。
- 机器要能访问镜像仓库 `ghcr.io`。国内服务器访问慢时，见 [常见问题](troubleshooting.md) 里的镜像加速。
- 要让员工用 Codex / Claude Code 连 ReachAI，最好准备一个域名和 HTTPS 证书，见下文「配域名和 HTTPS」。

## 选一种安装方式

几种方式装出来的是同一套东西，都只需要一条命令。

### 方式一：Linux 服务器（给团队用）

```bash
curl -fsSL https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy/install.sh | sudo bash -s -- --server
```

安装过程会问两个问题：访问端口、浏览器访问地址（有域名就填域名）。没有 Docker 时会提示自动安装。最后屏幕上会打印访问地址、管理员账号 `admin` 和随机生成的初始密码。**把初始密码记下来。**

常用参数（加在 `--server` 后面）：

| 参数 | 作用 |
| --- | --- |
| `--url https://reachai.example.com` | 直接指定访问地址，不再提问 |
| `--port 8080` | 访问端口 |
| `--with-milvus` | 同时装 Milvus，知识库才能做向量检索（多占约 1.5G 内存） |
| `--external-db` | 用你自己的 MySQL（库要提前建好，空库即可） |
| `--version v0.1.0` | 固定镜像版本，默认 `dev` 跟随最新开发版 |
| `--registry <地址>` | 换镜像仓库（国内加速） |
| `--dir /data/reachai` | 换安装目录 |
| `--yes` | 不提问，全部用默认值 |

### 方式二：自己电脑试用（Linux / macOS）

```bash
curl -fsSL https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy/install.sh | bash
```

本机模式只监听 `127.0.0.1`，打开 http://localhost:8080 。和服务器模式的区别只在默认配置：ReachAI 可以访问本机和内网里的业务系统（业务系统在本机时，在 ReachAI 里填 `http://host.docker.internal:端口`）。

macOS 需要先装好并打开 [Docker Desktop](https://www.docker.com/products/docker-desktop/)。

### 方式三：Windows（Docker Desktop）

先装好并打开 Docker Desktop（需要 WSL2），然后在 PowerShell 里运行：

```powershell
irm https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy/install.ps1 | iex
```

装在 `%USERPROFILE%\reachai`。要用服务器模式或带参数，先下载再运行：

```powershell
irm https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy/install.ps1 -OutFile install.ps1
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1 -Mode server -Port 8080 -Url https://reachai.example.com -WithMilvus
```

装好后在安装目录用 `.\reachai.ps1 status | doctor | logs | restart | backup | upgrade` 管理，用法和 Linux 的 `reachai` 命令一样。

### 方式四：已经会用 Docker

```bash
mkdir reachai && cd reachai
base=https://raw.githubusercontent.com/w8123/EnterpriseAgentFramework-dev/dev/deploy
curl -fsSLO $base/docker-compose.yml -fsSLO $base/milvus-embedEtcd.yaml
curl -fsSL $base/reachai.env.example -o reachai.env
vi reachai.env          # 填管理员密码和两个数据库密码
docker compose --env-file reachai.env up -d --wait
```

### 方式五：没有外网，或镜像下载不了

在能上网的机器上导出镜像，拷到服务器上导入，再用 `--no-pull` 安装：

```bash
# 能上网的机器
docker pull ghcr.io/w8123/reachai-server:dev
docker pull ghcr.io/w8123/reachai-web:dev
docker pull mysql:8.0
docker save ghcr.io/w8123/reachai-server:dev ghcr.io/w8123/reachai-web:dev mysql:8.0 | gzip > reachai-images.tar.gz
# 服务器（同时把仓库的 deploy 目录拷过去）
docker load < reachai-images.tar.gz
sudo bash deploy/install.sh --server --no-pull
```

也可以在源码目录里现场构建镜像：`bash deploy/install.sh --build`（第一次约 10 分钟或更久）。

## 装好后目录里有什么

```text
reachai/                 （root 安装在 /opt/reachai，其他用户在 ~/reachai）
├── reachai.env          配置（含自动生成的密码，权限 600）
├── docker-compose.yml   服务编排，一般不用改
├── data/                密钥 secrets.properties、知识库原件、Skill 包
├── mysql/               自带 MySQL 的数据
├── backups/             reachai backup 的备份
└── reachai              运维命令（已链接到 /usr/local/bin/reachai）
```

**`reachai.env` 和 `data/secrets.properties` 很重要**：前者有数据库密码，后者用来加密模型 API Key 等敏感信息。不要贴到聊天里，不要提交到 Git，备份时一定要带上（见 [日常运维](operations.md)）。

## 确认安装成功

```bash
reachai doctor
```

各项都是正常，浏览器能打开访问地址并登录，就装好了。

## 配域名和 HTTPS

员工的 AI 工具要从自己电脑连到 ReachAI，访问地址必须是员工电脑能打开的地址（不能是 localhost），建议用 HTTPS。ReachAI 只需要把整个站点反向代理到 `127.0.0.1:8080`：

1. 在 `reachai.env` 里把 `REACHAI_BIND` 改成 `127.0.0.1`（只让本机的 Nginx 访问），`REACHAI_PUBLIC_URL` 改成 `https://你的域名`。
2. 运行 `reachai restart` 让配置生效。
3. 配 Nginx（宝塔面板：网站 → 添加站点 → 反向代理到 `http://127.0.0.1:8080`，再申请 SSL 证书）：

```nginx
server {
    listen 443 ssl http2;
    server_name reachai.example.com;
    ssl_certificate     /path/fullchain.pem;
    ssl_certificate_key /path/privkey.pem;
    client_max_body_size 100m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # 对话是流式输出，不能缓冲
        proxy_buffering off;
        proxy_read_timeout 600s;
    }
}
```

4. 运行 `reachai doctor`，「访问」一栏应显示 `https://你的域名 可以打开`。

## 让 AI 编程工具帮你部署

让 Codex、Claude Code 帮你部署或排障时，把本页和 [日常运维](operations.md) 发给它，并告诉它下面几条规矩：

1. 以你指定的目标、范围和现网结构为准。本页的安装脚本与 `reachai` 命令适用于它管理的容器环境；已有宝塔托管、systemd 等部署方式时，按现有方式维护，不要求先迁移。
2. 改配置前先读当前文件，核对实际生效方式，保留和任务无关的修改。
3. 改数据库前先备份；容器安装用 `reachai backup`。**不要在已有库上重跑 `sql/initV2.sql`**。
4. 没有你的明确同意，不删除 `data/`、`mysql/` 等数据目录；不把 `reachai.env`、`secrets.properties` 的内容贴到聊天或提交到 Git。
5. 在你已经授权的部署、重启或排障范围内持续推进，不因为某个操作没写在本页就反复确认；超出范围的新操作再问你。
6. 按现网方式看状态和日志：容器环境用 `reachai doctor`、`reachai logs <服务>`；systemd 环境用 `systemctl`、`journalctl` 和应用日志。
7. 完成后验证真实的访问地址能打开、能登录。

## 下一步

- [第一次登录和「开始使用」](first-login.md)
- [日常运维：升级、备份、恢复、卸载](operations.md)
