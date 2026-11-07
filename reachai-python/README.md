# FastAPI 业务系统接入

`reachai-fastapi` 1.0.0 支持 Python 3.10–3.13、FastAPI >=0.115,<1、Pydantic >=2.8.2,<3。SDK 与 Java/C# 共用 [业务系统 SDK 接入协议](../docs/contracts/sdk-protocol.md)，提供项目注册、心跳、签名同步、显式业务方法、调用身份、Embed Token 和业务索引接入。HTTP API 是独立资产，使用平台的 OpenAPI 导入，不将 FastAPI 接口伪装为 Spring 来源。

构建 `python -m build`，使用平台提供的 SHA-256 校验 wheel 后，执行 `python -m pip install <wheel文件路径>`。依赖版本受 `pyproject.toml` 约束；实际部署应使用企业批准的依赖源和锁定文件。

## 配置与业务能力

```python
import os
from fastapi import FastAPI, Request
from pydantic import BaseModel
from reachai_fastapi import (
    ReachAI, ReachAIOptions, EmbedPrincipal, EncryptedFileCredentialStore,
    current_invocation,
)

app = FastAPI()
options = ReachAIOptions(
    registry_url=os.environ['REACHAI_REGISTRY_URL'],
    project_code=os.environ['REACHAI_PROJECT_CODE'],
    base_url=os.environ['REACHAI_BASE_URL'],
    enrollment_token=os.environ.get('REACHAI_ENROLLMENT_TOKEN', ''),
    allowed_origins=(os.environ['REACHAI_BUSINESS_ORIGIN'],),
)
store = EncryptedFileCredentialStore(
    os.environ['REACHAI_CREDENTIAL_FILE'],
    os.environ['REACHAI_CREDENTIAL_KEY'],
)

# 必须替换为业务系统真实登录态和数据授权，不能信任浏览器提交的身份。
async def resolve_employee(request: Request, agent_id: str) -> EmbedPrincipal | None:
    raise NotImplementedError('从已经认证的业务登录态解析用户、租户与业务会话')

async def apply_business_identity(identity, call_next):
    raise NotImplementedError('恢复业务用户身份并执行真实业务授权')

reach = ReachAI(options, credential_store=store,
                principal_resolver=resolve_employee,
                security_bridge=apply_business_identity)

class OrderView(BaseModel):
    order_id: int
    status: str

@reach.capability(name='order.query', title='查询订单', side_effect='READ',
                  business_identity_required=True,
                  parameter_names={'order_id': 'orderId'})
async def query_order(order_id: int) -> OrderView:
    caller = current_invocation.get()
    # 在真实业务数据中校验 caller 的用户、租户与订单权限。
    raise NotImplementedError('调用已授权的业务服务')

reach.mount(app)
```

宿主自行从受保护环境或秘密管理服务读取配置。`project_code` 必须已经是平台规范的小写编码。首次注册必须提供一次性 token 和支持持久化的 `CredentialStore`；也可显式提供 `app_key`、`app_secret`，但不可与 token 混用。`EncryptedFileCredentialStore` 使用宿主提供的 Fernet 密钥加密并原子创建凭据文件；密钥必须独立存储，不能放在凭据文件旁或提交源码。目录必须限制运行账号访问，Windows 须由宿主配置 ACL。密钥丢失不能自动重用 token。

SDK 在发送首次注册前持久化 pending 标记；响应丢失、进程中断或凭据保存失败后，不自动重放一次性 token。应先核对平台结果并恢复凭据；只有确认平台没有消费 token 后，才由运维人员按自己的恢复流程处理标记。多 worker 不应同时使用一次性 token：应先由单个实例注册，再为其他 worker 提供同一受保护凭据。

只同步显式登记的能力；调用 `reach.mark_empty_inventory_complete()` 才表示已确认完整清单为空。启动会注册并定时心跳；平台触发 `/reachai/registry/capabilities/sync` 时进行完整同步。默认回调 nonce 防重放存储只覆盖单进程；多 worker/多副本应注入实现 `CallbackNonceStore.claim(project_code, nonce, expires_at_ms)` 的共享原子存储，并确保故障时拒绝请求。

能力的参数和返回值要求明确类型；支持标量、可空值、类型明确的列表和非递归 Pydantic DTO。未知字段、缺失必需值、动态字典和递归 DTO 不会被当作有效契约。`required_roles` 是区分大小写的字符串元组，角色能力必须同时声明 `business_identity_required=True`。业务用户仍需由宿主检查，SDK 的身份和角色检查不替代行权限、事务或业务规则。`current_invocation` 是请求作用域上下文，异步并发任务之间隔离；`claims` 包含已验证令牌的只读字段，宿主必须自行核实其业务含义。

## 嵌入和退出

`principal_resolver(request, agent_id)` 必须从宿主验证过的 Cookie 或会话取得 `EmbedPrincipal`；`attributes['tenantId']`、用户、角色和业务会话都不能来自浏览器提交字段。将实际业务页面来源写入 `allowed_origins`；broker 必须收到匹配的 `Origin`。对 Cookie 认证宿主还应使用现有 CSRF 策略。

业务退出时调用 `await reach.revoke_business_session(session_id)`，并由宿主负责网络失败后的可靠处理；只有服务端确认后才表示 ReachAI 撤销完成。浏览器只请求本系统 `/reachai/embed/token` 并得到短期 Token，不能接触项目密钥。前端对话组件与页面动作桥按[嵌入指南](../docs/guides/embed-chat.md)接入。

## 业务索引

`await reach.index_upsert(index_code, record)`、`index_batch(index_code, records)` 和 `index_delete(index_code, biz_id)` 对实际 UTF-8 正文做签名。回源业务方法使用 `business_index_resolver=True`、`business_identity_required=True`，声明唯一参数 `request: BusinessIndexRequest` 和返回值 `BusinessIndexResolution`。宿主查询真实业务记录后，通过 `BusinessIndexAccessScope.authorize_row(...)` 校验当前用户对该租户和对象的可见性，再使用 `BusinessIndexResolution.create_authorized(...)` 返回结果。SDK 会将返回作用域与已验签身份、请求对象逐项核对；宿主仍负责可靠地判断用户是否能看到该行。

## 验证

在项目根目录安装 `.[test]` 后执行 `python -m pytest`。共享协议向量、无效签名和过期令牌、并发身份、注册结果不确定、主体解析和业务索引作用域均有测试。单元测试和测试夹具不代替独立业务系统、平台联调与目标环境验收。
