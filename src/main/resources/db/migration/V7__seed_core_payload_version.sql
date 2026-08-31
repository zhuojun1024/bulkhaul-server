-- V7 种子快照核心集合 payload 注入 version 字段（Phase 4 重置持久化：演示数据版本 schema 对齐）
-- 背景：seed_* 为 V4 时点快照（固化 V3 种子态），早于 V6 给 biz_* 核心记录注入 payload.version。
-- 缺 version 导致 reset-demo 恢复种子后核心记录无 version → 前端不发 expectedVersion → B3 乐观锁首写不触发。
-- 本迁移把 V6 的 version 注入镜像到 seed_*（缺失才注入，已有不覆盖），使演示数据版本与 biz_* schema 一致。
-- 注意：seed_* 为只读基线，业务写操作（commitAll）只写 biz_*，永不写 seed_*；本迁移是唯一的 seed_* 变更。
SET NAMES utf8mb4;

UPDATE `seed_contracts` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `seed_transportRequests` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `seed_plans` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `seed_dispatches` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `seed_settlements` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `seed_weighings` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
UPDATE `seed_invoices` SET payload = JSON_SET(payload, '$.version', 1) WHERE JSON_EXTRACT(payload, '$.version') IS NULL;
