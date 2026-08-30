# 大宗物流综合管理平台（BulkHaul Server）

大宗物流全链路业务演示系统的**后端服务**（Spring Boot 3）。与前端仓库 `bulkhaul-manage-web` 的 mock 服务层 **1:1 对应**：

| 前端（bulkhaul-manage-web） | 后端（本仓库） |
|---|---|
| `src/mock/flow.js` 的 ~150 个业务函数 | 各 Service 的 API endpoint（函数体逐行平移） |
| `src/permission-table.js` 角色×操作码矩阵 | `RbacService` 内置表 + `sys_role_perm` 数据化权限 |
| `src/mock/base.js` 的 db（37 个集合） | 34 张 `biz_*` 表（JSON payload）+ 34 张 `seed_*` 种子快照表 |
| `scripts/verify-flow.mjs` 556 条断言（环节 1–35） | 验收标准；其中环节 1–12 已 1:1 移植为后端集成测试 |
| `src/mock/scheduler.js` 全局定时任务 | `SchedulerService`（cron 驱动，不依赖页面打开） |
| localStorage 快照 + 单写者 | `DataStore` 内存仓库 + 粗粒度写锁 + `commitAll` 回写 MySQL |

业务闭环覆盖 **合同 → 计划 → 调度 → 在途执行（遥测/围栏）→ 异常 → 结算 → 对账 → 开票 → 收款/核销 → 单证 → 报表**，
外加客户门户、司机端（独立身份守卫）、安全/保险理赔、仓储、消息中心与 RBAC 权限体系。

---

## 技术栈

| 类别 | 选型 |
|---|---|
| 框架 | Spring Boot 3.3.5（Java 17） |
| Web | spring-boot-starter-web + validation |
| 安全 | Spring Security（STATELESS + JWT 过滤器）+ AOP 切面（RBAC 单点校验） |
| 持久化 | MyBatis-Plus 3.5.7（sys_* 关系表）+ JdbcTemplate（biz_* JSON payload 表） |
| 数据库 | MySQL 8（utf8mb4）；Flyway 迁移（V1 建表 / V2 鉴权种子 / V3 业务种子 / V4 种子快照） |
| 缓存 | Redis（spring-data-redis，验证码/登录态辅助） |
| JWT | jjwt 0.12.6（480 分钟 TTL） |
| 测试 | JUnit 5 + @SpringBootTest（独立测试库 blms_test） |

## 快速开始

### 环境要求

- JDK 17、Maven 3.8+
- MySQL 8：库 `blms`（开发）/ `blms_test`（测试），账号 `blms` / `blms123456`（连接参数见 `application.yml`）
- Redis：127.0.0.1:6379

> 本机（Windows）无 JDK 时，在 WSL（Ubuntu）内运行：`scripts/run.sh` 已按
> `/usr/lib/jvm/java-17-openjdk-amd64` + 离线模式（`mvn -o`）配置，cwd 走 `/mnt/d` 路径。

### 启动

```bash
bash scripts/run.sh          # 后台启动（8081），日志 /tmp/bulkhaul-server.log
bash scripts/wait-ready.sh   # 轮询 /api/snapshot 直到就绪（401 即已起，需登录）
curl http://127.0.0.1:8081/api/health
# → {"status":"UP","db":"connected","tables":105}
```

Flyway 首次启动自动执行 V1–V4 建表 + 种子；`blms_test` 库可随时 DROP 重建（测试用，互不污染）。

### 演示账号（统一密码 `123456`，登录需图形验证码）

| 账号 | 角色 | 说明 |
|---|---|---|
| `admin` | 平台管理员 | 全量菜单 + 全量操作 + 全量数据 |
| `user02` | 调度员 | 数据范围限华北（行级权限） |
| `user04` | 结算专员 | 合同/客户/结算/发票/报表/运价表 |
| `user05` | 场站操作员 | 调度执行/磅单/仓储/场站仓库维护 |
| `user06` | 安全管理员 | 异常处置/安全/保险理赔 |
| `customer01` | 客户 | 门户：运输需求、确认/异议对账 |
| `user16` | 只读用户 | 全菜单只读，无任何操作权限 |
| 司机手机号 | 司机 | 司机端独立身份守卫（车次本人） |

登录失败 5 次锁定 5 分钟。

---

## 架构设计

### 分层

