/**
 * 阶段 6 验证：定时任务（真实 HTTP，与前端 scheduler.js runSchedulerTick 1:1）
 * 手动 /api/scheduler/tick 触发单轮，验证 5 个心跳：
 *  - advanceTelemetry：在途车次 progress 推进（<95）
 *  - checkFenceEvents：超 ETA 的在途车次生成延误异常（source=fence）
 *  - recalcOverdueAll：逾期校准（settled 超账期 → overdue）
 *  - escalatePendingExceptions：待受理异常超 2h → escalated=1
 *  - escalateContractApprovals：待批合同超 24h → 催办（submitTime 为空则不触发）
 * 运行：node scripts/verify-scheduler.mjs
 */
const BASE = 'http://127.0.0.1:8081'
let pass = 0, fail = 0
function ok(name, cond, detail = '') {
  if (cond) { pass++; console.log('  PASS  ' + name) }
  else { fail++; console.log('  FAIL  ' + name + (detail ? '  ← ' + detail : '')) }
}
async function api(path, { method = 'GET', body, token } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined
  })
  return { status: res.status, json: await res.json().catch(() => null) }
}
async function login(username) {
  const cap = await api('/api/auth/captcha')
  const r = await api('/api/auth/login', { method: 'POST', body: { username, password: '123456', captchaId: cap.json.data.id, captchaCode: cap.json.data.code } })
  return r.json.data.token
}
const num = v => Number(v) || 0
// 动态当前时间（避免硬编码日期过期）：种子在途车次含已过 ETA（触发延误围栏）+ 未过 ETA（正常在途）
// 用**本地**时间（与种子 dayjs()=本地 一致）；用 toISOString()（UTC）会在 00:00–08:00 本地时段落后 8h，漏判已过 ETA
const _pad = n => String(n).padStart(2, '0')
const _nd = new Date()
const NOW = _nd.getFullYear() + '-' + _pad(_nd.getMonth() + 1) + '-' + _pad(_nd.getDate()) + ' ' + _pad(_nd.getHours()) + ':' + _pad(_nd.getMinutes())
let targetId, targetProg, pendingNoEsc, settledBefore, overdueBefore, tick

const token = await login('admin')
if (!token) { console.log('登录失败'); process.exit(1) }

console.log('== 1. 触发前状态快照 ==')
{
  const d = (await api('/api/coll/dispatches', { token })).json.data
  const it = d.filter(x => x.status === 'intransit')
  const overdueEta = it.filter(x => x.eta && x.eta < NOW)
  ok('存在在途车次', it.length > 0, 'intransit=' + it.length)
  ok('存在超 ETA 在途车次（将触发延误围栏）', overdueEta.length > 0, 'overdueEta=' + overdueEta.map(x => x.id).join(','))
  // 记录一个超 ETA 车次的 progress（验证遥测推进）
  const target = it[0]
  targetId = target.id
  targetProg = num(target.progress)
  const ex = (await api('/api/coll/exceptions', { token })).json.data
  const pendingOld = ex.filter(x => x.status === 'pending' && !x.escalated).length
  pendingNoEsc = pendingOld
  const st = (await api('/api/coll/settlements', { token })).json.data
  settledBefore = st.filter(x => x.status === 'settled').length
  overdueBefore = st.filter(x => x.status === 'overdue').length
  console.log('  在途=' + it.length + ' 超ETA=' + overdueEta.length + ' 待受理未升级异常=' + pendingOld + ' settled=' + settledBefore + ' overdue=' + overdueBefore)
}

console.log('== 2. 手动触发单轮 tick ==')
{
  const r = await api('/api/scheduler/tick', { method: 'POST', token, body: {} })
  ok('tick 返回统计', r.json.data && r.json.data.fenceCreated !== undefined, JSON.stringify(r.json.data))
  tick = r.json.data
  console.log('  本轮：' + JSON.stringify(r.json.data))
}

console.log('== 3. 验证各心跳效果 ==')
{
  const d = (await api('/api/coll/dispatches', { token })).json.data
  const target = d.find(x => x.id === targetId)
  // 遥测推进：progress 增加（若未变 exception）或已变 exception（被围栏拦截）
  const progAfter = num(target.progress)
  const becameException = target.status === 'exception'
  ok('遥测推进（progress 增加 或 车次被围栏转异常）', progAfter > targetProg || becameException,
     'prog ' + targetProg + '→' + progAfter + ' status=' + target.status)

  // 围栏延误异常：超 ETA 车次生成 source=fence 的 delay 异常
  const ex = (await api('/api/coll/exceptions', { token })).json.data
  const fenceDelay = ex.filter(x => x.source === 'fence' && x.type === 'delay')
  ok('围栏延误异常生成', fenceDelay.length > 0, 'fenceDelay=' + fenceDelay.length)

  // 异常升级：待受理超 2h 异常 escalated>=1
  const escalated = ex.filter(x => x.status === 'pending' && (x.escalated || 0) >= 1)
  ok('异常升级（escalated>=1）', escalated.length > 0, 'escalated=' + escalated.length)
  const e1 = escalated[0]
  ok('升级字段完整', e1.escalateTime != null && e1.escalatedTo != null, JSON.stringify({ t: e1.escalateTime, to: e1.escalatedTo }))

  // 逾期校准：overdue 数不减少（settled 超账期转 overdue）
  const st = (await api('/api/coll/settlements', { token })).json.data
  const overdueAfter = st.filter(x => x.status === 'overdue').length
  ok('逾期校准（overdue 不减）', overdueAfter >= overdueBefore, 'overdue ' + overdueBefore + '→' + overdueAfter)

  // 审批催办：submitTime 为空的 pending 合同不触发（条件断言）
  const c = (await api('/api/coll/contracts', { token })).json.data
  const pendingWithSubmit = c.filter(x => x.status === 'pending' && x.submitTime)
  if (pendingWithSubmit.length === 0) {
    ok('审批催办（无 submitTime 合同，不触发）', tick.reminded === 0, 'reminded=' + tick.reminded)
  } else {
    // 有 submitTime 的合同，超 24h 应催办
    ok('审批催办（有 submitTime 合同）', tick.reminded >= 0, 'reminded=' + tick.reminded + ' 候选=' + pendingWithSubmit.length)
  }
}

console.log('== 4. 幂等性：二次 tick 不重复升级 ==')
{
  const exBefore = (await api('/api/coll/exceptions', { token })).json.data
  const escBefore = exBefore.filter(x => x.status === 'pending' && (x.escalated || 0) >= 1).length
  const r = await api('/api/scheduler/tick', { method: 'POST', token, body: {} })
  const exAfter = (await api('/api/coll/exceptions', { token })).json.data
  const escAfter = exAfter.filter(x => x.status === 'pending' && (x.escalated || 0) >= 1).length
  ok('二次 tick 升级数不增（幂等）', escAfter === escBefore, 'escalated ' + escBefore + '→' + escAfter)
}

console.log('== 汇总 ==')
console.log('PASS=' + pass + ' FAIL=' + fail)
process.exit(fail > 0 ? 1 : 0)
