/** Shared display labels; kept separate from the optional Excel exporter. */
const labels: Record<string, string> = {
  PHONE: '电话号码/手机号', EMAIL: '邮箱', ID_CARD: '身份证', BANK_CARD: '银行卡',
  REAL_NAME: '姓名', USER_CODE: '工号', USER_ID: '用户ID', PASSWORD_SECRET: '密码/密钥',
  ADDRESS: '地址', IP_ADDRESS: 'IP', DEVICE_ID: '设备标识', SSO_TOKEN: 'SSO/会话令牌',
  API_KEY: 'API Key', COOKIE_SESSION: 'Cookie/会话', MEDICAL: '医疗信息', BIOMETRIC: '生物特征',
  LOCATION: '位置', EDUCATIONAL_BACKGROUND: '学历', CREDIT: '征信', OTHER_PII: '其他个人信息',
}
export function scanSensitiveTypeLabel(code: string): string { return labels[code] ?? code }
