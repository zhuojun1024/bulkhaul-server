-- V5 核心表规范化（B1 路 B + B3 乐观锁 基础）
-- 给 7 个核心业务表加真实列（version/region/status/关键外键）+ 索引，
-- 从 payload 回填，供 A1 行级过滤（WHERE region IN）/ B2 分页 / B3 乐观锁（WHERE version=?）走 SQL。
-- 注意：region 为派生列（装货侧终端所在数据区域），非 payload 字段；回填顺序 = 先外键列，后 region。
SET NAMES utf8mb4;

-- ===== 1. 加列（version 默认 1；region/status/外键 可空，派生/回填）=====
ALTER TABLE `biz_dispatches`
  ADD COLUMN `version` INT NOT NULL DEFAULT 1 AFTER `payload`,
  ADD COLUMN `region` VARCHAR(32) NULL AFTER `version`,
  ADD COLUMN `status` VARCHAR(32) NULL AFTER `region`,
  ADD COLUMN `contract_id` VARCHAR(64) NULL AFTER `status`,
  ADD COLUMN `load_terminal_id` VARCHAR(64) NULL AFTER `contract_id`;

ALTER TABLE `biz_contracts`
  ADD COLUMN `version` INT NOT NULL DEFAULT 1 AFTER `payload`,
  ADD COLUMN `region` VARCHAR(32) NULL AFTER `version`,
  ADD COLUMN `status` VARCHAR(32) NULL AFTER `region`,
  ADD COLUMN `load_terminal_id` VARCHAR(64) NULL AFTER `status`;

ALTER TABLE `biz_plans`
  ADD COLUMN `version` INT NOT NULL DEFAULT 1 AFTER `payload`,
  ADD COLUMN `region` VARCHAR(32) NULL AFTER `version`,
  ADD COLUMN `status` VARCHAR(32) NULL AFTER `region`,
  ADD COLUMN `contract_id` VARCHAR(64) NULL AFTER `status`,
  ADD COLUMN `load_terminal_id` VARCHAR(64) NULL AFTER `contract_id`;

ALTER TABLE `biz_transportRequests`
  ADD COLUMN `version` INT NOT NULL DEFAULT 1 AFTER `payload`,
  ADD COLUMN `region` VARCHAR(32) NULL AFTER `version`,
  ADD COLUMN `status` VARCHAR(32) NULL AFTER `region`,
  ADD COLUMN `contract_id` VARCHAR(64) NULL AFTER `status`,
  ADD COLUMN `load_terminal_id` VARCHAR(64) NULL AFTER `contract_id`;

ALTER TABLE `biz_settlements`
  ADD COLUMN `version` INT NOT NULL DEFAULT 1 AFTER `payload`,
  ADD COLUMN `region` VARCHAR(32) NULL AFTER `version`,
  ADD COLUMN `status` VARCHAR(32) NULL AFTER `region`,
  ADD COLUMN `contract_id` VARCHAR(64) NULL AFTER `status`;

ALTER TABLE `biz_weighings`
  ADD COLUMN `version` INT NOT NULL DEFAULT 1 AFTER `payload`,
  ADD COLUMN `region` VARCHAR(32) NULL AFTER `version`,
  ADD COLUMN `dispatch_id` VARCHAR(64) NULL AFTER `region`;

ALTER TABLE `biz_invoices`
  ADD COLUMN `version` INT NOT NULL DEFAULT 1 AFTER `payload`,
  ADD COLUMN `region` VARCHAR(32) NULL AFTER `version`,
  ADD COLUMN `status` VARCHAR(32) NULL AFTER `region`,
  ADD COLUMN `settlement_id` VARCHAR(64) NULL AFTER `status`;

-- ===== 2. 回填 status + 外键列（先于 region）=====
UPDATE `biz_dispatches` SET `status` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')),
  `contract_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.contractId')),
  `load_terminal_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.loadTerminalId'));