```
┌────────────────────────────────────────────────────────────────┐
│ Controller 层  13 个 Controller（/api/**，~120 endpoint）     │
│   薄适配：参数绑定 → Service；ApiResult 统一包装               │
├────────────────────────────────────────────────────────────────┤
│ Service 层  Contract/Dispatch/Settlement/Finance/Weighing/    │
│   Warehouse/Exception/Safety/Insurance/Report/Dashboard/      │
│   Scheduler/Admin/UserAdmin/RateCard/Recalc/Auth             │
│   业务函数体与前端 flow.js 同名同构（1:1 平移）；              │
│   @RequireAction 标注 → 切面 RBAC 单点校验 + 审计留痕         │
├────────────────────────────────────────────────────────────────┤
│ FlowCtx  共享上下文 + 确定性算法（等价 flow.js 模块级状态）    │
│   操作人 Operator.current()（JWT）/ 时间 / genId / 结算费率 /  │
│   磅差/质量扣重等确定性函数逐行翻译，不依赖随机序列            │
├────────────────────────────────────────────────────────────────┤
│ DataStore  内存数据仓库（等价前端 reactive db）                │
│   34 集合全量载入内存（Map 对象，与前端数组元素同构）；        │
│   ReentrantLock 单写者语义；写操作结束 commitAll() 回写       │
│   biz_*（整条记录 JSON payload）；种子基线优先从 seed_* 加载   │
└────────────────────────────────────────────────────────────────┘
```

### 目录结构

```
src/main/java/com/blms/
├── BulkhaulServerApplication.java   # 入口（@EnableScheduling + @MapperScan）
├── auth/        # JWT 过滤器/服务、SecurityConfig、CaptchaService、AuthService、
│               # RbacService（权限判定）、RequireAction + 切面（RBAC 单点校验）、Operator
├── common/      # ApiResult、GlobalExceptionHandler、ForbiddenException、AuditLog（op_log）、
│               # HealthController、SnapshotController（快照/reset-demo）、CollReadController
├── domain/      # entity/mapper（sys_user 等关系表，MyBatis-Plus）
├── service/     # 业务服务（contract/dispatch/settlement/finance/weighing/warehouse/
│               # exception/safety/insurance/report/scheduler/admin）
├── store/       # DataStore（内存仓库 + commitAll + seed 基线）、FlowCtx（共享上下文）
└── resources/
    ├── application.yml              # 8081 / blms 库 / Redis / JWT / scheduler 开关
    └── db/migration/                # Flyway：V1 建表 / V2 鉴权种子 / V3 业务种子 / V4 种子快照
src/test/
├── java/com/blms/FlowIntegrationTest.java   # 环节 1–12 集成测试（1:1 移植前端断言）
└── resources/application-test.yml           # 测试库 blms_test
scripts/       # run.sh / compile.sh / wait-ready.sh（WSL 内运行）
```

### 关键设计决策

1. **内存仓库 + 回写（1:1 复现前端行为）**
   前端 flow.js 的 150 个函数全部操作同一个内存 db；后端把 34 个集合全量载入内存，
   业务逻辑对 Map 操作（与前端对数组同构），写操作结束 `commitAll()` 回写 MySQL
   （`biz_*` 表，整条记录 JSON payload）。粗粒度写锁（ReentrantLock）保证"单写者"语义——
   前端 localStorage 单写者 + version 乐观锁的等价前提。读操作无锁（内存读线程安全）。

2. **种子治理（biz_* 可写 / seed_* 只读）**
   业务写操作只回写 `biz_*`；`V4__seed_snapshot.sql` 把种子态固化到只读 `seed_*` 表（34 张）。
   `DataStore` 启动时种子基线**优先从 seed_* 加载**（不受 commitAll 污染影响），seed_* 缺失时
   回退内存捕获（旧库兼容）。`POST /api/admin/reset-demo` 把内存仓库重置回种子基线（仅内存，不回写 DB），
   供演示/E2E 跨场景恢复种子前置数据。改种子必须同步 V3（biz_*）+ V4（seed_*）两份 SQL 并重放。

3. **RBAC 四层权限，默认拒绝（与前端同口径）**
   - 菜单级：`rolePerms[角色].menus` → 内置表 `ROLE_MENUS` → 默认拒绝；
   - 按钮级：`rolePerms[角色].actions` → 内置表 `ROLE_ACTIONS` → 默认拒绝
     （actions: null=全放行 / []=全拒绝 / [..]=列表匹配；权限表带 5 分钟内存缓存）；
   - **服务层单点校验**：`@RequireAction` 切面在每个写操作入口按操作人角色校验，
     无权限 → 审计失败日志 + 403（前端按钮权限仅为体验层，绕过前端也无法越权）；
   - 行级数据权限：`dataScopes` 按装货侧场站区域过滤列表（如调度员仅见华北）。
   - 司机端独立身份守卫：司机操作走"车次本人司机"校验，不做 PC 端 RBAC（等价司机 App 独立鉴权）。

