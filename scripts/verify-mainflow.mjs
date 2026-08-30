/**
 * 阶段 3 验证：主链路端到端（真实 HTTP，与前端 flow.js 行为 1:1）
 * 链路：选执行中公路合同 → 新建计划 → 下发调度 → 确认装货 → 发车 → 到达 → 确认卸货
 * 验证：状态机流转 / 仓储出库入库 / 进磅出磅 / 质检 / 趟次应付 / 计划回卷 / 审计
 * 运行：node scripts/verify-mainflow.mjs（前置：后端已启动 8081）
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
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  let json = null
  try { json = await res.json() } catch {}
  return { status: res.status, json }
}

async function login(username, password) {
  const { json: cap } = await api('/api/auth/captcha')
  const r = await api('/api/auth/login', { method: 'POST', body: { username, password, captchaId: cap.data.id, captchaCode: cap.data.code } })
  return r.json?.data?.token
}

const token = await login('admin', '123456')
ok('admin 登录', !!token)

console.log('== 1. 种子数据加载（DataStore 与前端同态）==')
{
  const { json } = await api('/api/coll/dispatches', { token })
  ok('调度单已加载（≥100）', json.data.length >= 100, `count=${json.data.length}`)
  const { json: c } = await api('/api/coll/contracts', { token })
  const executing = c.data.filter(x => x.status === 'executing' && (x.mode === '公路' || x.mode === '多式联运'))
  ok('执行中公路合同存在（≥5）', executing.length >= 5, `count=${executing.length}`)
}

console.log('== 2. 新建合同（守卫）==')
{
  const { json } = await api('/api/contract', { method: 'POST', token, body: {} })
  ok('空合同名被拒', json.data?.error === '请输入合同名称', JSON.stringify(json.data))
  // 动态选有效客户（发货方/收货方）、商品、场站
  const { json: cust } = await api('/api/coll/customers', { token })
  const shipper = cust.data.find(c => ['shipper', 'both'].includes(c.type) && c.status !== 'frozen')
  const consignee = cust.data.find(c => ['consignee', 'both'].includes(c.type) && c.status !== 'frozen' && c.id !== shipper?.id)
  const { json: comm } = await api('/api/coll/commodities', { token })
  const commodity = comm.data[0]
  const { json: terms } = await api('/api/coll/terminals', { token })
  const loadT = terms.data.find(t => t.type === '装货') || terms.data[0]
  const unloadT = terms.data.find(t => t.type === '卸货') || terms.data[terms.data.length - 1]
  const { json: c2 } = await api('/api/contract', { method: 'POST', token, body: { name: '阶段3测试合同', shipperId: shipper.id, consigneeId: consignee.id, commodityId: commodity.id, loadTerminalId: loadT.id, unloadTerminalId: unloadT.id, quantity: 1000, unitPrice: 580 } })
  ok('新建合同成功（draft）', c2.data?.ok === true && c2.data.contract.status === 'draft', JSON.stringify(c2.data).slice(0, 120))
  globalThis.__newContract = c2.data.contract
}

console.log('== 3. 合同审批 → 执行中（简化：直接置 executing 验证主链路）==')
// 注：完整审批流（submitContractApproval/approveContract）属阶段 5；此处用种子已有的 executing 合同走主链路
{
  const { json } = await api('/api/coll/contracts', { token })
  const candidates = json.data.filter(x => x.status === 'executing' && (x.mode === '公路' || x.mode === '多式联运'))
  let c = null
  for (const cand of candidates) {
    const { json: rj } = await api(`/api/contract/remaining/${cand.id}`, { token })
    if (rj.data > 0) { c = cand; break }
  }
  ok('选中执行中公路合同（剩余量>0）', !!c, 'candidates=' + candidates.length)
  globalThis.__contract = c
}

console.log('== 4. 新建计划（守卫 + 剩余量校验）==')
let planId
{
  const c = globalThis.__contract
  const { json: remain } = await api(`/api/contract/remaining/${c.id}`, { token })
  ok('合同剩余量 ≥0', remain.data >= 0, `remain=${remain.data}`)
  // 超量守卫
  const { json: over } = await api('/api/plan', { method: 'POST', token, body: { contractId: c.id, quantity: remain.data + 100000 } })
  ok('超剩余量被拒', /超出合同剩余可计划量/.test(over.data?.error || ''), JSON.stringify(over.data))
  // 正常创建
  const qty = Math.min(200, remain.data)
  const { json: p } = await api('/api/plan', { method: 'POST', token, body: { contractId: c.id, quantity: qty } })
  ok('新建计划成功（pending）', p.data?.ok === true && p.data.plan.status === 'pending', JSON.stringify(p.data).slice(0, 120))
  planId = p.data.plan.id
  globalThis.__plan = p.data.plan
}

console.log('== 5. 下发调度（拆车均摊 + 资源占用）==')
let dispatchId
{
  const p = globalThis.__plan
  const count = 2
  const { json } = await api('/api/dispatch/create', { method: 'POST', token, body: { planId, count, vehicleIds: [] } })
  ok('下发 2 张调度单', json.data?.created?.length === 2, JSON.stringify(json.data).slice(0, 150))
  const created = json.data.created
  // 拆车均摊：Σ车次量 = 计划量
  const sum = created.reduce((s, d) => s + d.quantity, 0)
  ok('Σ车次量 = 计划量（F5b）', Math.abs(sum - p.quantity) < 0.01, `sum=${sum} plan=${p.quantity}`)
  // 每张有独立车辆/司机
  const vids = new Set(created.map(d => d.vehicleId))
  ok('车辆互不重复', vids.size === 2, [...vids].join(','))
  ok('调度单初始态 pending', created.every(d => d.status === 'pending'))
  dispatchId = created[0].id
  globalThis.__dispatch = created[0]
  // 计划状态 → dispatched
  const { json: p2 } = await api(`/api/coll/plans/${planId}`, { token })
  ok('计划状态 → dispatched', p2.data.status === 'dispatched', p2.data.status)
}

console.log('== 6. 状态机：装货 → 发车 → 到达 → 卸货 ==')
{
  const d = globalThis.__dispatch
  // 守卫：pending 态不可直接发车
  const { json: g1 } = await api(`/api/dispatch/${d.id}/depart`, { method: 'POST', token })
  ok('守卫：非装货中不可发车', /非"装货中"/.test(g1.data?.error || ''), JSON.stringify(g1.data))

  // 司机接单（公路车次装货前置）
  const { json: acc } = await api(`/api/dispatch/${d.id}/accept`, { method: 'POST', token })
  // 接单走司机端，admin 有 dispatch 权限可代操作
  const { json: d1 } = await api(`/api/coll/dispatches/${d.id}`, { token })
  ok('司机已接单（accepted）', d1.data.accepted === true, `accepted=${d1.data.accepted}`)

  // 确认装货（公路 → 进磅 + 仓储出库）
  const { json: load } = await api(`/api/dispatch/${d.id}/confirmLoad`, { method: 'POST', token })
  ok('确认装货成功', load.data?.ok === true, JSON.stringify(load.data))
  const { json: d2 } = await api(`/api/coll/dispatches/${d.id}`, { token })
  ok('状态 → loading', d2.data.status === 'loading', d2.data.status)
  ok('progress = 5', d2.data.progress === 5)
  // 进磅单生成（净重 = 调度量 × (1±0.5%)）
  const { json: ws } = await api('/api/coll/weighings', { token })
  const inW = ws.data.find(w => w.dispatchId === d.id && w.type === '进磅')
  ok('进磅单生成', !!inW, JSON.stringify(inW).slice(0, 80))
  if (inW) {
    const ratio = inW.net / d2.data.quantity
    ok('进磅净重在 ±0.5% 内', Math.abs(ratio - 1) <= 0.005, `ratio=${ratio}`)
  }

  // 发车
  const { json: dep } = await api(`/api/dispatch/${d.id}/depart`, { method: 'POST', token })
  ok('发车成功', dep.data?.ok === true, JSON.stringify(dep.data))
  const { json: d3 } = await api(`/api/coll/dispatches/${d.id}`, { token })
  ok('状态 → intransit', d3.data.status === 'intransit', d3.data.status)
  ok('speed 40-68', d3.data.speed >= 40 && d3.data.speed <= 68, `speed=${d3.data.speed}`)
  ok('eta 已计算', !!d3.data.eta)
  // 车辆占用
  const { json: v } = await api(`/api/coll/vehicles/${d2.data.vehicleId}`, { token })
  ok('车辆状态 → inuse', v.data.status === 'inuse', v.data.status)

  // 到达
  const { json: arr } = await api(`/api/dispatch/${d.id}/arrive`, { method: 'POST', token })
  ok('到达成功', arr.data?.ok === true)
  const { json: d4 } = await api(`/api/coll/dispatches/${d.id}`, { token })
  ok('状态 → unloading', d4.data.status === 'unloading', d4.data.status)
  ok('progress = 96', d4.data.progress === 96)

  // 确认卸货（出磅 + 质检 + 入库 + 释放 + 趟次应付）
  const { json: un } = await api(`/api/dispatch/${d.id}/confirmUnload`, { method: 'POST', token })
  ok('确认卸货成功', un.data?.ok === true, JSON.stringify(un.data))
  const { json: d5 } = await api(`/api/coll/dispatches/${d.id}`, { token })
  ok('状态 → completed', d5.data.status === 'completed', d5.data.status)
  ok('progress = 100', d5.data.progress === 100)
  // 出磅单（损耗 1-2%）
  const { json: ws2 } = await api('/api/coll/weighings', { token })
  const outW = ws2.data.find(w => w.dispatchId === d.id && w.type === '出磅')
  ok('出磅单生成', !!outW)
  if (inW && outW) {
    const lossRatio = (inW.net - outW.net) / inW.net
    ok('损耗在 1-2% 内', lossRatio >= 0.01 && lossRatio <= 0.02, `loss=${lossRatio}`)
  }
  // 质检记录
  ok('质检记录生成（水分/灰分）', !!d5.data.quality && d5.data.quality.moisture >= 8 && d5.data.quality.moisture <= 14, JSON.stringify(d5.data.quality))
  // 车辆释放
  const { json: v2 } = await api(`/api/coll/vehicles/${d2.data.vehicleId}`, { token })
  ok('车辆状态 → idle（释放）', v2.data.status === 'idle', v2.data.status)
  // 趟次应付生成
  const { json: pay } = await api('/api/coll/payables', { token })
  const p1 = pay.data.find(x => x.dispatchId === d.id)
  ok('趟次应付生成（pending）', !!p1 && p1.status === 'pending', JSON.stringify(p1).slice(0, 80))
  globalThis.__completedDispatch = d5
}

console.log('== 7. 计划回卷 ==')
{
  const p = globalThis.__plan
  const { json: p2 } = await api(`/api/coll/plans/${p.id}`, { token })
  // 2 张调度单完成 1 张 → progress = 完成量/计划量
  ok('计划 progress 已回卷（>0）', p2.data.progress > 0, `progress=${p2.data.progress}`)
  ok('计划状态 intransit（未全完成）', p2.data.status === 'intransit', p2.data.status)
}

console.log('== 8. 审计落库 ==')
{
  const { execFileSync } = await import('node:child_process')
  const q = (sql) => execFileSync('wsl', ['-d', 'Ubuntu-24.04', '--', 'bash', '-lc', `mysql -ublms -pblms123456 -h127.0.0.1 blms -N -e "${sql}"`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  const dispatchLogs = q("SELECT COUNT(*) FROM op_log WHERE module='调度管理' AND result='success'")
  ok('调度操作已落审计（≥3）', Number(dispatchLogs) >= 3, `count=${dispatchLogs}`)
  const whLogs = q("SELECT COUNT(*) FROM op_log WHERE module='仓储管理'")
  ok('仓储联动已落审计（≥1）', Number(whLogs) >= 1, `count=${whLogs}`)
  const loadLogs = q("SELECT COUNT(*) FROM op_log WHERE module='场站管理' AND action='确认装货'")
  ok('确认装货已落审计（≥1）', Number(loadLogs) >= 1, `count=${loadLogs}`)
}

console.log('== 9. 持久化（commitAll 回写 MySQL）==')
{
  const { execFileSync } = await import('node:child_process')
  const q = (sql) => execFileSync('wsl', ['-d', 'Ubuntu-24.04', '--', 'bash', '-lc', `mysql -ublms -pblms123456 -h127.0.0.1 blms -N -e "${sql}"`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  const d = globalThis.__dispatch
  const dbStatus = q(`SELECT JSON_EXTRACT(payload, '$.status') FROM biz_dispatches WHERE id='${d.id}'`)
  ok('调度单状态已回写 MySQL（completed）', dbStatus.includes('completed'), dbStatus)
  const newPlan = q(`SELECT COUNT(*) FROM biz_plans WHERE id='${globalThis.__plan.id}'`)
  ok('新计划已回写 MySQL', Number(newPlan) === 1, `count=${newPlan}`)
}

console.log('== 汇总 ==')
console.log(`PASS=${pass} FAIL=${fail}`)
process.exit(fail > 0 ? 1 : 0)
