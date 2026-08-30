// verify-admin.mjs — 新实现的管理后台/客户门户/运价卡端点 smoke test
// 覆盖：主数据 CRUD + 用户/角色/权限/数据范围 + 运价卡 + 运输需求 + 仓储安全库存/批次状态 + 全局校准
const BASE = 'http://127.0.0.1:8081'
let pass = 0, fail = 0
const ok = (name, cond) => { if (cond) { pass++; console.log('  PASS', name) } else { fail++; console.log('  FAIL', name) } }
const j = r => r.json()

async function login(username, password) {
  const cap = await (await fetch(BASE + '/api/auth/captcha')).json()
  const c = cap.data
  const r = await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, captchaId: c.id, captchaCode: c.code })
  })
  const b = await j(r)
  if (!b.ok) throw new Error('login failed ' + username + ': ' + b.error)
  return b.data.token
}
const H = t => ({ 'Content-Type': 'application/json', Authorization: 'Bearer ' + t })
async function post(t, path, body) { const r = await fetch(BASE + path, { method: 'POST', headers: H(t), body: JSON.stringify(body) }); return j(r) }
async function put(t, path, body) { const r = await fetch(BASE + path, { method: 'PUT', headers: H(t), body: JSON.stringify(body) }); return j(r) }
async function del(t, path) { const r = await fetch(BASE + path, { method: 'DELETE', headers: H(t) }); return j(r) }
async function get(t, path) { const r = await fetch(BASE + path, { headers: H(t) }); return j(r) }

