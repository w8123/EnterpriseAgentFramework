"""ReachAI + 芋道一键体验初始化。

全部通过 ReachAI 的公开接口完成，等同于管理员在页面上做一遍：
  1. 登录管理台
  2. （填了大模型 Key 时）添加模型厂商账号和对话模型
  3. 给芋道签发 SDK 接入令牌，等芋道启动后自动注册、同步业务方法
  4. 调用权限：芋道超级管理员可以用芋道的全部工具
  5. 建 Workflow 和「ERP 助手」Agent，发布
  6. 嵌入对话：允许芋道页面打开 ERP 助手
  7. MCP 发布 + 员工使用：员工可以在自己的 Claude Code / Codex 里用芋道

每一步先查再建，重复执行是安全的。只用 Python 标准库。
"""
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("REACHAI_URL", "http://reachai-control:18603").rstrip("/")
ADMIN_USER = os.environ.get("REACHAI_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("REACHAI_ADMIN_PASSWORD", "admin123")
YUDAO_PUBLIC_URL = os.environ.get("YUDAO_PUBLIC_URL", "http://localhost:48080").rstrip("/")
TOKEN_FILE = os.environ.get("ENROLLMENT_TOKEN_FILE", "/shared/yudao-enrollment-token")
PROJECT_CODE = "yudao-erp"
AGENT_SLUG = "erp-assistant"
YUDAO_ADMIN_ROLE = "super_admin"

LLM_PROVIDER = (os.environ.get("LLM_PROVIDER") or "deepseek").strip().lower()
LLM_BASE_URL = (os.environ.get("LLM_BASE_URL") or "").strip()
LLM_API_KEY = (os.environ.get("LLM_API_KEY") or "").strip()
LLM_MODEL = (os.environ.get("LLM_MODEL") or "").strip()
DEFAULT_MODELS = {"deepseek": "deepseek-chat", "openai": "gpt-4.1-mini", "tongyi": "qwen-plus",
                  "kimi": "kimi-k2-turbo-preview", "zhipu": "glm-4.5-air"}

READ_METHODS = ["erpProductPage", "erpProductSimpleList", "erpStockPage", "erpWarehouseSimpleList",
                "erpCustomerPage", "erpSaleOrderPage", "erpSupplierPage", "erpPurchaseOrderPage",
                "erpSaleSummary", "erpPurchaseSummary"]
WRITE_METHODS = ["erpCustomerCreate"]


class ApiError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status = status
        self.body = body


cookies = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
csrf = {"value": None}


def log(msg):
    print(f"[reachai-init] {msg}", flush=True)


def call(method, path, body=None, params=None, timeout=120):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if csrf["value"]:
        req.add_header("X-ReachAI-CSRF", csrf["value"])
    try:
        with opener.open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode("utf-8", "replace")) from None
    if not raw:
        return None
    payload = json.loads(raw)
    # Model 接口包一层 {code, data}；Control 接口直接返回对象
    if isinstance(payload, dict) and "code" in payload and "data" in payload and len(payload) <= 4:
        if payload.get("code") not in (0, 200):
            raise ApiError(200, raw)
        return payload["data"]
    return payload


def items_of(page):
    if page is None:
        return []
    if isinstance(page, list):
        return page
    for key in ("records", "items", "content", "list", "data"):
        if isinstance(page.get(key), list):
            return page[key]
    return []


def wait_for(desc, fn, timeout=900, interval=5):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            value = fn()
            if value:
                return value
        except Exception as e:  # noqa: BLE001 - 等待期间的错误只记录
            last = e
        time.sleep(interval)
    raise SystemExit(f"[reachai-init] 等待超时：{desc}（最后一次错误：{last}）")


# ---------------------------------------------------------------- steps

