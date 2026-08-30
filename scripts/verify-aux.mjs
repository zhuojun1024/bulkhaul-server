/**
 * 阶段 3 验证：辅助域端到端（真实 HTTP，与前端 flow.js 1:1）
 *  - 磅单更正：守卫 + 成功更正 + 结算联动（已入账单重算金额）
 *  - 异常处置：上报 → 受理 → 处置 → 关闭（close 联动结算补扣 + 事故结案）
 *  - 手工入库：守卫（无仓库/非运营/无商品/数量≤0/超容量）+ 成功入库 + 批次 + 库存增加
 * 运行：node scripts/verify-aux.mjs（前置：后端已启动；DB 保留结算数据）
 */
const BASE = 'http://127.0.0.1:8081'
let pass = 0, fail = 0
function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log('  PASS  ' + name) }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  [' + extra + ']' : '')) }
}
async function api(path, { method = 'GET', body, token } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json().catch(() => ({}))
  return { status: res.status, json }
}
async function login(username) {
  const cap = await api('/api/auth/captcha')
  const r = await api('/api/auth/login', { method: 'POST', body: { username, password: '123456', captchaId: cap.json.data.id, captchaCode: cap.json.data.code } })
  return r.json.data.token
}

const token = await login('admin')
ok('admin 登录', !!token)

console.log('== 1. 磅单更正（守卫 + 成功 + 结算联动）==')
{
  const { json } = await api('/api/weighing/BZ-99999/correct', { method: 'POST', token, body: { newNet: 30, reason: 'x' } })
  ok('守卫：磅单不存在', json.data?.error === '磅单不存在', JSON.stringify(json.data))
  // 取一个真实出磅磅单（结算量按出磅净重算，改出磅才影响结算金额）
  const { json: ws } = await api('/api/coll/weighings', { token })
  const w = ws.data.find(x => x.type === '出磅') || ws.data[0]
  ok('存在出磅磅单', !!w && w.type === '出磅', 'weighings=' + ws.data.length)
  const { json: e1 } = await api(`/api/weighing/${w.id}/correct`, { method: 'POST', token, body: { newNet: -5, reason: 'x' } })
  ok('守卫：净重须>0', e1.data?.error === '复磅净重须为大于 0 的数值', JSON.stringify(e1.data))
  const { json: e2 } = await api(`/api/weighing/${w.id}/correct`, { method: 'POST', token, body: { newNet: w.net, reason: 'x' } })
  ok('守卫：与原值相同无需更正', e2.data?.error === '复磅净重与原值相同，无需更正', JSON.stringify(e2.data))
  const { json: e3 } = await api(`/api/weighing/${w.id}/correct`, { method: 'POST', token, body: { newNet: w.net + 1, reason: '  ' } })
  ok('守卫：须填复磅原因', e3.data?.error === '请填写复磅原因', JSON.stringify(e3.data))
  // 成功更正
  const newNet = +(w.net + 2).toFixed(2)
  const { json: c } = await api(`/api/weighing/${w.id}/correct`, { method: 'POST', token, body: { newNet, reason: '复磅复核' } })
  ok('成功更正', c.data?.ok === true, JSON.stringify(c.data))
  ok('返回 oldNet/net', c.data?.oldNet === w.net && c.data?.net === newNet, JSON.stringify(c.data))
  // 校验磅单字段
  const { json: w2 } = await api(`/api/coll/weighings/${w.id}`, { token })
  ok('corrected=true', w2.data?.corrected === true, JSON.stringify(w2.data))
  ok('originalNet 记录原值', w2.data?.originalNet === w.net, JSON.stringify(w2.data))
  ok('correctReason/correctOperator', w2.data?.correctReason === '复磅复核' && !!w2.data?.correctOperator, JSON.stringify(w2.data))
  // 结算联动：若该磅单车次已入账单 → 账单金额重算
  const d = w2.data?.dispatchId ? (await api(`/api/coll/dispatches/${w2.data.dispatchId}`, { token })).json.data : null
  if (d && d.settlementId) {
    const { json: s } = await api(`/api/coll/settlements/${d.settlementId}`, { token })
    ok('结算联动：账单含 adjustments', Array.isArray(s.data?.adjustments) && s.data.adjustments.length > 0, JSON.stringify(s.data?.adjustments))
    ok('结算联动：已对账账单回待对账', s.data?.status === 'pending', 'status=' + s.data?.status)
  } else {
    console.log('  (该磅单车次未入账单，跳过结算联动断言)')
  }
}