4. **审计日志（op_log）**
   写操作实时落库（含失败记录）：操作人/模块/动作/详情/IP/结果；ID 前缀 + 5 位序列不复用
   （每进程内存自增 + 启动取库内最大值）；超 1000 条裁剪最旧（与前端一致）。

5. **确定性算法平移**
   断言均为区间/关系断言（非精确随机值）：运行时随机用 ThreadLocalRandom；
   确定性算法（tareOf / loadVarianceOf / genId / calcSettlementFees / 质量扣重费率等）逐行翻译，
   不依赖随机序列——保证前后端对同一输入产生同一结算/磅单结果。

6. **调度单状态机（执行核心，与前端一致）**

   ```
   pending 待装货 → loading 装货中 → intransit 在途 → unloading 卸货中 → completed 已完成
        │               │                │  ▲
        │  任意执行态可上报异常 ↓          │  │ 异常关闭后恢复运输（exceptionFrom 回原态）
        └──────────────→ exception 异常 ─┘
   ```

   公路车次占车辆/司机、进出场站过磅；铁路/水运/管道按运输单元执行，不占车辆、无公路磅单。
   装货前可取消（释放占用）或改派（换车/换司机，目标须空闲/证照未过期/无其他未完结车次）。
   联动回卷：调度单状态变化 → 回卷计划进度 → 回卷合同进度；仓储联动：确认装货按入库时间
   FIFO 跨批次出库（可发库存不足拦截装货），确认卸货按出磅净重入库生成新批次。

---

## API 概览（/api/**，除 health/captcha/login 外均需 JWT）

| 模块 | 前缀 | 主要 endpoint |
|---|---|---|
| 健康/快照 | `/api/health`、`/api/snapshot`、`/api/logs`、`/api/admin/reset-demo` | 健康检查；34 集合全量快照（前端 hydrate 用）；审计日志；重置种子态 |
| 鉴权 | `/api/auth` | `captcha`（图形验证码）/ `login`（账密+验证码，5 次失败锁定）/ `me` |
| 集合读取 | `/api/coll/{name}`、`/api/coll/{name}/{id}` | 34 集合通用只读（列表/详情） |
| 合同/计划 | `/api/contract` | 合同 CRUD + 审批流（提交/批准/驳回/变更两级审批/延期/终止/归档）；计划生成/取消；信用校验；运输需求（门户发起 → 转合同/驳回） |
| 调度 | `/api/dispatch` | `create`（拆车派单，两阶段资源提交）/ `confirmLoad` / `accept` / `depart` / `arrive` / `confirmUnload` / `cancel` / `reassign` / `reportException` / `resume`；司机端 `driver/depart`、`driver/arrive`、`driver/signReceipt`、`scan/load`、`scan/unload`（扫码）；`supplementReceipt`（补签）；`codes`（扫码凭证） |
| 结算 | `/api/settlement` | `candidates` / `generate` / `startReconcile` / `recalc` / `customerConfirm` / `customerObjection` / `confirmSettle` / `recordPayment` / `revertPayment` / `applyPrepayment`（预付款抵扣）/ `dunning`（催收）/ `issueInvoice` / `invoice/{id}/redFlush`（红冲）/ `prepayment/collect` |
| 财务 | `/api/finance` | 应付（generate/pay/stats）；银行流水（statement 录入 / match 手动核销 / autoMatch 精确匹配） |
| 磅单 | `/api/weighing` | `manual`（手工补磅）/ `{id}/correct`（磅差更正，联动重算） |
| 仓储 | `/api/warehouse` | `inbound`（手工入库/补库）/ `inventoryAlerts`（安全库存预警）/ `safetyStock`（设置）/ `inventory/{id}/status`（批次状态） |
| 异常 | `/api/exception` | `{id}/accept`（受理）/ `finish`（定损）/ `close`（关闭 → 调度单恢复） |
| 安全 | `/api/safety` | 事故登记/关闭、安全培训/完成、隐患排查 |
| 保险 | `/api/insurance` | 理赔申请/定损/赔付/驳回 |
| 系统管理 | `/api/admin` | 商品/客户/场站/仓库/司机/车辆（新增/编辑/启停/导入）；用户（CRUD/启停/重置密码）；角色（CRUD/权限编辑）；数据范围；消息中心（未读/已读/免打扰）；运价表（CRUD/启停）；`recalc`（全量重算） |
| 定时任务 | `/api/scheduler/tick` | 手动驱动单轮 tick（遥测推进 → 围栏检查 → 逾期重算 → 异常升级 → 合同审批升级） |

