package com.blms.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存数据仓库（等价前端 mock 的共享 reactive db）。
 *
 * 设计：前端 flow.js 的 150 个函数全部操作同一个内存 db 对象（扁平对象数组 + 嵌套结构）。
 * 后端为 1:1 复现其行为，把 37 个集合全量载入内存，业务逻辑对 Map 对象操作（与前端对数组操作同构），
 * 写操作结束后 commitAll() 把内存态回写 MySQL（biz_* 表，整条记录 JSON payload）。
 *
 * 一致性：粗粒度写锁（ReentrantLock）保证"单写者"语义——前端 localStorage 单写者 + version 乐观锁
 * 的等价前提。读操作无锁（内存读线程安全）。
 */
@Component
public class DataStore {

    /** 数组型集合（coll → 记录列表） */
    private final Map<String, List<Map<String, Object>>> lists = new HashMap<>();
    /** 对象型集合（coll → 单个对象） */
    private final Map<String, Map<String, Object>> objects = new HashMap<>();
    /** 启动时捕获的种子态深拷贝（resetToSeed 用，供测试/演示把业务数据仓库重置回种子态） */
    private final Map<String, List<Map<String, Object>>> seedLists = new HashMap<>();
    private final Map<String, Map<String, Object>> seedObjects = new HashMap<>();

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();
    private final ReentrantLock writeLock = new ReentrantLock();

    public DataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 数组型集合清单（与 gen-biz-seed.mjs 的 COLL 一致） */
    public static final List<String> LIST_COLLS = List.of(
            "commodities", "customers", "terminals", "vehicles", "drivers", "contracts", "transportRequests",
            "plans", "dispatches", "weighings", "warehouses", "inventories", "settlements", "payments",
            "prepayments", "payables", "dunnings", "bankRecords", "invoices", "messages", "exceptions",
            "accidents", "trainings", "inspections", "rateCards", "insurance", "safetyStocks", "users", "roles");
    /** 对象型集合清单（与 gen-biz-seed.mjs 的 OBJ 一致） */
    public static final List<String> OBJ_COLLS = List.of(
            "rolePerms", "fenceConfig", "escalateConfig", "dnd", "dataScopes");

    @PostConstruct
    public void load() {
        for (String coll : LIST_COLLS) {
            List<Map<String, Object>> rows = new ArrayList<>();
            jdbc.query("SELECT payload FROM biz_" + coll, rs -> {
                try {
                    rows.add(om.readValue(rs.getString(1), new TypeReference<Map<String, Object>>() {}));
                } catch (Exception e) {
                    throw new RuntimeException("解析 " + coll + " payload 失败", e);
                }
            });
            lists.put(coll, rows);
        }
        for (String coll : OBJ_COLLS) {
            jdbc.query("SELECT payload FROM biz_" + coll + " WHERE id = ?", rs -> {
                try {
                    objects.put(coll, om.readValue(rs.getString(1), new TypeReference<Map<String, Object>>() {}));
                } catch (Exception e) {
                    throw new RuntimeException("解析 " + coll + " payload 失败", e);
                }
            }, coll);
            objects.putIfAbsent(coll, new LinkedHashMap<>());
        }
        if (!tryLoadSeedFromSnapshot()) {
            captureSeed();
        }
    }

