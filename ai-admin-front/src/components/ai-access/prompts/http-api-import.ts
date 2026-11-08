import type { AiPromptScenario } from '../aiPromptScenarios'
import { PLATFORM_PREAMBLE, PLATFORM_RULES } from '../promptHelpers'

const scenario: AiPromptScenario = {
  key: 'http-api-import',
  title: '从代码里整理 HTTP 接口导入 ReachAI',
  category: 'api',
  audience: ['developer'],
  connection: 'platform',
  summary: 'AI 读业务系统代码，整理出 HTTP 接口上传到项目，接纳后试调用只读接口。',
  order: 10,
  variables: [{ name: 'projectCode', label: '项目编码', required: true }],
  template: `${PLATFORM_PREAMBLE}

把当前业务系统代码仓库里的 HTTP 接口整理好，导入 ReachAI 项目「{{projectCode}}」。

1. 先读路由 / Controller，整理出接口：方法、路径、参数、返回值、中文用途、是否只读。不确定的标出来问我。
2. 用 reachai_http_apis_upload 上传（一次最多 200 个），再用 reachai_http_apis_list 确认。
3. 挑我确认过的接口，先 reachai_http_api_get 读来源修订，再 reachai_http_api_accept 接纳。
4. 对只读接口用 reachai_http_api_invoke_read_only 各试一次，告诉我结果；写接口不要试调用。

${PLATFORM_RULES}`,
}

export default scenario