def login():
    def attempt():
        result = call("POST", "/api/platform/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
        csrf["value"] = result["sessionId"]
        return True
    wait_for("登录 ReachAI", attempt, timeout=300)
    log(f"已登录 ReachAI（{ADMIN_USER}）")


def ensure_model():
    """返回对话模型实例 id；没填 Key 时返回 None。"""
    if not LLM_API_KEY:
        # 用户可能已经在管理台「模型」里加过对话模型，直接用它
        for inst in items_of(call("GET", "/model/instances")):
            if inst.get("modelType") == "LLM" and inst.get("status") not in ("ARCHIVED", "DISABLED"):
                log(f"使用管理台里已有的模型：{inst.get('name')}")
                return inst["id"]
        log("没有填写 LLM_API_KEY：跳过模型配置。员工 AI 工具连接（MCP）不需要模型；对话面板要在管理台「模型」里补一个模型。")
        return None
    model_name = LLM_MODEL or DEFAULT_MODELS.get(LLM_PROVIDER, "")
    for inst in items_of(call("GET", "/model/instances")):
        if inst.get("modelName") == model_name and inst.get("status") != "ARCHIVED":
            log(f"模型已存在：{inst.get('name')}（{inst['id']}）")
            return inst["id"]
    account_name = "一键体验模型账号"
    accounts = items_of(call("GET", "/model/instances/provider-accounts", params={"provider": LLM_PROVIDER}))
    account = next((a for a in accounts if a.get("name") == account_name), None)
    connection = {"apiKey": LLM_API_KEY}
    if LLM_BASE_URL:
        connection["baseUrl"] = LLM_BASE_URL
    body = {"name": account_name, "provider": LLM_PROVIDER, "connection": connection,
            "remark": "docker compose 一键体验自动创建"}
    if account is None:
        account = call("POST", "/model/instances/provider-accounts", body)
    created = call("POST", f"/model/instances/provider-accounts/{account['id']}/instances",
                   {"models": [{"modelName": model_name, "modelType": "LLM", "name": f"{model_name}（一键体验）"}]})
    log(f"已添加模型 {model_name}: {json.dumps(created, ensure_ascii=False)[:300]}")
    for inst in items_of(call("GET", "/model/instances")):
        if inst.get("modelName") == model_name:
            return inst["id"]
    raise SystemExit("[reachai-init] 模型创建后没有查到实例")


def find_project():
    for p in items_of(call("GET", "/api/scan-projects", params={"keyword": PROJECT_CODE})):
        if p.get("projectCode") == PROJECT_CODE:
            return p
    return None


def ensure_enrollment():
    project = find_project()
    if project and project.get("projectKind") in ("REGISTERED", "HYBRID"):
        log(f"芋道项目已注册（id={project['id']}）")
        return
    if os.path.exists(TOKEN_FILE) and os.path.getsize(TOKEN_FILE) > 0:
        log("接入令牌已签发，等待芋道注册")
        return
    token = call("POST", "/api/platform/registry-enrollments", {"projectCode": PROJECT_CODE})
    os.makedirs(os.path.dirname(TOKEN_FILE), exist_ok=True)
    with open(TOKEN_FILE + ".tmp", "w") as f:
        f.write(token["enrollmentToken"])
    os.replace(TOKEN_FILE + ".tmp", TOKEN_FILE)
    log("已签发芋道接入令牌，芋道启动后会自动注册")


def sync_business_methods():
    """芋道注册后触发一次同步，并接纳需要人工确认的写方法（新建客户）。"""
    def registered():
        project = find_project()
        return project if project and project.get("projectKind") in ("REGISTERED", "HYBRID") else None
    project = wait_for("芋道启动并注册到 ReachAI（首次约需 1–2 分钟）", registered, timeout=1200, interval=5)

    def synced():
        page = call("GET", "/api/business-methods", params={"projectCode": PROJECT_CODE, "size": 100, "current": 1})
        codes = {m.get("methodCode") for m in items_of(page)}
        if set(READ_METHODS) <= codes:
            return codes
        call("POST", f"/api/scan-projects/{project['id']}/sdk-sync/scan", timeout=160)
        return None
    wait_for("同步芋道业务方法", synced, timeout=300, interval=5)
    for change in items_of(call("GET", f"/api/capability-review/projects/{PROJECT_CODE}/changes")):
        if change.get("reviewStatus") == "PENDING":
            call("POST", f"/api/capability-review/projects/{PROJECT_CODE}/diff-items/{change['id']}/review",
                 {"action": "APPLY", "note": "一键体验：演示环境直接接纳"})
            log(f"已接纳待确认能力 {change.get('name')}")
    methods = {m["methodCode"]: m for m in items_of(
        call("GET", "/api/business-methods", params={"projectCode": PROJECT_CODE, "size": 100, "current": 1}))}
    log(f"芋道业务方法已就绪：{len(methods)} 个")
    return project, methods


def ensure_tool_acl(project):
    """芋道超级管理员（admin 账号）可以用芋道的全部工具。员工能用什么仍按这里的规则控制。"""
    for rule in items_of(call("GET", "/api/tool-acl", params={"roleCode": YUDAO_ADMIN_ROLE, "size": 100})):
        if rule.get("projectCode") == PROJECT_CODE and rule.get("targetName") == "*":
            return
    call("POST", "/api/tool-acl", {"roleCode": YUDAO_ADMIN_ROLE, "projectId": project["id"], "projectCode": PROJECT_CODE,
                                   "targetKind": "TOOL", "targetName": "*", "permission": "ALLOW",
                                   "note": "一键体验：芋道超级管理员可用芋道全部工具", "enabled": True})
    log(f"调用权限：芋道角色 {YUDAO_ADMIN_ROLE} 可用 {PROJECT_CODE} 全部工具")


def ensure_embed_policy(agent_ids):
    creds = [c for c in items_of(call("GET", "/api/platform/embed/credentials", params={"projectCode": PROJECT_CODE}))
             if c.get("projectCode") == PROJECT_CODE]
    if not creds:
        log("没有找到芋道的嵌入凭据，跳过嵌入对话配置")
        return
    for cred in creds:
        origins = sorted(set(json.loads(cred.get("allowedOriginsJson") or "[]")) | {YUDAO_PUBLIC_URL})
        agents = sorted(set(json.loads(cred.get("allowedAgentIdsJson") or "[]")) | set(agent_ids))
        call("PUT", f"/api/platform/embed/credentials/{cred['id']}/policy",
             {"allowedOrigins": origins, "allowedAgentIds": agents,
              "tokenTtlSeconds": cred.get("tokenTtlSeconds") or 3600, "status": "ACTIVE"})
    log(f"嵌入对话：允许 {YUDAO_PUBLIC_URL} 打开 {', '.join(agent_ids) or '（暂无 Agent）'}")


def ensure_mcp_publication(project, methods):
    name = "yudao-erp-employee"
    pub = next((p for p in items_of(call("GET", "/api/mcp/publications", params={"size": 100}))
                if p.get("name") == name), None)
    if pub is None:
        pub = call("POST", "/api/mcp/publications", {"name": name, "description": "员工在自己的 Claude Code / Codex 里查询和办理芋道 ERP 业务"})
    detail = call("GET", f"/api/mcp/publications/{pub['id']}")
    existing = {i.get("sourceRef") for i in (detail.get("items") or [])}
    added = 0
    for code in READ_METHODS + WRITE_METHODS:
        ref = f"{PROJECT_CODE}:{code}"
        if code in methods and ref not in existing:
            call("POST", f"/api/mcp/publications/{pub['id']}/items", {"sourceKind": "CAPABILITY", "sourceRef": ref})
            added += 1
    if added or (detail.get("publication") or {}).get("state") != "PUBLISHED":
        report = call("POST", f"/api/mcp/publications/{pub['id']}/precheck")
        if not report.get("ok"):
            raise SystemExit(f"[reachai-init] MCP 发布预检没通过：{json.dumps(report, ensure_ascii=False)[:500]}")
        call("POST", f"/api/mcp/publications/{pub['id']}/publish", {"irreversibleAcknowledged": False})
    call("PUT", f"/api/mcp/publications/{pub['id']}/employee-access",
         {"projectId": project["id"], "projectCode": PROJECT_CODE, "displayName": "芋道 ERP", "enabled": True})
    log(f"MCP 发布 {name} 已发布，员工可在芋道对话面板里生成 AI 工具连接")


# Agent 的工具是 Workflow：每个 Workflow 调一个芋道业务方法，把结果原样交给 Agent 组织回答。
TOOL_WORKFLOWS = [
    {"slug": "erp-product-query", "tool": "query_products", "name": "查询产品", "method": "erpProductPage",
     "description": "按产品名称模糊查询产品，返回产品编号、规格和价格。查库存前先用它拿产品编号。",
     "inputs": {"name": ("string", "产品名称关键字，可留空查全部", False)}},
    {"slug": "erp-stock-query", "tool": "query_stock", "name": "查询产品库存", "method": "erpStockPage",
     "description": "按产品编号查询各仓库的库存数量。",
     "inputs": {"productId": ("integer", "产品编号（先用 query_products 查到）", True)}},
    {"slug": "erp-customer-query", "tool": "query_customers", "name": "查询客户", "method": "erpCustomerPage",
     "description": "按客户名称或手机号查询客户。",
     "inputs": {"name": ("string", "客户名称关键字，可留空", False), "mobile": ("string", "手机号，可留空", False)}},
    {"slug": "erp-sale-order-query", "tool": "query_sale_orders", "name": "查询销售订单", "method": "erpSaleOrderPage",
     "description": "按客户编号、订单号或产品编号查询销售订单。",
     "inputs": {"customerId": ("integer", "客户编号，可留空", False), "no": ("string", "订单号，可留空", False),
                "productId": ("integer", "产品编号，可留空", False)}},
]


def ensure_tool_workflows(project):
    """返回 Agent 工具配置列表。"""
    existing = {w.get("keySlug"): w for w in items_of(call("GET", "/api/workflows", params={"projectCode": PROJECT_CODE}))}
    tools = []
    for spec in TOOL_WORKFLOWS:
        props = {k: {"type": t, "description": d} for k, (t, d, _) in spec["inputs"].items()}
        required = [k for k, (_, _, req) in spec["inputs"].items() if req]
        input_schema = {"type": "object", "properties": props, "required": required}
        graph = {"schemaVersion": 2, "entryNodeId": "input", "exitNodeIds": ["answer"], "inputSchema": input_schema,
                 "nodes": [{"id": "input", "type": "USER_INPUT", "config": {}},
                           {"id": "call", "name": spec["name"], "type": "TOOL",
                            "ref": {"kind": "TOOL", "qualifiedName": f"{PROJECT_CODE}:{spec['method']}"},
                            "config": {"inputMapping": {k: f"params.{k}" for k in spec["inputs"]}}},
                           {"id": "answer", "type": "ANSWER", "config": {"template": "{{nodeOutput.call.data}}"}}],
                 "edges": [{"from": "input", "to": "call"}, {"from": "call", "to": "answer"}]}
        wf = existing.get(spec["slug"])
        if wf is None:
            wf = call("POST", "/api/workflows", {
                "keySlug": spec["slug"], "name": spec["name"], "description": spec["description"],
                "projectId": project["id"], "projectCode": PROJECT_CODE, "workflowKind": "GENERAL",
                "inputSchemaJson": json.dumps(input_schema, ensure_ascii=False)})
        state = call("GET", f"/api/workflows/{wf['id']}/working-copy")
        if not state.get("activeVersion"):
            state = call("PUT", f"/api/workflows/{wf['id']}/working-copy", {
                "baseRevision": state["revision"], "graphSpecJson": json.dumps(graph, ensure_ascii=False),
                "inputSchemaJson": json.dumps(input_schema, ensure_ascii=False)})
            call("POST", f"/api/workflows/{wf['id']}/versions/publish",
                 {"version": "1.0.0", "note": "一键体验自动发布", "baseRevision": state["revision"]})
            state = call("GET", f"/api/workflows/{wf['id']}/working-copy")
            log(f"已发布 Workflow「{spec['name']}」")
        version = state["activeVersion"]
        tools.append({"workflowId": wf["id"], "workflowVersionId": version.get("id") or version.get("versionId"),
                      "workflowVersion": version.get("version"), "toolName": spec["tool"],
                      "description": spec["description"], "riskLevel": "READ", "readOnly": True, "enabled": True})
    return tools


AGENT_PROMPT = """你是芋道 ERP 的业务助手，帮员工查询产品、库存、客户和销售订单。
- 按产品名称查库存时，先查产品拿到产品编号，再按产品编号查库存，按仓库分别列出数量。
- 回答用简洁的中文，数字保留原样，必要时用表格。"""


def ensure_agent(project, model_id, tools):
    agent = next((a for a in items_of(call("GET", "/api/agents", params={"projectCode": PROJECT_CODE}))
                  if a.get("keySlug") == AGENT_SLUG), None)
    if agent is None:
        agent = call("POST", "/api/agents", {
            "keySlug": AGENT_SLUG, "name": "ERP 助手", "projectId": project["id"], "projectCode": PROJECT_CODE,
            "description": "查询芋道 ERP 的产品、库存、客户和销售订单", "visibility": "PROJECT",
            "allowedRolesJson": None, "enabled": True})
        log(f"已创建 Agent「ERP 助手」（{agent['id']}）")
    versions = items_of(call("GET", f"/api/agents/{agent['id']}/config-versions"))
    active = next((v for v in versions if v.get("status") == "ACTIVE"), None)
    if active and not model_id:
        log("Agent「ERP 助手」已发布")
        return agent
    if active and (active.get("modelInstanceId") or None) == model_id and len(active.get("tools") or []) == len(tools):
        log("Agent「ERP 助手」已是最新发布版本")
        return agent
    draft = call("PUT", f"/api/agents/{agent['id']}/config-versions/draft", {
        "runtimeType": "AGENTSCOPE", "systemPrompt": AGENT_PROMPT, "modelInstanceId": model_id,
        "maxPlanSteps": 6, "maxWorkflowCalls": 4, "maxReplans": 2, "totalTimeoutMs": 300000,
        "workflowTimeoutMs": 180000, "pageBridgeTimeoutMs": 30000, "parallelReadOnly": True,
        "policyProfile": "STANDARD", "toolCatalogMode": "ALLOW_LIST",
        "memory": {"sessionMemoryEnabled": True, "sessionMemoryWindow": 40,
                   "personalMemoryRead": False, "personalMemoryWrite": "OFF"},
        "configJson": None, "tools": tools, "remoteAgents": []})
    if not model_id:
        log("Agent「ERP 助手」已保存草稿；没有模型不能发布。填好 LLM_API_KEY 后重新执行 reachai-init 即可发布。")
        return agent
    call("POST", f"/api/agents/{agent['id']}/config-versions/{draft['id']}/publish", {"publishedBy": "reachai-init"})
    log(f"已发布 Agent「ERP 助手」v{draft.get('versionNo')}（{len(tools)} 个工具，模型 {model_id or '未配置'}）")
    return agent


def main():
    login()
    model_id = ensure_model()
    ensure_enrollment()
    project, methods = sync_business_methods()
    ensure_tool_acl(project)
    ensure_mcp_publication(project, methods)
    tools = ensure_tool_workflows(project)
    agent = ensure_agent(project, model_id, tools)
    # 芋道前端按 keySlug 打开助手，所以 id 和 keySlug 都放进白名单
    ensure_embed_policy([agent["id"], AGENT_SLUG])
    log(f"完成。model={model_id}")


if __name__ == "__main__":
    try:
        main()
    except ApiError as e:
        log(f"接口调用失败：{e}")
        sys.exit(1)
