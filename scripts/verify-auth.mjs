/**
 * 阶段 2 验证：鉴权 + RBAC + 审计全链路（真实 HTTP 请求，非 mock）
 * 运行：node scripts/verify-auth.mjs
 * 前置：后端已启动（WSL Ubuntu-24.04，端口 8081）
 */
const BASE = 'http://127.0.0.1:8081'
let pass = 0, fail = 0
const ok = (name, cond, extra = '') => {
  if (cond) { pass++; console.log(`  PASS  ${name}`) }
  else { fail++; console.log(`  FAIL  ${name} ${extra}`) }
}

async function api(path, { method = 'GET', body, token } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  let json = null
  try { json = await res.json() } catch {}
  return { status: res.status, json }
}

async function login(username, password, { captchaId, captchaCode } = {}) {
  return api('/api/auth/login', {
    method: 'POST',
    body: { username, password, captchaId, captchaCode },
  })
}

async function freshCaptcha() {
  const { json } = await api('/api/auth/captcha')
  return json.data // { id, code, svg }
}

console.log('== 1. 健康检查 ==')
{
  const r = await api('/api/health')
  ok('health UP', r.status === 200 && r.json.status === 'UP', JSON.stringify(r.json))
}

console.log('== 2. 验证码 ==')
let cap
{
  const r = await api('/api/auth/captcha')
  cap = r.json?.data
  ok('返回 id/code/svg', !!cap?.id && cap.code.length === 4 && cap.svg.includes('<svg'), JSON.stringify(r.json).slice(0, 100))
}

console.log('== 3. 登录 ==')
let adminToken
{
  const c = await freshCaptcha()
  const r = await login('admin', '123456', { captchaId: c.id, captchaCode: c.code })
  adminToken = r.json?.data?.token
  ok('admin 登录成功', r.status === 200 && r.json.ok === true && !!adminToken, JSON.stringify(r.json).slice(0, 120))
  ok('user 不含密码哈希', r.json?.data?.user && !JSON.stringify(r.json.data.user).includes('$2'))
}
{
  const c = await freshCaptcha()
  const r = await login('admin', '123456', { captchaId: c.id, captchaCode: c.code.toUpperCase() === c.code ? 'XXXX' : c.code })
  ok('验证码错误 → code=captcha', r.json?.ok === false && r.json.code === 'captcha', JSON.stringify(r.json))
}
{
  const c = await freshCaptcha()
  const r = await login('admin', 'wrong-pass', { captchaId: c.id, captchaCode: c.code })
  ok('密码错误 → code=credential', r.json?.ok === false && r.json.code === 'credential', JSON.stringify(r.json))
}
{
  const c = await freshCaptcha()
  const r = await login('no-such-user-xyz', 'whatever', { captchaId: c.id, captchaCode: c.code })
  ok('用户不存在 → code=credential', r.json?.ok === false && r.json.code === 'credential', JSON.stringify(r.json))
}
{
  const c = await freshCaptcha()
  const r = await login('admin', '123456', { captchaId: 'CAP-NONEXIST000', captchaCode: 'AAAA' })
  ok('验证码不存在 → code=captcha', r.json?.ok === false && r.json.code === 'captcha')
}
{
  const c = await freshCaptcha()
  // 一次性：同一验证码第二次使用必须失败
  const r1 = await login('admin', '123456', { captchaId: c.id, captchaCode: c.code })
  const r2 = await login('admin', '123456', { captchaId: c.id, captchaCode: c.code })
  ok('验证码一次性（复用失败）', r1.json?.ok === true && r2.json?.ok === false && r2.json.code === 'captcha')
}
{
  const c = await freshCaptcha()
  const r = await login('user13', '123456', { captchaId: c.id, captchaCode: c.code })
  ok('停用账号 → code=disabled', r.json?.ok === false && r.json.code === 'disabled', JSON.stringify(r.json))
}

console.log('== 4. JWT 会话 ==')
{
  const r = await api('/api/auth/me', { token: adminToken })
  ok('me 返回操作人', r.status === 200 && r.json?.data?.username === 'admin' && r.json.data.role === '平台管理员', JSON.stringify(r.json))
}
{
  const r = await api('/api/auth/me')
  ok('无 token → 401', r.status === 401 && r.json?.code === 'unauthenticated', `status=${r.status}`)
}
{
  const r = await api('/api/auth/me', { token: 'garbage.token.here' })
  ok('无效 token → 401', r.status === 401, `status=${r.status}`)
}

console.log('== 5. RBAC（@RequireAction 切面）==')
let user16Token
{
  const c = await freshCaptcha()
  const r = await login('user16', '123456', { captchaId: c.id, captchaCode: c.code })
  user16Token = r.json?.data?.token
  ok('user16（只读）登录成功', r.json?.ok === true, JSON.stringify(r.json).slice(0, 100))
}
{
  const r = await api('/api/dispatch/probe', { method: 'POST', token: adminToken })
  ok('admin 下发调度单 → 放行', r.status === 200 && r.json?.ok === true && r.json.data.startsWith('dispatch-ok:admin'), JSON.stringify(r.json))
}
{
  const r = await api('/api/dispatch/probe', { method: 'POST', token: user16Token })
  ok('user16 下发调度单 → 403 拦截', r.status === 403 && r.json?.ok === false && r.json.code === 'forbidden', `status=${r.status} ${JSON.stringify(r.json).slice(0, 100)}`)
  ok('拦截文案与前端一致', r.json?.error?.includes('无此操作权限') && r.json.error.includes('服务层拦截'), r.json?.error)
}
{
  const r = await api('/api/dispatch/probe', { method: 'POST' })
  ok('未登录 → 401（默认拒绝在认证层）', r.status === 401, `status=${r.status}`)
}

console.log('== 6. 审计日志（op_log 落库）==')
{
  const { execFileSync } = await import('node:child_process')
  // 用参数数组传 bash -lc 脚本，避免外层 shell 引号嵌套；SQL 单引号在 bash 双引号串内安全
  const q = (sql) => execFileSync(
      'wsl', ['-d', 'Ubuntu-24.04', '--', 'bash', '-lc',
        `mysql -ublms -pblms123456 -h127.0.0.1 blms -N -e "${sql}"`],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  const loginFails = q("SELECT COUNT(*) FROM op_log WHERE result='fail' AND action='登录系统'")
  ok('登录失败已落审计（≥3 条）', Number(loginFails) >= 3, `count=${loginFails}`)
  const loginOk = q("SELECT COUNT(*) FROM op_log WHERE result='success' AND action='登录系统'")
  ok('登录成功已落审计（≥3 条）', Number(loginOk) >= 3, `count=${loginOk}`)
  const rbacBlock = q("SELECT COUNT(*) FROM op_log WHERE result='fail' AND module='调度管理'")
  ok('RBAC 拦截已落审计（≥1 条）', Number(rbacBlock) >= 1, `count=${rbacBlock}`)
  const rbacOk = q("SELECT COUNT(*) FROM op_log WHERE result='success' AND module='调度管理'")
  ok('调度放行已落审计（≥1 条）', Number(rbacOk) >= 1, `count=${rbacOk}`)
  const sample = q("SELECT user, username, action, result FROM op_log ORDER BY id DESC LIMIT 1")
  ok('审计含操作人信息', sample.includes('user16') || sample.includes('admin'), sample)
}

console.log('== 汇总 ==')
console.log(`PASS=${pass} FAIL=${fail}`)
process.exit(fail > 0 ? 1 : 0)
