import { ElMessage } from 'element-plus'

export function useSdkAccessWizardActions() {
  async function copyText(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      ElMessage.success('已复制到剪贴板')
    } catch {
      ElMessage.warning('复制失败，请手动选择文本复制')
    }
  }

  return {
    copyText,
  }
}
