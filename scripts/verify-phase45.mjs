/**
 * 阶段 4/5 验证：合同审批/变更 + 安全/保险 + 财务核销（真实 HTTP，与前端 flow.js 1:1）
 *  - 合同审批：draft→提交→部门通过→公司通过→executing；驳回→回 draft
 *  - 合同变更：改价转审批→两级通过→单价生效；驳回→维持
 *  - 安全：事故登记→结案；培训计划/完成；车辆检查
 *  - 保险：报险→定责→结案（冲减事故损失）
 *  - 财务：趟次应付生成/付款；银行流水登记→自动核销→手动核销（超余额拦截）
 * 运行：node scripts/verify-phase45.mjs
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
// 动态日期（避免硬编码日期过期）：今天 / 今天+1天 / 今天+7天
// 用**本地**日期（与后端 ctx.today()=LocalDate.now() 一致）；用 toISOString()（UTC）会在 00:00–08:00 本地时段取到前一天，导致"培训日期不能早于今天"
const _d = (off = 0) => { const t = new Date(); t.setDate(t.getDate() + off); const p = n => String(n).padStart(2, '0'); return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())}` }
const TODAY = _d(0), TOMORROW = _d(1), NEXTWEEK = _d(7)
const DT_TODAY = TODAY + ' 10:00', DT_TODAY2 = TODAY + ' 11:00', DT_TODAY3 = TODAY + ' 12:00', DT_TODAY4 = TODAY + ' 12:30'

const token = await login('admin')
if (!token) { console.log('登录失败'); process.exit(1) }

console.log('== 1. 合同审批（draft → 部门 → 公司 → executing）==')
{
  const { json } = await api('/api/coll/contracts', { token })
  const draft = json.data.find(x => x.status === 'draft')
  ok('存在草稿合同', !!draft, 'drafts=' + json.data.filter(x => x.status === 'draft').length)
  const id = draft.id
  const oldPrice = num(draft.unitPrice)

  let r = await api(`/api/contract/${id}/submitApproval`, { method: 'POST', token, body: {} })
  ok('提交审批 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
  let c = (await api(`/api/coll/contracts/${id}`, { token })).json.data
  ok('状态→pending', c.status === 'pending', c.status)
  ok('审批链 2 级', Array.isArray(c.approvalChain) && c.approvalChain.length === 2, JSON.stringify(c.approvalChain?.map(s => s.status)))
  ok('首级 pending', c.approvalChain[0].status === 'pending' && c.approvalChain[1].status === 'waiting')

  r = await api(`/api/contract/${id}/approve`, { method: 'POST', token, body: { comment: '同意' } })
  ok('部门审批通过（非末级）', r.json.data.ok === true && r.json.data.final === false, JSON.stringify(r.json.data))
  c = (await api(`/api/coll/contracts/${id}`, { token })).json.data
  ok('二级转 pending', c.approvalChain[1].status === 'pending', JSON.stringify(c.approvalChain.map(s => s.status)))

  r = await api(`/api/contract/${id}/approve`, { method: 'POST', token, body: { comment: '同意' } })
  ok('公司审批通过（末级）', r.json.data.ok === true && r.json.data.final === true, JSON.stringify(r.json.data))
  c = (await api(`/api/coll/contracts/${id}`, { token })).json.data
  ok('状态→executing', c.status === 'executing', c.status)
  ok('approval 记录', !!c.approval && !!c.approval.approver, JSON.stringify(c.approval))

  // 驳回路径：另取一个草稿
  const { json: cj } = await api('/api/coll/contracts', { token })
  const draft2 = cj.data.find(x => x.status === 'draft' && x.id !== id)
  if (draft2) {
    await api(`/api/contract/${draft2.id}/submitApproval`, { method: 'POST', token, body: {} })
    r = await api(`/api/contract/${draft2.id}/reject`, { method: 'POST', token, body: { reason: '金额偏高' } })
    ok('驳回 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
    const c2 = (await api(`/api/coll/contracts/${draft2.id}`, { token })).json.data
    ok('驳回→回 draft', c2.status === 'draft', c2.status)
    ok('后续层级取消', c2.approvalChain[1].status === 'cancelled', JSON.stringify(c2.approvalChain.map(s => s.status)))
  }
}

console.log('== 2. 合同变更（改价审批）==')
{
  const { json } = await api('/api/coll/contracts', { token })
  const c = json.data.find(x => x.status === 'executing')
  const id = c.id
  const oldPrice = num(c.unitPrice)
  const newPrice = oldPrice + 5

  let r = await api(`/api/contract/${id}/change`, { method: 'POST', token, body: { fields: { unitPrice: newPrice }, reason: '市场调价' } })
  ok('改价转审批（pending）', r.json.data.pending === true && r.json.data.changed === false, JSON.stringify(r.json.data))
  let cc = (await api(`/api/coll/contracts/${id}`, { token })).json.data
  ok('pendingChange 生成', !!cc.pendingChange && Array.isArray(cc.pendingChange.chain), JSON.stringify(cc.pendingChange?.chain?.map(s => s.status)))
  ok('单价未即时变', num(cc.unitPrice) === oldPrice, cc.unitPrice + ' vs ' + oldPrice)

  r = await api(`/api/contract/${id}/approveChange`, { method: 'POST', token, body: { comment: '同意' } })
  ok('改价部门通过（非末级）', r.json.data.ok === true && r.json.data.final === false, JSON.stringify(r.json.data))
  r = await api(`/api/contract/${id}/approveChange`, { method: 'POST', token, body: { comment: '同意' } })
  ok('改价公司通过（末级）', r.json.data.ok === true && r.json.data.final === true, JSON.stringify(r.json.data))
  cc = (await api(`/api/coll/contracts/${id}`, { token })).json.data
  ok('单价生效', num(cc.unitPrice) === newPrice, cc.unitPrice + ' vs ' + newPrice)
  ok('pendingChange 清空', cc.pendingChange == null, JSON.stringify(cc.pendingChange))
  ok('变更历史', Array.isArray(cc.changes) && cc.changes.length > 0, 'changes=' + (cc.changes?.length || 0))

  // 驳回路径
  const { json: cj } = await api('/api/coll/contracts', { token })
  const c2 = cj.data.find(x => x.status === 'executing' && x.id !== id)
  if (c2) {
    const p2 = num(c2.unitPrice)
    await api(`/api/contract/${c2.id}/change`, { method: 'POST', token, body: { fields: { unitPrice: p2 + 10 }, reason: '试改价' } })
    r = await api(`/api/contract/${c2.id}/rejectChange`, { method: 'POST', token, body: { reason: '不合理' } })
    ok('改价驳回 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
    const cc2 = (await api(`/api/coll/contracts/${c2.id}`, { token })).json.data
    ok('驳回单价维持', num(cc2.unitPrice) === p2, cc2.unitPrice + ' vs ' + p2)
    ok('驳回 pendingChange 清空', cc2.pendingChange == null)
  }
}

console.log('== 3. 安全域（事故/培训/检查）==')
{
  const { json: veh } = await api('/api/coll/vehicles', { token })
  const v = veh.data[0]
  let r = await api('/api/safety/accident', { method: 'POST', token, body: { time: DT_TODAY, type: '货物撒漏', level: '一般', vehicleId: v.id, location: 'G4 高速 K120', description: '侧翻撒漏', loss: 15000 } })
  ok('事故登记 ok', r.json.data.id != null, JSON.stringify(r.json.data))
  const accId = r.json.data.id
  const acc = (await api(`/api/coll/accidents/${accId}`, { token })).json.data
  ok('事故 status=handling', acc.status === 'handling', acc.status)
  ok('事故 loss=15000', num(acc.loss) === 15000, acc.loss)

  // 培训
  r = await api('/api/safety/training', { method: 'POST', token, body: { title: '安全驾驶培训', date: TODAY, trainer: '安全科' } })
  ok('培训计划 ok', r.json.data.id != null, JSON.stringify(r.json.data))
  const trId = r.json.data.id
  r = await api(`/api/safety/training/${trId}/complete`, { method: 'POST', token, body: { driverIds: ['D001', 'D002'] } })
  ok('培训完成 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
  const tr = (await api(`/api/coll/trainings/${trId}`, { token })).json.data
  ok('培训 status=completed', tr.status === 'completed', tr.status)
  ok('培训 participants=2', num(tr.participants) === 2, tr.participants)

  // 车辆检查
  r = await api('/api/safety/inspection', { method: 'POST', token, body: { vehicleId: v.id, date: TODAY, item: '制动系统', result: 'pass', inspector: '王工' } })
  ok('车辆检查 ok', r.json.data.id != null, JSON.stringify(r.json.data))
  ok('检查 result=pass', r.json.data.result === 'pass', r.json.data.result)

  // 事故结案
  r = await api(`/api/safety/accident/${accId}/close`, { method: 'POST', token, body: {} })
  ok('事故结案 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
  const acc2 = (await api(`/api/coll/accidents/${accId}`, { token })).json.data
  ok('事故 status=closed', acc2.status === 'closed', acc2.status)
}

console.log('== 4. 保险域（报险→定责→结案）==')
{
  const { json: accs } = await api('/api/coll/accidents', { token })
  const { json: ins0 } = await api('/api/coll/insurance', { token })
  const claimed = new Set(ins0.data.map(x => x.accidentId))
  const acc = accs.data.find(x => x.status === 'handling' && !claimed.has(x.id))
  ok('存在未报险的处理中事故', !!acc, 'handling=' + accs.data.filter(x => x.status === 'handling').length + ' claimed=' + claimed.size)
  const accId = acc.id
  const oldRecovered = num(acc.insuranceRecovered)

  let r = await api('/api/insurance/claim', { method: 'POST', token, body: { accidentId: accId, insurer: '人保财险', reportedAmount: 20000 } })
  ok('报险 ok', r.json.data.ok === true && r.json.data.id != null, JSON.stringify(r.json.data))
  const claimId = r.json.data.id
  const claim = (await api(`/api/coll/insurance/${claimId}`, { token })).json.data
  ok('理赔 status=reported', claim.status === 'reported', claim.status)
  ok('事故挂 insuranceId', (await api(`/api/coll/accidents/${accId}`, { token })).json.data.insuranceId === claimId)

  // 重复报险拦截
  r = await api('/api/insurance/claim', { method: 'POST', token, body: { accidentId: accId } })
  ok('重复报险拦截', r.json.data.error != null, JSON.stringify(r.json.data))

  r = await api(`/api/insurance/claim/${claimId}/assess`, { method: 'POST', token, body: { responsibility: '对方全责', responsibilityParty: '对方车辆', assessedAmount: 18000 } })
  ok('定责 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
  const c2 = (await api(`/api/coll/insurance/${claimId}`, { token })).json.data
  ok('理赔 status=assessed', c2.status === 'assessed', c2.status)
  ok('assessedAmount=18000', num(c2.assessedAmount) === 18000, c2.assessedAmount)

  r = await api(`/api/insurance/claim/${claimId}/settle`, { method: 'POST', token, body: { settledAmount: 18000 } })
  ok('理赔结案 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
  const c3 = (await api(`/api/coll/insurance/${claimId}`, { token })).json.data
  ok('理赔 status=settled', c3.status === 'settled', c3.status)
  ok('事故 insuranceRecovered +18000', num((await api(`/api/coll/accidents/${accId}`, { token })).json.data.insuranceRecovered) === oldRecovered + 18000)
}

console.log('== 5. 财务域（趟次应付 + 银行核销）==')
{
  // 趟次应付
  let r = await api('/api/finance/payables/generate', { method: 'POST', token, body: {} })
  ok('趟次应付生成 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
  const stats1 = (await api('/api/finance/payables/stats', { token })).json.data
  ok('应付统计有数据', stats1.paidCount + stats1.pendingCount > 0, JSON.stringify(stats1))

  const { json: pays } = await api('/api/coll/payables', { token })
  const pendingPay = pays.data.find(x => x.status === 'pending')
  if (pendingPay) {
    r = await api(`/api/finance/payables/${pendingPay.id}/pay`, { method: 'POST', token, body: { method: '银行转账' } })
    ok('趟次应付付款 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
    const p2 = (await api(`/api/coll/payables/${pendingPay.id}`, { token })).json.data
    ok('应付 status=paid', p2.status === 'paid', p2.status)
  } else {
    ok('存在待付应付', false, '无 pending 应付')
  }

  // 银行核销：自造 settled 且未付清的账单（reconciling → customerConfirm → confirmSettle，此时 paid=0）
  const { json: sts } = await api('/api/coll/settlements', { token })
  const recs = sts.data.filter(x => x.status === 'reconciling')
  let settled = null
  for (const rec of recs) {
    await api(`/api/settlement/${rec.id}/customerConfirm`, { method: 'POST', token, body: {} })
    const cs = await api(`/api/settlement/${rec.id}/confirmSettle`, { method: 'POST', token, body: {} })
    if (cs.json.data && cs.json.data.ok === true) {
      settled = (await api(`/api/coll/settlements/${rec.id}`, { token })).json.data
      break
    }
  }
  ok('自造 settled 未付清账单', !!settled && (num(settled.totalAmount) - num(settled.paidAmount)) > 0, 'reconciling=' + recs.length)
  if (settled) {
    const { json: cust } = await api('/api/coll/customers', { token })
    const customer = cust.data.find(x => x.id === settled.customerId)
    const unpaid = num(settled.totalAmount) - num(settled.paidAmount)
    // 自动核销：对手方=客户名，金额=未付余额
    r = await api('/api/finance/bank/statement', { method: 'POST', token, body: { counterparty: customer.name, amount: unpaid, time: DT_TODAY2, summary: '货款' } })
    ok('银行流水登记 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
    const auto = (await api('/api/finance/bank/autoMatch', { method: 'POST', token, body: {} })).json.data
    ok('自动核销命中', Array.isArray(auto) && auto.some(b => b.id === r.json.data.id), JSON.stringify(auto.map(b => b.id)))
    const bank = (await api(`/api/coll/bankRecords/${r.json.data.id}`, { token })).json.data
    ok('流水 status=matched', bank.status === 'matched', bank.status)
    ok('流水挂 settlementId', bank.settlementId === settled.id, bank.settlementId)

    // 手动核销超余额拦截：再自造一个 settled 未付清账单
    const rec2 = recs.find(x => x.id !== settled.id)
    if (rec2) {
      await api(`/api/settlement/${rec2.id}/customerConfirm`, { method: 'POST', token, body: {} })
      const cs2 = await api(`/api/settlement/${rec2.id}/confirmSettle`, { method: 'POST', token, body: {} })
      if (cs2.json.data && cs2.json.data.ok === true) {
        const s2 = (await api(`/api/coll/settlements/${rec2.id}`, { token })).json.data
        const unpaid2 = num(s2.totalAmount) - num(s2.paidAmount)
        const cust2 = cust.data.find(x => x.id === s2.customerId)
        r = await api('/api/finance/bank/statement', { method: 'POST', token, body: { counterparty: cust2.name, amount: unpaid2 + 5000, time: DT_TODAY3 } })
        const overId = r.json.data.id
        r = await api(`/api/finance/bank/${overId}/match`, { method: 'POST', token, body: { settlementId: s2.id } })
        ok('超余额核销拦截', r.json.data.error != null, JSON.stringify(r.json.data))
        const r2 = await api('/api/finance/bank/statement', { method: 'POST', token, body: { counterparty: cust2.name, amount: unpaid2, time: DT_TODAY4 } })
        r = await api(`/api/finance/bank/${r2.json.data.id}/match`, { method: 'POST', token, body: { settlementId: s2.id } })
        ok('手动核销 ok', r.json.data.ok === true, JSON.stringify(r.json.data))
      }
    }
  }
}

console.log('== 汇总 ==')
console.log('PASS=' + pass + ' FAIL=' + fail)
process.exit(fail > 0 ? 1 : 0)
