import { guideScenario } from '../promptHelpers'

export default guideScenario({
  key: 'embed-script',
  topic: 'embed-script',
  title: '老页面用 script 标签接入嵌入对话',
  summary: 'jQuery、JSP、多页应用等没有构建工具的页面，用一个 script 标签接入。',
  category: 'embed',
  order: 40,
  goal: '在当前没有前端构建工具的页面里用 script 标签接入 ReachAI 嵌入对话',
})
