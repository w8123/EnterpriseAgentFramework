# 日常运维：升级、备份、恢复、卸载

> 适合：平台管理员、运维。下面的命令适用于用 [一条命令安装](install.md) 的 ReachAI。

## 常用命令

| 要做的事 | 命令 |
| --- | --- |
| 看各服务状态 | `reachai status` |
| 体检（出问题先跑它） | `reachai doctor` |
| 看日志 | `reachai logs control`，加 `-f` 持续看；服务名：model knowledge capability runtime control web mysql milvus |
| 重启全部 / 某个服务 | `reachai restart` / `reachai restart runtime` |
| 改了 reachai.env | `reachai restart`（会重新创建容器，让新配置生效） |
| 停止 / 启动 | `reachai stop` / `reachai start` |

日志由 Docker 自动轮转（每个服务最多 100MB），不用手动清理。

Windows 在安装目录里用 `.\reachai.ps1`，子命令相同。

## 备份

**必须一起备份的两样东西：数据库和 `data/secrets.properties`。** 密钥文件丢了，已保存的模型 API Key、Workflow 凭据就解不开了。

```bash
reachai backup         # 生成 backups/reachai-时间.tar.gz，自动保留最近 10 份
```

- 备份包里有数据库导出、密钥文件、`reachai.env` 和知识库原件，权限是 600。请再拷一份到别的机器。
- 用自己的 MySQL（`--external-db`）时，`reachai backup` 只备份密钥和配置，数据库请用你们平时的方式备份。
- 建议每天定时备份：`crontab -e` 加一行 `0 3 * * * /usr/local/bin/reachai backup >/dev/null 2>&1`。

## 恢复

```bash
reachai restore backups/reachai-20261028-140334.tar.gz
```

恢复会用备份里的数据库、密钥和配置覆盖当前的，完成后自动重启。

## 升级

```bash
reachai upgrade            # 跟随当前版本标签（默认 dev）拉最新镜像
reachai upgrade v0.2.0     # 升到指定版本
```

升级会自动：备份 → 下载新镜像 → 重启 → 体检。

- 新版本如果带数据库升级脚本，发布说明里会写明，需要按 [SQL 说明](../../sql/README.md) 执行（执行前已经有备份）。
- 升级后有问题：用 `reachai restore` 回到升级前的备份，并在 `reachai.env` 把 `REACHAI_VERSION` 改回原来的版本，再 `reachai restart`。

## 打开可选功能

改 `reachai.env` 后运行 `reachai restart` 生效。

### 开启自动化

管理人员的「自动化中心」默认只能保存草稿、不会按计划运行。要打开，在 `reachai.env` 里加一行：

```bash
REACHAI_AUTOMATION_ENABLED=true
```

`reachai restart` 后，自动化中心顶部显示「执行引擎 就绪」即可。用法见 [让 AI 按计划自动办事](../manager/automations.md)。

### 开启知识库向量检索（Milvus）

把 `reachai.env` 的 `COMPOSE_PROFILES` 改成 `mysql,milvus`，再 `reachai start`。多占约 1.5G 内存。

### 解析 PDF、Word 等文档（Docling）

不开时知识库只能导入 txt、md、csv。部署好 Docling 服务后，在 `reachai.env` 里配置：

```bash
REACHAI_DOCLING_ENABLED=true
REACHAI_DOCLING_BASE_URL=http://docling:5001
```

### 访问内网里的业务系统

业务系统在内网其他机器（如 192.168.x.x）时，在 `reachai.env` 里打开（本机模式默认已打开）：

```bash
RUNTIME_HTTP_EGRESS_ALLOW_PRIVATE=true
```

### 调整内存

每个服务的 Java 内存由 `REACHAI_JAVA_OPTS_*` 控制，安装时按机器内存自动设置。服务因内存不足被杀时，可以调小 `-Xmx`，或给机器加内存、开 swap。

## 卸载

```bash
reachai uninstall
```

会先问你要不要保留数据：

- **保留数据**（直接回车）：删除容器、网络、ReachAI 镜像和 `reachai` 命令，数据库、密钥、上传的文件和备份留在安装目录。以后重新运行 `install.sh --dir <安装目录>` 就能接着用，管理员密码不变。
- **全部删除**（回答 n，再输入 `delete` 确认）：再删掉数据和整个安装目录，以及只给 ReachAI 用的 MySQL / Milvus / Redis 镜像（别的容器还在用的会保留）。删除前会问要不要先导出一份备份，导出的文件放在安装目录的上一级，例如 `/opt/reachai-20261030-003020.tar.gz`。

写在脚本里、不想回答问题时：

```bash
reachai uninstall --keep-data --yes   # 保留数据
reachai uninstall --purge --yes       # 全部删除，不导出备份；要备份就再加 --backup
```

找不到 `reachai` 命令时，用安装脚本卸载，效果一样：`bash install.sh --uninstall`（装在别的目录时加 `--dir <安装目录>`）。Windows：在安装目录里运行 `.\reachai.ps1 uninstall`，可以加 `-KeepData` 或 `-Purge`，以及 `-Yes`。

补充说明：

- MySQL、Milvus 在容器里写出的文件属于容器用户，卸载会借一个容器把它们删干净，不需要 sudo。
- 用的是自己的 MySQL 时，卸载不会碰你的数据库，需要的话自己删掉 `reach_ai` 库。
- 只删除 ReachAI 自己装的东西。Docker 本身不会卸载；ReachAI 也不改系统环境变量和 shell 配置文件。

## 下一步

- [常见问题和排查](troubleshooting.md)