    /**
     * 从只读种子快照表（seed_*，V4__seed_snapshot.sql）加载种子基线。
     * 快照固化了 V3 种子态，不受 commitAll 回写 biz_* 的污染影响——
     * 历史教训：autoMatchBank 演示写入收款后 commitAll 覆盖 biz_settlements/biz_payments，
     * 重启后内存捕获捕获到污染态，reset-demo 无法恢复种子，UI E2E 自动核销永久失败。
     * @return true 快照表存在且加载成功；false 快照表缺失（旧库未跑 V4），调用方回退内存捕获
     */
    @SuppressWarnings("unchecked")
    private boolean tryLoadSeedFromSnapshot() {
        try {
            seedLists.clear();
            seedObjects.clear();
            for (String coll : LIST_COLLS) {
                List<Map<String, Object>> rows = new ArrayList<>();
                jdbc.query("SELECT payload FROM seed_" + coll, rs -> {
                    try {
                        rows.add(om.readValue(rs.getString(1), new TypeReference<Map<String, Object>>() {}));
                    } catch (Exception e) {
                        throw new RuntimeException("解析 seed_" + coll + " payload 失败", e);
                    }
                });
                seedLists.put(coll, rows);
            }
            for (String coll : OBJ_COLLS) {
                jdbc.query("SELECT payload FROM seed_" + coll + " WHERE id = ?", rs -> {
                    try {
                        seedObjects.put(coll, om.readValue(rs.getString(1), new TypeReference<Map<String, Object>>() {}));
                    } catch (Exception e) {
                        throw new RuntimeException("解析 seed_" + coll + " payload 失败", e);
                    }
                }, coll);
                seedObjects.putIfAbsent(coll, new LinkedHashMap<>());
            }
            return true;
        } catch (Exception e) {
            return false; // seed_* 表不存在（旧库）→ 回退内存捕获，保持兼容
        }
    }

    /** 深拷贝当前内存态为种子基线（启动 load() 后调用一次，之后业务写操作不影响该基线） */
    @SuppressWarnings("unchecked")
    private void captureSeed() {
        seedLists.clear();
        seedObjects.clear();
        for (String coll : LIST_COLLS) {
            List<Map<String, Object>> copy = new ArrayList<>();
            for (Map<String, Object> r : lists.get(coll)) copy.add(new LinkedHashMap<>(r));
            seedLists.put(coll, copy);
        }
        for (String coll : OBJ_COLLS) {
            seedObjects.put(coll, new LinkedHashMap<>(objects.get(coll)));
        }
    }