console.log('== 2. 异常处置（上报→受理→处置→关闭，close 联动结算补扣）==')
{
  // 取一个执行中调度单（pending/loading/intransit/unloading）
  const { json: ds } = await api('/api/coll/dispatches', { token })
  const d = ds.data.find(x => ['pending', 'loading', 'intransit', 'unloading'].includes(x.status))
  ok('存在执行中调度单', !!d, 'statuses=' + [...new Set(ds.data.map(x => x.status))].join(','))
  if (d) {
    const { json: rep } = await api(`/api/dispatch/${d.id}/reportException`, { method: 'POST', token, body: { description: '车辆抛锚', type: 'other', level: 'medium' } })
    ok('上报异常成功', rep.data?.ok === true, JSON.stringify(rep.data))
    const e = rep.data?.exception
    ok('生成异常单', !!e?.id && e.id.startsWith('YC-'), JSON.stringify(e))
    // 调度单 → exception
    const { json: d2 } = await api(`/api/coll/dispatches/${d.id}`, { token })
    ok('调度单状态 → exception', d2.data?.status === 'exception', 'status=' + d2.data?.status)
    ok('exceptionFrom 记录原态', d2.data?.exceptionFrom === d.status, 'from=' + d2.data?.exceptionFrom)
    // 受理
    const { json: acc } = await api(`/api/exception/${e.id}/accept`, { method: 'POST', token, body: { handler: '张建国' } })
    ok('受理异常成功', acc.data?.ok === true, JSON.stringify(acc.data))
    const { json: e2 } = await api(`/api/coll/exceptions/${e.id}`, { token })
    ok('异常单 → handling', e2.data?.status === 'handling' && e2.data?.handler === '张建国', JSON.stringify(e2.data))
    // 处置完成
    const { json: fin } = await api(`/api/exception/${e.id}/finish`, { method: 'POST', token, body: { result: '已修复', cost: 1500 } })
    ok('处置完成成功', fin.data?.ok === true, JSON.stringify(fin.data))
    const { json: e3 } = await api(`/api/coll/exceptions/${e.id}`, { token })
    ok('处置结果/损失记录', e3.data?.result === '已修复' && e3.data?.cost === 1500, JSON.stringify(e3.data))
    // 关闭（联动结算补扣：若车次已入账单）
    const { json: clo } = await api(`/api/exception/${e.id}/close`, { method: 'POST', token })
    ok('关闭异常单成功', clo.data?.ok === true, JSON.stringify(clo.data))
    const { json: e4 } = await api(`/api/coll/exceptions/${e.id}`, { token })
    ok('异常单 → closed', e4.data?.status === 'closed', 'status=' + e4.data?.status)
    // 若车次已入账单 → 结算补扣
    const dset = d2.data?.settlementId
    if (dset) {
      const { json: s } = await api(`/api/coll/settlements/${dset}`, { token })
      ok('结算补扣：exceptionLoss 增加', s.data?.exceptionLoss >= 1500, 'exceptionLoss=' + s.data?.exceptionLoss)
      ok('结算补扣：settleApplied 标记', e4.data?.settleApplied === dset, 'settleApplied=' + e4.data?.settleApplied)
    } else {
      console.log('  (该调度单未入账单，跳过结算补扣断言)')
    }
  }
}

console.log('== 3. 手工入库（守卫 + 成功 + 批次 + 库存增加）==')
{
  const { json: whs } = await api('/api/coll/warehouses', { token })
  const wh = whs.data.find(x => x.status === 'operating')
  ok('存在运营中仓库', !!wh, 'warehouses=' + whs.data.length)
  const { json: cms } = await api('/api/coll/commodities', { token })
  const cm = cms.data[0]
  // 守卫
  const { json: g1 } = await api('/api/warehouse/inbound', { method: 'POST', token, body: { warehouseId: 'WH-999', commodityId: cm.id, quantity: 10 } })
  ok('守卫：请选择仓库', g1.data?.error === '请选择仓库', JSON.stringify(g1.data))
  const { json: g2 } = await api('/api/warehouse/inbound', { method: 'POST', token, body: { warehouseId: wh.id, commodityId: 'CM-999', quantity: 10 } })
  ok('守卫：请选择商品', g2.data?.error === '请选择商品', JSON.stringify(g2.data))
  const { json: g3 } = await api('/api/warehouse/inbound', { method: 'POST', token, body: { warehouseId: wh.id, commodityId: cm.id, quantity: -5 } })
  ok('守卫：数量须>0', g3.data?.error === '入库数量须为大于 0 的数值', JSON.stringify(g3.data))
  const { json: g4 } = await api('/api/warehouse/inbound', { method: 'POST', token, body: { warehouseId: wh.id, commodityId: cm.id, quantity: 999999 } })
  ok('守卫：超仓库容量', String(g4.data?.error || '').startsWith('入库后超仓库容量'), JSON.stringify(g4.data))
  // 成功入库
  const beforeUsed = wh.used
  const beforeAvail = (await api('/api/coll/inventories', { token })).json.data
    .filter(i => i.warehouseId === wh.id && i.commodityId === cm.id && i.status === 'normal').reduce((s, i) => s + i.quantity, 0)
  const { json: inb } = await api('/api/warehouse/inbound', { method: 'POST', token, body: { warehouseId: wh.id, commodityId: cm.id, quantity: 50, remark: '补库' } })
  ok('成功入库', inb.data?.ok === true, JSON.stringify(inb.data))
  ok('生成批次（B..-M..）', /^B\d{6}-M\d{3}$/.test(inb.data?.batch || ''), 'batch=' + inb.data?.batch)
  const { json: wh2 } = await api(`/api/coll/warehouses/${wh.id}`, { token })
  ok('仓库占用增加 50', Math.abs(wh2.data?.used - (beforeUsed + 50)) < 0.01, 'used=' + wh2.data?.used)
  const afterAvail = (await api('/api/coll/inventories', { token })).json.data
    .filter(i => i.warehouseId === wh.id && i.commodityId === cm.id && i.status === 'normal').reduce((s, i) => s + i.quantity, 0)
  ok('可发库存增加 50', Math.abs(afterAvail - (beforeAvail + 50)) < 0.01, 'avail=' + afterAvail)
}

console.log('== 4. 审计落库 ==')
{
  const r = await (await import('node:child_process')).execFileSync('wsl', ['-d', 'Ubuntu-24.04', '--', 'bash', '-lc',
    `mysql -ublms -pblms123456 -h127.0.0.1 blms -N -e "SELECT COUNT(*) FROM op_log WHERE action IN ('磅单更正/复磅','上报异常','受理异常','处置完成','关闭异常单','手工入库') AND result='success';"`],
    { encoding: 'utf8' }).trim()
  ok('辅助域操作已落审计（≥5）', parseInt(r, 10) >= 5, 'count=' + r)
}

console.log('== 汇总 ==')
console.log(`PASS=${pass} FAIL=${fail}`)
process.exit(fail > 0 ? 1 : 0)