UPDATE `biz_contracts` SET `status` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')),
  `load_terminal_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.loadTerminalId'));
UPDATE `biz_plans` SET `status` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')),
  `contract_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.contractId')),
  `load_terminal_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.loadTerminalId'));
UPDATE `biz_transportRequests` SET `status` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')),
  `contract_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.contractId')),
  `load_terminal_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.loadTerminalId'));
UPDATE `biz_settlements` SET `status` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')),
  `contract_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.contractId'));
UPDATE `biz_weighings` SET `dispatch_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.dispatchId'));
UPDATE `biz_invoices` SET `status` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')),
  `settlement_id` = JSON_UNQUOTE(JSON_EXTRACT(payload,'$.settlementId'));

-- ===== 3. 回填 region（派生：装货侧终端所在数据区域；多级 JOIN，外键列已就绪）=====
UPDATE `biz_dispatches` d JOIN `biz_terminals` t ON t.id = d.load_terminal_id
  SET d.region = JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'));
UPDATE `biz_contracts` c JOIN `biz_terminals` t ON t.id = c.load_terminal_id
  SET c.region = JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'));
UPDATE `biz_plans` p JOIN `biz_terminals` t ON t.id = p.load_terminal_id
  SET p.region = JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'));
UPDATE `biz_transportRequests` r JOIN `biz_terminals` t ON t.id = r.load_terminal_id
  SET r.region = JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'));
UPDATE `biz_settlements` s JOIN `biz_contracts` c ON c.id = s.contract_id JOIN `biz_terminals` t ON t.id = c.load_terminal_id
  SET s.region = JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'));
UPDATE `biz_weighings` w JOIN `biz_dispatches` d ON d.id = w.dispatch_id JOIN `biz_terminals` t ON t.id = d.load_terminal_id
  SET w.region = JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'));
UPDATE `biz_invoices` i JOIN `biz_settlements` s ON s.id = i.settlement_id JOIN `biz_contracts` c ON c.id = s.contract_id JOIN `biz_terminals` t ON t.id = c.load_terminal_id
  SET i.region = JSON_UNQUOTE(JSON_EXTRACT(t.payload,'$.region'));

-- ===== 4. 索引（A1 region / B2 status / 外键关联）=====
ALTER TABLE `biz_dispatches` ADD INDEX `idx_d_region` (`region`), ADD INDEX `idx_d_status` (`status`), ADD INDEX `idx_d_contract` (`contract_id`), ADD INDEX `idx_d_loadterm` (`load_terminal_id`);
ALTER TABLE `biz_contracts` ADD INDEX `idx_c_region` (`region`), ADD INDEX `idx_c_status` (`status`), ADD INDEX `idx_c_loadterm` (`load_terminal_id`);
ALTER TABLE `biz_plans` ADD INDEX `idx_p_region` (`region`), ADD INDEX `idx_p_status` (`status`), ADD INDEX `idx_p_contract` (`contract_id`), ADD INDEX `idx_p_loadterm` (`load_terminal_id`);
ALTER TABLE `biz_transportRequests` ADD INDEX `idx_tr_region` (`region`), ADD INDEX `idx_tr_status` (`status`), ADD INDEX `idx_tr_contract` (`contract_id`), ADD INDEX `idx_tr_loadterm` (`load_terminal_id`);
ALTER TABLE `biz_settlements` ADD INDEX `idx_s_region` (`region`), ADD INDEX `idx_s_status` (`status`), ADD INDEX `idx_s_contract` (`contract_id`);
ALTER TABLE `biz_weighings` ADD INDEX `idx_w_region` (`region`), ADD INDEX `idx_w_dispatch` (`dispatch_id`);
ALTER TABLE `biz_invoices` ADD INDEX `idx_i_region` (`region`), ADD INDEX `idx_i_status` (`status`), ADD INDEX `idx_i_settlement` (`settlement_id`);