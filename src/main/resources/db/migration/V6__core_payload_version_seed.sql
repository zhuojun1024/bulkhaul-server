-- V6 核心集合 payload 注入 version 字段（B3 乐观锁 端到端）
-- 背景：V5 已给 7 核心表加 version 派生列（默认 1），但 payload JSON 内无 version 字段。
-- 前端经 /api/snapshot 读 payload 的 version 发 expectedVersion；payload 无 version → 前端不发 → B3 不触发。
-- 本迁移给既有核心记录 payload 注入 version=1（缺失才注入，已有不覆盖），与 V5 派生列对齐。
-- 运行时新建记录由 DataStore.ensureCoreVersions 在 commitAll 持久化前兜底注入。
SET NAMES utf8mb4;

UPDATE `biz_contracts` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `biz_transportRequests` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `biz_plans` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `biz_dispatches` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `biz_settlements` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `biz_weighings` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `biz_invoices` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
