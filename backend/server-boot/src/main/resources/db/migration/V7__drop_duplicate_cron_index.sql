-- ============================================================
-- 冗余索引清理：ops_cron_job
--
-- 作者: zhaodc        创建时间: 2026-09-21
--
-- 背景：V6 给 ops_cron_job 加了 idx_status_next_run(status, next_run_at)，
--       但 documents/serverpanel-init.sql 里该表**早已有同列同序的
--       idx_status_next(status, next_run_at)**。MySQL 对「同列不同名」的索引
--       只给 deprecation 警告而不报错，于是 V6 静默留下了一个完全重复的索引：
--       白白多占一份 B+Tree 写放大，且未来版本会直接失败。
--
-- 说明：只 DROP 本次（V6）自己新增的那一个，保留 init 脚本里的 idx_status_next；
--       该表当前 0 行数据，无业务影响。
--
-- 为什么不直接改 V6：V6 已在生产库执行，Flyway 会校验已应用迁移的 checksum，
--       改动 V6 会导致 DbValidate 失败（需人工 flyway repair）。因此按不可变迁移
--       的惯例，新增 V7 做补偿；全新库会「V6 建、V7 删」，结果一致。
-- ============================================================

ALTER TABLE ops_cron_job DROP INDEX idx_status_next_run;
