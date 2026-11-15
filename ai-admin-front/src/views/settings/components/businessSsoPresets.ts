export type BusinessSsoPresetKey = 'yudao' | 'oidc' | 'password'

interface PresetValues {
  authorizeUrl: string
  tokenUrl: string
  userInfoUrl: string
  scope: string
  userIdPath: string
  userNamePath: string
  headers: string
  mode: 'OAUTH' | 'PASSWORD'
  loginContentType?: 'JSON' | 'FORM'
  loginBody?: string
  tokenPath?: string
  tokenHeader?: string
  tokenPrefix?: string
  code?: string
  label?: string
  projectCode?: string
}

interface BusinessSsoPreset {
  label: string
  hint: string
  fill: (origin: string) => PresetValues
}

function trimOrigin(origin: string) {
  return origin.trim().replace(/\/+$/, '')
}

/**
 * 芋道 ships its own OAuth2 server: add a client in 芋道 「系统管理 → OAuth 2.0 → 应用管理」 with the
 * authorization_code grant, scope user.read ticked as auto-approve and the ReachAI callback as redirect URI.
 */
export const businessSsoPresets: Record<BusinessSsoPresetKey, BusinessSsoPreset> = {
  yudao: {
    label: '芋道',
    hint: '在芋道「系统管理 → OAuth 2.0 → 应用管理」新增应用：授权类型选授权码，权限范围 user.read 设为自动授权，可重定向地址填上面的回调地址。',
    fill: (origin) => {
      const base = trimOrigin(origin) || 'https://芋道地址'
      return {
        code: 'yudao',
        label: '芋道 ERP',
        projectCode: 'yudao-erp',
        authorizeUrl: `${base}/sso`,
        tokenUrl: `${base}/admin-api/system/oauth2/token`,
        userInfoUrl: `${base}/admin-api/system/oauth2/user/get`,
        scope: 'user.read',
        userIdPath: 'data.id',
        userNamePath: 'data.nickname',
        headers: 'tenant-id: 1',
        mode: 'OAUTH',
      }
    },
  },
  oidc: {
    label: '标准 OIDC',
    hint: '企业微信、钉钉、飞书、Keycloak 等统一登录：从它们的 OIDC 配置里抄登录页、令牌和用户信息地址。',
    fill: (origin) => {
      const base = trimOrigin(origin) || 'https://登录服务地址'
      return {
        authorizeUrl: `${base}/authorize`,
        tokenUrl: `${base}/token`,
        userInfoUrl: `${base}/userinfo`,
        scope: 'openid profile',
        userIdPath: 'sub',
        userNamePath: 'name',
        headers: '',
        mode: 'OAUTH',
      }
    },
  },
  password: {
    label: '只有登录接口',
    hint: '业务系统不用做任何改动：员工在 ReachAI 登录页输入业务系统的账号密码，ReachAI 调业务系统自己的登录接口验证，不保存密码。',
    fill: (origin) => {
      const base = trimOrigin(origin) || 'https://业务系统地址'
      return {
        authorizeUrl: '',
        tokenUrl: `${base}/login`,
        userInfoUrl: '',
        scope: '',
        userIdPath: '',
        userNamePath: '',
        headers: '',
        mode: 'PASSWORD',
        loginContentType: 'JSON',
        loginBody: '{"username":"{{username}}","password":"{{password}}"}',
        tokenPath: 'data.token',
        tokenHeader: 'Authorization',
        tokenPrefix: 'Bearer ',
      }
    },
  },
}

export function headerLines(headers: Record<string, string> | undefined) {
  return Object.entries(headers ?? {})
    .map(([name, value]) => `${name}: ${value}`)
    .join('\n')
}

export function parseHeaderLines(text: string): Record<string, string> {
  const headers: Record<string, string> = {}
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const index = line.indexOf(':')
    if (index <= 0) throw new Error(`请求头格式应为「名称: 值」：${line}`)
    headers[line.slice(0, index).trim()] = line.slice(index + 1).trim()
  }
  return headers
}