## 定时任务

`SchedulerService` 与前端 `scheduler.js` 的 `runSchedulerTick` 1:1，由 cron 驱动（不依赖页面打开）。
单轮：`advanceTelemetry`（在途进度/车速）→ `checkFenceEvents`（轨迹偏离/超 ETA 自动写异常单）
→ `recalcOverdueAll`（账单逾期）→ `escalatePendingExceptions`（异常升级）→ `escalateContractApprovals`（合同审批超时升级）。
系统任务不做登录用户权限校验（与前端"系统事件走内部核心"一致）。

`blms.scheduler.auto-enabled` 默认 **false**（验证/演示环境确定性运行，避免后台任务干扰端到端断言）；
前端联调时由前端轮询 `POST /api/scheduler/tick`（E2E 由 node 侧手动驱动，确定性等价全局定时任务）。
需要后台自动心跳时：`--blms.scheduler.auto-enabled=true`。

## 测试

```bash
mvn test        # WSL 内：JDK17 + 测试库 blms_test（Flyway V1–V4 重建种子）
```

- `FlowIntegrationTest`：环节 1–12 集成测试，与前端 `verify-flow.mjs` 环节 1–12 的断言 **1:1 移植**
  （@SpringBootTest 完整 context，DataStore 从 blms_test 加载 34 集合种子；
  @TestInstance(PER_CLASS) + @Order 按序执行共享内存态，模拟前端单进程顺序流；
  operator 经 SecurityContextHolder 注入，RBAC 单点校验生效；check() 计数，@AfterAll 汇总）。
- 测试库 `blms_test` 独立于开发库 `blms`，Flyway 重建种子互不污染；
  若迁移校验和失配（如 V3 文件被修改后重放），DROP 重建 blms_test 即可。
- 前端侧验收基线（bulkhaul-manage-web）：`npm test` 556 断言（环节 1–35）**全绿** + UI E2E 82 断言（19 组场景）**全绿**，
  其中 UI E2E 直连本服务（8081）跑真实链路。前端 verify-flow 是业务口径的权威验收标准。

### 当前状态（2026-08-30 实测）

后端集成测试（环节 1–12）当前 **137 通过 / 4 失败**——4 条为 1:1 移植偏差（前端同环节为绿，后端实现待对齐），
非环境/种子问题（context 正常加载、Flyway V1–V4 迁移成功、blms_test 种子重建）：

| 失败断言 | 位置 | 说明 |
|---|---|---|
| 守卫：在途车次不可确认装货/重复发车 | 环节 8 | 状态机守卫：intransit 车次 confirmLoad/depart 应被拦截且状态不变 |
| 异常关闭补扣：已入账单损失扣减 + 调整记录 + 防重复标记 | 环节 9 | finishException→closeException 后已生成账单应补扣损失 + 写调整记录 + settleApplied 防重复 |
| 重算与补扣结果一致（幂等，不重复扣减） | 环节 9 | 补扣后 recalcSettlement 应 delta=0（幂等，不重复扣减） |
| 装/卸货码确定性派生（同单同码、异单异码、格式 ZD/XD+6 位） | 环节 9 | 扫码凭证码确定性派生（同单同码/异单异码/格式） |

> 这 4 条是后端移植的**待整改项**（对应前端 verify-flow 同环节已绿）。修复后 `mvn test` 应全绿，
> 与前端 556 断言验收标准对齐。整改记录见前端仓库 `docs/fix-plan.md`（后续轮次）。

## 已知取舍（演示系统层面，与前端 fix-plan F8 一致）

1. **内存仓库 + 单写者锁**：为 1:1 复现前端行为而设计，非高并发方案；写路径粗粒度锁，
   读路径无锁（内存读线程安全）。走向真实系统应改为按实体行级锁 + 关系表字段级更新。
2. **biz_* JSON payload 存储**：整条记录 JSON 回写，便于与前端结构同构；真实系统应拆关系表字段。
3. **异常损失单一金额字段**：finishException 的 cost 不区分货损/车损/维修费；演示用单字段+备注。
4. **银行自动核销仅精确匹配**：一笔流水只核销一张账单、容差 0.01 元；真实"一笔付多账/含手续费"落人工核销。
5. **JWT 演示密钥**：`application.yml` 内置密钥，生产必须外置。
