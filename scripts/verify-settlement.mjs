/**
 * 阶段 3 验证：结算域端到端（真实 HTTP，与前端 flow.js 结算流转 1:1）
 * 链路：candidates → generate → startReconcile → customerConfirm → confirmSettle
 *       → recordPayment → issueInvoice；含环节1 签收硬拦截 + 补签联动
 * 运行：node scripts/verify-settlement.mjs（前置：后端已启动 8081）
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

console.log('== 1. 结算候选（已完成未入账单，按合同+月份聚合）==')
let candidates
{
  const { json } = await api('/api/settlement/candidates', { token })
  candidates = json.data
  ok('存在结算候选（≥1）', candidates.length >= 1, `count=${candidates.length}`)
  if (candidates.length) {
    const c = candidates[0]
    ok('候选含 dispatchCount/quantity/freight', c.dispatchCount >= 1 && c.quantity > 0 && c.freight > 0, JSON.stringify(c).slice(0, 100))
  }
}

console.log('== 2. 生成结算单（守卫 + 车次入账单）==')
let settlementId
{
  // 守卫：空 keys
  const { json: empty } = await api('/api/settlement/generate', { method: 'POST', token, body: { keys: [] } })
  ok('空 keys 不生成', (empty.data?.created || []).length === 0, JSON.stringify(empty.data))
  // 正常生成（取第一个候选）
  const key = candidates[0].key
  const { json } = await api('/api/settlement/generate', { method: 'POST', token, body: { keys: [key] } })
  ok('生成 1 张结算单', json.data?.created?.length === 1, JSON.stringify(json.data).slice(0, 120))
  const s = json.data.created[0]
  settlementId = s.id
  ok('结算单初始态 pending', s.status === 'pending', s.status)
  ok('结算单含 billNo/totalAmount', !!s.billNo && s.totalAmount > 0, `billNo=${s.billNo} total=${s.totalAmount}`)
  ok('结算单含费用明细（freight/lossDeduction）', s.freight > 0 && s.lossDeduction >= 0, JSON.stringify(s).slice(0, 120))
  // 车次已入账单（settled=true + settlementId）
  const { json: ds } = await api('/api/coll/dispatches', { token })
  const marked = ds.data.filter(d => d.settlementId === settlementId)
  ok('车次已标记入账单', marked.length === candidates[0].dispatchCount, `marked=${marked.length} expect=${candidates[0].dispatchCount}`)
  ok('入账单车次 settled=true', marked.every(d => d.settled === true))
  // 重复生成（已入账单的不再出现）
  const { json: c2 } = await api('/api/settlement/candidates', { token })
  ok('已入账单车次不再进候选', !c2.data.some(c => c.key === key), 'still in candidates')
  globalThis.__settlement = s
}

console.log('== 3. 发起对账（三方比对）==')
{
  // 守卫：未对账不可确认结算
  const { json: g1 } = await api(`/api/settlement/${settlementId}/confirmSettle`, { method: 'POST', token })
  ok('守卫：非对账中不可确认结算', /非"对账中"/.test(g1.data?.error || ''), JSON.stringify(g1.data))
  const { json } = await api(`/api/settlement/${settlementId}/startReconcile`, { method: 'POST', token })
  ok('发起对账成功', json.data?.ok === true, JSON.stringify(json.data).slice(0, 100))
  const recon = json.data.reconciliation
  ok('对账含 items/diffCount/lossQty', Array.isArray(recon.items) && recon.diffCount >= 0 && recon.lossQty >= 0, JSON.stringify(recon).slice(0, 120))
  const { json: s2 } = await api(`/api/coll/settlements/${settlementId}`, { token })
  ok('结算单状态 → reconciling', s2.data.status === 'reconciling', s2.data.status)
}

console.log('== 4. 环节1 签收硬拦截 + 补签联动（先客户确认，结算时签收拦截）==')
{
  // 前端 confirmSettle 守卫顺序：先客户确认，再签收拦截。先由客户确认对账。
  const { json: cc } = await api(`/api/settlement/${settlementId}/customerConfirm`, { method: 'POST', token, body: {} })
  ok('客户确认对账成功', cc.data?.ok === true, JSON.stringify(cc.data))
  const { json: s2 } = await api(`/api/coll/settlements/${settlementId}`, { token })
  const recon = s2.data.reconciliation
  const missing = recon.missingReceiptIds || []
  if (missing.length > 0) {
    // 守卫：客户已确认但公路车次未签收 → 拦截结算
    const { json: g1 } = await api(`/api/settlement/${settlementId}/confirmSettle`, { method: 'POST', token, body: {} })
    ok('守卫：未签收公路车次拦截结算', /尚无电子签收单/.test(g1.data?.error || ''), JSON.stringify(g1.data).slice(0, 100))
    // 补签（supplementReceipt 内部 buildReconciliation 重建对账，missingReceiptCount 自动清零）
    for (const did of missing) {
      const { json: sr } = await api(`/api/dispatch/${did}/supplementReceipt`, { method: 'POST', token, body: { signer: '收货方', reason: '漏签补开' } })
      ok(`补签 ${did}`, sr.data?.ok === true, JSON.stringify(sr.data).slice(0, 80))
    }
    const { json: s3 } = await api(`/api/coll/settlements/${settlementId}`, { token })
    ok('补签后 missingReceiptCount=0', s3.data.reconciliation.missingReceiptCount === 0, `count=${s3.data.reconciliation.missingReceiptCount}`)
  } else {
    ok('本账单公路车次均已有签收（无需补签）', true)
  }
}

console.log('== 5. 确认结算（客户已确认 + 签收已补齐）==')
{
  const { json: cs } = await api(`/api/settlement/${settlementId}/confirmSettle`, { method: 'POST', token, body: {} })
  ok('确认结算成功', cs.data?.ok === true, JSON.stringify(cs.data).slice(0, 100))
  const { json: s3 } = await api(`/api/coll/settlements/${settlementId}`, { token })
  ok('结算单状态 → settled', s3.data.status === 'settled', s3.data.status)
  ok('settleDate 已设置', !!s3.data.settleDate)
}

console.log('== 6. 登记收款（超收截断 + 付清）==')
{
  const { json: s3 } = await api(`/api/coll/settlements/${settlementId}`, { token })
  const total = s3.data.totalAmount
  // 部分收款
  const { json: p1 } = await api(`/api/settlement/${settlementId}/recordPayment`, { method: 'POST', token, body: { amount: total * 0.5, method: '银行转账' } })
  ok('部分收款成功', p1.data?.ok === true, JSON.stringify(p1.data))
  const { json: s4 } = await api(`/api/coll/settlements/${settlementId}`, { token })
  ok('paidAmount = 50%', Math.abs(s4.data.paidAmount - total * 0.5) < 1, `paid=${s4.data.paidAmount} total=${total}`)
  // 超收截断（收 10 倍 → 只收未付余额）
  const { json: p2 } = await api(`/api/settlement/${settlementId}/recordPayment`, { method: 'POST', token, body: { amount: total * 10, method: '银行转账' } })
  ok('超收按未付余额截断', p2.data?.ok === true, JSON.stringify(p2.data))
  const { json: s5 } = await api(`/api/coll/settlements/${settlementId}`, { token })
  ok('付清后 paidAmount = total', Math.abs(s5.data.paidAmount - total) < 1, `paid=${s5.data.paidAmount}`)
  // 收款流水
  const { json: pays } = await api('/api/coll/payments', { token })
  const myPays = pays.data.filter(p => p.settlementId === settlementId)
  ok('收款流水 2 笔', myPays.length === 2, `count=${myPays.length}`)
}

console.log('== 7. 开具发票 ==')
{
  const { json } = await api(`/api/settlement/${settlementId}/issueInvoice`, { method: 'POST', token, body: {} })
  ok('开具发票成功', json.data?.ok === true, JSON.stringify(json.data))
  const invNo = json.data.invoiceNo
  ok('发票号 16 位', invNo.length === 16, `invNo=${invNo}`)
  const { json: s6 } = await api(`/api/coll/settlements/${settlementId}`, { token })
  ok('结算单 invoiceStatus → issued', s6.data.invoiceStatus === 'issued', s6.data.invoiceStatus)
  // 重复开票守卫
  const { json: g1 } = await api(`/api/settlement/${settlementId}/issueInvoice`, { method: 'POST', token, body: {} })
  ok('守卫：已开票不可重复开具', /无法重复开具/.test(g1.data?.error || ''), JSON.stringify(g1.data).slice(0, 80))
}

console.log('== 8. 审计落库 ==')
{
  const { execFileSync } = await import('node:child_process')
  const q = (sql) => execFileSync('wsl', ['-d', 'Ubuntu-24.04', '--', 'bash', '-lc', `mysql -ublms -pblms123456 -h127.0.0.1 blms -N -e "${sql}"`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  const settleLogs = q("SELECT COUNT(*) FROM op_log WHERE module='结算管理' AND result='success'")
  ok('结算操作已落审计（≥4）', Number(settleLogs) >= 4, `count=${settleLogs}`)
  const invLogs = q("SELECT COUNT(*) FROM op_log WHERE module='发票管理'")
  ok('发票操作已落审计（≥1）', Number(invLogs) >= 1, `count=${invLogs}`)
}

console.log('== 9. 持久化（commitAll 回写 MySQL）==')
{
  const { execFileSync } = await import('node:child_process')
  const q = (sql) => execFileSync('wsl', ['-d', 'Ubuntu-24.04', '--', 'bash', '-lc', `mysql -ublms -pblms123456 -h127.0.0.1 blms -N -e "${sql}"`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  const dbStatus = q(`SELECT JSON_EXTRACT(payload, '$.status') FROM biz_settlements WHERE id='${settlementId}'`)
  ok('结算单状态已回写 MySQL（settled）', dbStatus.includes('settled'), dbStatus)
  const invCount = q(`SELECT COUNT(*) FROM biz_invoices WHERE JSON_EXTRACT(payload, '$.settlementId')='${settlementId}'`)
  ok('发票已回写 MySQL', Number(invCount) >= 1, `count=${invCount}`)
}

console.log('== 汇总 ==')
console.log(`PASS=${pass} FAIL=${fail}`)
process.exit(fail > 0 ? 1 : 0)
