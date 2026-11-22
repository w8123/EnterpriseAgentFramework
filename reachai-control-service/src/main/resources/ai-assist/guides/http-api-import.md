# 从代码里整理 HTTP 接口导入 ReachAI

> 验证情况：按平台 MCP 工具契约提供操作步骤；实际项目的配置、权限和执行结果仍需逐项核对。

下文“本次用户指定”或“本次页面交接”的值来自当前任务上下文；这些说明文字不是可直接提交的参数。缺少必要信息时先查询授权目录或向用户确认。

把当前业务系统代码仓库里的 HTTP 接口整理好，导入 ReachAI 项目「{{projectCode}}」。

1. 先读路由 / Controller，整理出接口：方法、路径、参数、返回值、中文用途、是否只读。不确定的标出来问我。
2. 用 reachai_http_apis_upload 上传（一次最多 200 个），再用 reachai_http_apis_list 确认。
3. 挑我确认过的接口，先 reachai_http_api_get 读来源修订，再 reachai_http_api_accept 接纳。
4. 对只读接口用 reachai_http_api_invoke_read_only 各试一次，告诉我结果；写接口不要试调用。