    /** 把内存数据仓库重置回启动时的种子态（测试/演示用：跨场景恢复种子前置数据，等价旧架构每场景全新种子） */
    public void resetToSeed() {
        writeLock.lock();
        try {
            for (String coll : LIST_COLLS) {
                List<Map<String, Object>> fresh = new ArrayList<>();
                for (Map<String, Object> r : seedLists.get(coll)) fresh.add(new LinkedHashMap<>(r));
                lists.put(coll, fresh);
            }
            for (String coll : OBJ_COLLS) {
                objects.put(coll, new LinkedHashMap<>(seedObjects.get(coll)));
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** 取数组型集合的可变引用（等价前端 db.<coll>） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(String coll) {
        return lists.get(coll);
    }

    /** 取对象型集合的可变引用（等价前端 db.<coll>） */
    public Map<String, Object> obj(String coll) {
        return objects.get(coll);
    }

    /**
     * 回写全部集合（DELETE + 批量 INSERT）。在写锁内调用，与业务改内存互斥。
     * 演示级数据量（<1000 行/集合），全量回写性能可接受且绝对正确。
     */
    public void commitAll() {
        writeLock.lock();
        try {
            for (String coll : LIST_COLLS) {
                List<Map<String, Object>> rows = lists.get(coll);
                jdbc.update("DELETE FROM biz_" + coll);
                if (!rows.isEmpty()) {
                    List<Object[]> batchArgs = new ArrayList<>();
                    for (Map<String, Object> r : rows) {
                        batchArgs.add(new Object[]{ String.valueOf(r.get("id")), om.writeValueAsString(r) });
                    }
                    jdbc.batchUpdate("INSERT INTO biz_" + coll + " (id, payload) VALUES (?, ?)", batchArgs);
                }
                // B1 路 B：核心表派生列（version/region/status/外键）随 payload 同步，供 A1 行级过滤 / B2 分页 / B3 乐观锁走 SQL
                syncDerivedColumns(coll);
            }
            for (String coll : OBJ_COLLS) {
                jdbc.update("DELETE FROM biz_" + coll);
                jdbc.update("INSERT INTO biz_" + coll + " (id, payload) VALUES (?, ?)",
                        coll, om.writeValueAsString(objects.get(coll)));
            }
        } catch (Exception e) {
            throw new RuntimeException("回写数据仓库失败", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * B1 路 B：核心表派生列同步（从 payload 回填 version/region/status/关键外键）。
     * 非核心集合 no-op。region 为派生列（装货侧终端所在数据区域），经终端/合同/调度单 JOIN 回填；
     * 无匹配（外键空/终端缺失）时保持 NULL（= 无区域 → 数据范围可见，与 DataScopeService 防御语义一致）。
     * 调用前提：被引用集合（terminals/contracts/dispatches/settlements）已在本次 commitAll 中先回写（LIST_COLLS 顺序保证）。
     */
    private void syncDerivedColumns(String coll) {
        switch (coll) {
            case "dispatches":
                jdbc.update("UPDATE biz_dispatches SET version=COALESCE(JSON_EXTRACT(payload,'$.version'),1), "
                        + "status=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')), "
                        + "contract_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.contractId')), "
                        + "load_terminal_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.loadTerminalId'))");
                jdbc.update("UPDATE biz_dispatches d JOIN biz_terminals t ON t.id=d.load_terminal_id "
                        + "SET d.region=JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'))");
                break;
            case "contracts":
                jdbc.update("UPDATE biz_contracts SET version=COALESCE(JSON_EXTRACT(payload,'$.version'),1), "
                        + "status=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')), "
                        + "load_terminal_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.loadTerminalId'))");
                jdbc.update("UPDATE biz_contracts c JOIN biz_terminals t ON t.id=c.load_terminal_id "
                        + "SET c.region=JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'))");
                break;
            case "plans":
                jdbc.update("UPDATE biz_plans SET version=COALESCE(JSON_EXTRACT(payload,'$.version'),1), "
                        + "status=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')), "
                        + "contract_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.contractId')), "
                        + "load_terminal_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.loadTerminalId'))");
                jdbc.update("UPDATE biz_plans p JOIN biz_terminals t ON t.id=p.load_terminal_id "
                        + "SET p.region=JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'))");
                break;
            case "transportRequests":
                jdbc.update("UPDATE biz_transportRequests SET version=COALESCE(JSON_EXTRACT(payload,'$.version'),1), "
                        + "status=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')), "
                        + "contract_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.contractId')), "
                        + "load_terminal_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.loadTerminalId'))");
                jdbc.update("UPDATE biz_transportRequests r JOIN biz_terminals t ON t.id=r.load_terminal_id "
                        + "SET r.region=JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'))");
                break;
            case "settlements":
                jdbc.update("UPDATE biz_settlements SET version=COALESCE(JSON_EXTRACT(payload,'$.version'),1), "
                        + "status=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')), "
                        + "contract_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.contractId'))");
                jdbc.update("UPDATE biz_settlements s JOIN biz_contracts c ON c.id=s.contract_id "
                        + "JOIN biz_terminals t ON t.id=c.load_terminal_id "
                        + "SET s.region=JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'))");
                break;
            case "weighings":
                jdbc.update("UPDATE biz_weighings SET version=COALESCE(JSON_EXTRACT(payload,'$.version'),1), "
                        + "dispatch_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.dispatchId'))");
                jdbc.update("UPDATE biz_weighings w JOIN biz_dispatches d ON d.id=w.dispatch_id "
                        + "JOIN biz_terminals t ON t.id=d.load_terminal_id "
                        + "SET w.region=JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'))");
                break;
            case "invoices":
                jdbc.update("UPDATE biz_invoices SET version=COALESCE(JSON_EXTRACT(payload,'$.version'),1), "
                        + "status=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')), "
                        + "settlement_id=JSON_UNQUOTE(JSON_EXTRACT(payload,'$.settlementId'))");
                jdbc.update("UPDATE biz_invoices i JOIN biz_settlements s ON s.id=i.settlement_id "
                        + "JOIN biz_contracts c ON c.id=s.contract_id "
                        + "JOIN biz_terminals t ON t.id=c.load_terminal_id "
                        + "SET i.region=JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'))");
                break;
            default:
                // 非核心集合：无派生列，no-op
        }
    }

    /** 写锁（业务写方法持有，保证单写者） */
    public void lockWrite() {
        writeLock.lock();
    }

    public void unlockWrite() {
        writeLock.unlock();
    }

    /** 重新加载（测试/重置用） */
    public void reload() {
        lists.clear();
        objects.clear();
        load();
    }
}