async function main() {
  const admin = await login('admin', '123456')

  console.log('--- 主数据 CRUD ---')
  // 商品
  let r = await post(admin, '/api/admin/commodity', { name: '测试精煤', category: '煤炭', density: 1.3, price: 620 })
  ok('新建商品 ok', r.ok && r.data.ok === true && /^CM\d{3}$/.test(r.data.id))
  const cmId = r.data.id
  r = await post(admin, '/api/admin/commodity', { name: '测试精煤' })
  ok('商品重名拦截', r.ok === false || (r.data && r.data.error && r.data.error.includes('已存在')))
  r = await post(admin, '/api/admin/commodity', { id: cmId, name: '测试精煤改', price: 650 })
  ok('编辑商品 ok', r.ok && r.data.ok === true)
  r = await post(admin, '/api/admin/commodity/' + cmId + '/toggle', {})
  ok('停用商品 ok', r.ok && r.data.ok === true)
  r = await post(admin, '/api/admin/commodity/import', [{ name: '导入煤A' }, { name: '导入煤A' }, { name: '' }])
  ok('导入商品 created=1 skipped=1 errors=1', r.ok && r.data.created.length === 1 && r.data.skipped.length === 1 && r.data.errors.length === 1)

  // 客户
  r = await post(admin, '/api/admin/customer/CUS001/toggle', {})
  ok('客户冻结 ok', r.ok && r.data.ok === true)
  r = await post(admin, '/api/admin/customer/CUS001/toggle', {})
  ok('客户解冻 ok', r.ok && r.data.ok === true)
  r = await post(admin, '/api/admin/customer/import', [{ name: '导入客户X', level: 'A' }, { name: '' }])
  ok('导入客户 created=1 errors=1', r.ok && r.data.created.length === 1 && r.data.errors.length === 1)

  // 场站 / 仓库
  r = await post(admin, '/api/admin/terminal', { name: '测试场站', type: 'loading', capacity: 5000, region: '华北' })
  ok('新建场站 ok', r.ok && r.data.ok === true && /^T\d{3}$/.test(r.data.id))
  r = await post(admin, '/api/admin/terminal', { name: '测试场站', capacity: 0 })
  ok('场站日能力<=0 拦截', r.ok === false || (r.data && r.data.error && r.data.error.includes('日能力')))
  r = await post(admin, '/api/admin/warehouse', { name: '测试煤仓', capacity: 10000, type: '煤仓' })
  ok('新建仓库 ok', r.ok && r.data.ok === true && /^WH\d{3}$/.test(r.data.id))
  const whId = r.data.id

  // 司机
  r = await post(admin, '/api/admin/driver', { name: '测试司机', phone: '13900001111', licenseType: 'A2' })
  ok('新建司机 ok', r.ok && r.data.ok === true && /^D\d{3}$/.test(r.data.id))
  const drId = r.data.id
  r = await post(admin, '/api/admin/driver/' + drId + '/toggle', {})
  ok('司机停用 ok', r.ok && r.data.ok === true)
  r = await post(admin, '/api/admin/driver/import', [{ name: '导入司机', phone: '13900002222' }, { name: 'x' }])
  ok('导入司机 created=1 errors=1', r.ok && r.data.created.length === 1 && r.data.errors.length === 1)

  // 车辆
  r = await post(admin, '/api/admin/vehicle/import', [{ plate: '冀A00001', capacity: 35 }, { plate: '冀A00001' }, { plate: '' }])
  ok('导入车辆 created=1 skipped=1 errors=1', r.ok && r.data.created.length === 1 && r.data.skipped.length === 1 && r.data.errors.length === 1)
  // 找一个空闲车辆报修
  const vs = await get(admin, '/api/coll/vehicles')
  const idleV = (vs.data || []).find(v => v.status === 'idle')
  if (idleV) {
    r = await post(admin, '/api/admin/vehicle/' + idleV.id + '/repair', { reason: '轮胎磨损' })
    ok('车辆报修 ok', r.ok && r.data.ok === true)
    r = await post(admin, '/api/admin/vehicle/' + idleV.id + '/resume', {})
    ok('车辆恢复 ok', r.ok && r.data.ok === true)
  } else {
    ok('车辆报修(无空闲车,跳过)', true)
    ok('车辆恢复(无空闲车,跳过)', true)
  }

  console.log('--- 用户/角色/权限/数据范围 ---')
  // 新建用户（双存储：biz_users + sys_user）
  r = await post(admin, '/api/admin/user', { username: 'smoketest01', name: '冒烟测试', role: '调度员', password: 'test123456', phone: '13800000001' })
  ok('新建用户 ok', r.ok && r.data.ok === true && /^U\d{3}$/.test(r.data.id))
  const uId = r.data.id
  // 新用户能用新密码登录（验证 sys_user 同步 + BCrypt）
  let login2 = null
  try { login2 = await login('smoketest01', 'test123456') } catch (e) { login2 = null }
  ok('新用户可登录(双存储同步)', !!login2)
  // 重置密码
  r = await post(admin, '/api/admin/user/' + uId + '/resetPassword', { password: 'newpass999' })
  ok('重置密码 ok', r.ok && r.data.ok === true)
  let login3 = null
  try { login3 = await login('smoketest01', 'newpass999') } catch (e) { login3 = null }
  ok('重置后可用新密码登录', !!login3)
  // 停用
  r = await post(admin, '/api/admin/user/' + uId + '/toggle', { active: false })
  ok('停用用户 ok', r.ok && r.data.ok === true)
  // 删除
  r = await del(admin, '/api/admin/user/' + uId)
  ok('删除用户 ok', r.ok && r.data.ok === true)

  // 角色
  r = await post(admin, '/api/admin/role', { name: '冒烟角色', code: 'smoke', description: '测试' })
  ok('新建角色 ok', r.ok && r.data.ok === true && /^R\d{3}$/.test(r.data.id))
  const roleR = r.data.id
  // 内置角色不可删
  r = await del(admin, '/api/admin/role/R001')
  ok('内置角色不可删', r.ok === false || (r.data && r.data.error && r.data.error.includes('不可删除')))
  // 角色权限更新（同步 sys_role_perm + 失效缓存）
  r = await put(admin, '/api/admin/role/冒烟角色/perms', { menus: ['/workbench'], actions: ['contract'] })
  ok('角色权限更新 ok', r.ok && r.data.ok === true)
  // 删除空角色
  r = await del(admin, '/api/admin/role/' + roleR)
  ok('删除角色 ok', r.ok && r.data.ok === true)

  // 数据范围
  r = await put(admin, '/api/admin/user/user02/dataScope', { regions: ['华北'] })
  ok('设置数据范围 ok', r.ok && r.data.ok === true)
  r = await put(admin, '/api/admin/user/admin/dataScope', { regions: ['华北'] })
  ok('admin 不可设数据范围', r.ok === false || (r.data && r.data.error && r.data.error.includes('管理员')))
  r = await get(admin, '/api/admin/dataScope')
  ok('读数据范围(admin 全量)', r.ok && Array.isArray(r.data.regions) && r.data.regions.length === 0)

  // 免打扰
  r = await put(admin, '/api/admin/dnd', { enabled: true, quietStart: '23:00', quietEnd: '07:00', mutedTypes: ['system'] })
  ok('设置免打扰 ok', r.ok && r.data.ok === true)
  r = await get(admin, '/api/admin/dnd')
  ok('读免打扰', r.ok && r.data.enabled === true && r.data.quietStart === '23:00')

  // 消息
  r = await get(admin, '/api/admin/messages')
  ok('读消息列表', r.ok && Array.isArray(r.data))
  r = await get(admin, '/api/admin/messages/unreadCount')
  ok('未读计数', r.ok && typeof r.data.count === 'number')
  r = await post(admin, '/api/admin/messages/readAll', {})
  ok('全部已读', r.ok && typeof r.data.count === 'number')

  console.log('--- 运价卡 ---')
  r = await post(admin, '/api/admin/rateCard', { commodityId: 'CM001', loadTerminalId: 'T003', unloadTerminalId: 'T009', mode: '公路', unitPrice: 55, effectiveDate: '2026-08-29' })
  const rcCreated = r.ok && r.data.ok === true
  ok('新建运价卡 ok', rcCreated)
  const rcId = rcCreated ? r.data.id : null
  if (rcId) {
    r = await post(admin, '/api/admin/rateCard', { commodityId: 'CM001', loadTerminalId: 'T003', unloadTerminalId: 'T009', mode: '公路', unitPrice: 60 })
    ok('运价卡线路重复拦截', r.ok === false || (r.data && r.data.error && r.data.error.includes('重复')))
    r = await put(admin, '/api/admin/rateCard/' + rcId, { unitPrice: 58 })
    ok('运价卡调价 ok', r.ok && r.data.changed === true && r.data.changes.length === 1)
    r = await put(admin, '/api/admin/rateCard/' + rcId, { unitPrice: 58 })
    ok('运价卡无变更 changed=false', r.ok && r.data.changed === false)
    r = await post(admin, '/api/admin/rateCard/' + rcId + '/toggle', {})
    ok('停用运价卡 ok', r.ok && r.data.status === 'inactive')
  }

  console.log('--- 客户运输需求（客户门户） ---')
  // 用客户账号发起
  const cust = await login('customer01', '123456')
  r = await post(cust, '/api/contract/request', { customerId: 'CUS001', consigneeId: 'CUS002', commodityId: 'CM001', quantity: 5000, loadTerminalId: 'T005', unloadTerminalId: 'T001', mode: '公路', unitPrice: 60 })
  ok('客户发起运输需求 ok', r.ok && r.data.status === 'pending' && /^YS-\d{4}$/.test(r.data.id))
  const reqId = r.data.id
  // 客户不能转合同（无 contract 权限）
  r = await post(cust, '/api/contract/request/' + reqId + '/convert', {})
  ok('客户转合同被 403 拦截', r.ok === false)
  // admin 转合同
  r = await post(admin, '/api/contract/request/' + reqId + '/convert', { quantity: 5000, unitPrice: 60 })
  ok('需求转合同草稿 ok', r.ok && r.data.status === 'draft' && /^HT-\d{4}$/.test(r.data.id) && r.data.source === 'request')
  // 已转换不能再转
  r = await post(admin, '/api/contract/request/' + reqId + '/convert', {})
  ok('已转换需求不可再转', r.ok === false || (r.data && r.data.error && r.data.error.includes('非"待处理"')))
  // 驳回另一条
  r = await post(cust, '/api/contract/request', { customerId: 'CUS001', consigneeId: 'CUS002', commodityId: 'CM001', quantity: 3000, loadTerminalId: 'T005', unloadTerminalId: 'T002', mode: '公路' })
  const reqId2 = r.data.id
  r = await post(admin, '/api/contract/request/' + reqId2 + '/reject', { reason: '运力不足' })
  ok('驳回运输需求 ok', r.ok && r.data.ok === true)

  console.log('--- 仓储安全库存/批次状态 ---')
  r = await post(admin, '/api/warehouse/safetyStock', { warehouseId: whId, commodityId: 'CM001', minQty: 500 })
  ok('设置安全库存 ok', r.ok && r.data.ok === true)
  r = await post(admin, '/api/warehouse/safetyStock', { warehouseId: whId, commodityId: 'CM001', minQty: -5 })
  ok('安全库存负数拦截', r.ok === false || (r.data && r.data.error && r.data.error.includes('不小于 0')))
  // 找一个库存批次改状态
  const invs = await get(admin, '/api/coll/inventories')
  const inv = (invs.data || []).find(i => i.status === 'normal')
  if (inv) {
    r = await post(admin, '/api/warehouse/inventory/' + inv.id + '/status', { status: 'locked' })
    ok('库存批次锁定 ok', r.ok && r.data.ok === true)
    r = await post(admin, '/api/warehouse/inventory/' + inv.id + '/status', { status: 'locked' })
    ok('重复锁定拦截', r.ok === false || (r.data && r.data.error && r.data.error.includes('已处于')))
    r = await post(admin, '/api/warehouse/inventory/' + inv.id + '/status', { status: 'normal' })
    ok('库存批次解锁 ok', r.ok && r.data.ok === true)
  } else {
    ok('库存批次状态(无批次,跳过)', true)
  }

  console.log('--- 全局校准 ---')
  r = await post(admin, '/api/admin/recalc', {})
  ok('全局校准 ok', r.ok && r.data.ok === true && typeof r.data.dispatches === 'number')

  console.log('\nPASS=' + pass + ' FAIL=' + fail)
  process.exit(fail > 0 ? 1 : 0)
}
main().catch(e => { console.error('FATAL', e); process.exit(2) })
