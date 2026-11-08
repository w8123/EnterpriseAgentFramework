# 平台常见问题和排查

> 适合：平台管理员、运维。按现象找处理方法。业务人员的问题见 [业务人员常见问题](../business/faq.md)。

## 先做这两件事

1. 运行 `reachai doctor`，它会逐项检查服务、配置、访问地址，并告诉你哪里有问题。
2. 管理台「开始使用」页右侧的「系统体检」，看服务和安全项。

再按下面的现象处理。

## 安装和启动

| 现象 | 处理 |
| --- | --- |
| 镜像下载失败 / 很慢 | 国内服务器可以给 Docker 配镜像加速，或安装时加 `--registry <加速地址>`；也可以按 [离线安装](install.md#方式五没有外网或镜像下载不了) 导入 |
| 安装卡在「启动 ReachAI」 | `reachai doctor` 看哪个服务没起来，再 `reachai logs <服务>`；最常见是内存不够 |
| 服务反复重启、doctor 提示被系统杀掉 | 内存不够。关掉 Milvus，或在 `reachai.env` 调小 `REACHAI_JAVA_OPTS_*` 里的 `-Xmx`，或给机器加内存 / 开 swap |
| 浏览器打开是 502 | Control 还在启动（首次约 1 分钟），稍等；一直 502 就看 `reachai logs control` |

## 模型和知识库

| 现象 | 处理 |
| --- | --- |
| 模型测试失败 | 页面会写明原因：Key 不对、余额不足、地址连不上等，按提示到模型中心修改 |
| 知识库提示 Milvus 没连上 | 按 [日常运维](operations.md#开启知识库向量检索milvus) 开启 Milvus；不用知识库检索可以忽略 |
| 只能导入 txt / md / csv | 需要 Docling 解析 PDF、Word，见 [日常运维](operations.md#解析-pdfword-等文档docling) |

## 业务系统和员工

| 现象 | 处理 |
| --- | --- |
| 调用业务系统提示连不上 | 检查「项目管理」里登记的业务系统地址；在本机用 `host.docker.internal`，内网要开 `RUNTIME_HTTP_EGRESS_ALLOW_PRIVATE` |
| 员工的 Codex / Claude Code 连不上 | `REACHAI_PUBLIC_URL` 必须是员工电脑能打开的地址（不能是 localhost），并且建议是 https |
| 员工说「在我的 AI 工具中使用」里看不到系统 | 让管理人员检查 [开通员工使用](../manager/open-to-employees.md) 和 [调用权限](../manager/permissions.md) |
| 业务系统里的助手提示「当前页面无法访问该助手」 | 检查嵌入对话的来源白名单和允许的 Agent，见 [在业务页面嵌入助手](../developer/embed-chat.md) |
| 自动化中心提示「自动运行暂未开启」 | 按 [日常运维](operations.md#开启自动化) 打开 |

## 账号

| 现象 | 处理 |
| --- | --- |
| 忘了管理员密码 | 初始密码在 `reachai.env`；改过又忘了，请另一位平台管理员在「账号与权限」里重置密码 |
| 唯一的平台管理员也登不上了 | 联系负责部署的同学，从数据库层面处理；先 `reachai backup` |

## 还是解决不了

收集这些信息再找开发人员或在项目仓库提 Issue：

- `reachai doctor` 的完整输出；
- 出问题的服务日志：`reachai logs <服务>`（默认显示最近 200 行）；
- 出问题的时间、操作步骤、页面上的提示。

**发出去之前，检查里面没有密码、API Key、`secrets.properties` 的内容。**
