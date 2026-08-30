-- 种子快照（只读基线）：把 biz_* 的种子态固化到 seed_* 表
-- 用途：reset-demo 从 seed_* 恢复种子态，防止 commitAll 污染 biz_* 后丢失种子
-- 注意：seed_* 仅供 DataStore 读取种子基线，业务写操作（commitAll）只写 biz_*，永不写 seed_*
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `seed_accidents`;
CREATE TABLE `seed_accidents` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_accidents` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_accidents`;

DROP TABLE IF EXISTS `seed_bankRecords`;
CREATE TABLE `seed_bankRecords` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_bankRecords` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_bankRecords`;

DROP TABLE IF EXISTS `seed_commodities`;
CREATE TABLE `seed_commodities` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_commodities` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_commodities`;

DROP TABLE IF EXISTS `seed_contracts`;
CREATE TABLE `seed_contracts` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_contracts` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_contracts`;

DROP TABLE IF EXISTS `seed_customers`;
CREATE TABLE `seed_customers` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_customers` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_customers`;

DROP TABLE IF EXISTS `seed_dataScopes`;
CREATE TABLE `seed_dataScopes` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_dataScopes` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_dataScopes`;

DROP TABLE IF EXISTS `seed_dispatches`;
CREATE TABLE `seed_dispatches` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_dispatches` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_dispatches`;

DROP TABLE IF EXISTS `seed_dnd`;
CREATE TABLE `seed_dnd` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_dnd` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_dnd`;

DROP TABLE IF EXISTS `seed_drivers`;
CREATE TABLE `seed_drivers` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_drivers` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_drivers`;

DROP TABLE IF EXISTS `seed_dunnings`;
CREATE TABLE `seed_dunnings` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_dunnings` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_dunnings`;

DROP TABLE IF EXISTS `seed_escalateConfig`;
CREATE TABLE `seed_escalateConfig` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_escalateConfig` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_escalateConfig`;

DROP TABLE IF EXISTS `seed_exceptions`;
CREATE TABLE `seed_exceptions` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_exceptions` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_exceptions`;

DROP TABLE IF EXISTS `seed_fenceConfig`;
CREATE TABLE `seed_fenceConfig` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_fenceConfig` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_fenceConfig`;

DROP TABLE IF EXISTS `seed_inspections`;
CREATE TABLE `seed_inspections` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_inspections` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_inspections`;

DROP TABLE IF EXISTS `seed_insurance`;
CREATE TABLE `seed_insurance` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_insurance` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_insurance`;

DROP TABLE IF EXISTS `seed_inventories`;
CREATE TABLE `seed_inventories` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_inventories` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_inventories`;

DROP TABLE IF EXISTS `seed_invoices`;
CREATE TABLE `seed_invoices` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_invoices` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_invoices`;

DROP TABLE IF EXISTS `seed_messages`;
CREATE TABLE `seed_messages` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_messages` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_messages`;

DROP TABLE IF EXISTS `seed_payables`;
CREATE TABLE `seed_payables` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_payables` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_payables`;

DROP TABLE IF EXISTS `seed_payments`;
CREATE TABLE `seed_payments` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_payments` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_payments`;

DROP TABLE IF EXISTS `seed_plans`;
CREATE TABLE `seed_plans` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_plans` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_plans`;

DROP TABLE IF EXISTS `seed_prepayments`;
CREATE TABLE `seed_prepayments` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_prepayments` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_prepayments`;

DROP TABLE IF EXISTS `seed_rateCards`;
CREATE TABLE `seed_rateCards` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_rateCards` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_rateCards`;

DROP TABLE IF EXISTS `seed_rolePerms`;
CREATE TABLE `seed_rolePerms` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_rolePerms` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_rolePerms`;

DROP TABLE IF EXISTS `seed_roles`;
CREATE TABLE `seed_roles` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_roles` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_roles`;

DROP TABLE IF EXISTS `seed_safetyStocks`;
CREATE TABLE `seed_safetyStocks` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_safetyStocks` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_safetyStocks`;

DROP TABLE IF EXISTS `seed_settlements`;
CREATE TABLE `seed_settlements` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_settlements` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_settlements`;

DROP TABLE IF EXISTS `seed_terminals`;
CREATE TABLE `seed_terminals` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_terminals` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_terminals`;

DROP TABLE IF EXISTS `seed_trainings`;
CREATE TABLE `seed_trainings` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_trainings` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_trainings`;

DROP TABLE IF EXISTS `seed_transportRequests`;
CREATE TABLE `seed_transportRequests` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_transportRequests` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_transportRequests`;

DROP TABLE IF EXISTS `seed_users`;
CREATE TABLE `seed_users` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_users` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_users`;

DROP TABLE IF EXISTS `seed_vehicles`;
CREATE TABLE `seed_vehicles` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_vehicles` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_vehicles`;

DROP TABLE IF EXISTS `seed_warehouses`;
CREATE TABLE `seed_warehouses` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_warehouses` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_warehouses`;

DROP TABLE IF EXISTS `seed_weighings`;
CREATE TABLE `seed_weighings` (
  `id` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `seed_weighings` (`id`, `payload`) SELECT `id`, `payload` FROM `biz_weighings`;
