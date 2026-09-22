-- ============================================================
-- V12：审计日志字段语义增强
--
--   1) biz_code  业务错误码独立成列
--      此前 ServiceException 的业务码（6035/6038/6039...）只出现在 error_msg 文本里，
--      result_code 一律记 500，调用方要拿业务码只能去文本里抠数字。
--      这里新增独立列，而不是把 result_code 改成业务码 —— 重载会让新旧行语义混用，
--      比维持现状更糟。
--   2) risky     高危标记落库（@Audit.risky）
--      前端审计页「风险」列早已按 row.risky 渲染，但后端从来没有这一列，
--      该列一直恒为「普通」。此列把注解的承诺补实。
--   3) 历史脏数据：审计入参曾把明文口令写进 params，本迁移一次性脱敏。
--
-- 说明：历史行的 risky 保持 0，不做启发式回填 —— 无法从 action 可靠推断
--       （例如 nginx:instance:save 非高危、job:save 是高危，字符串规则区分不了），
--       猜错比留空更有误导性。
--
-- 作者: zhaodc          创建时间: 2026-09-22
-- ============================================================

ALTER TABLE sys_audit_log
  ADD COLUMN biz_code INT DEFAULT NULL COMMENT '业务错误码(与 result_code 分工)' AFTER result_code,
  ADD COLUMN risky TINYINT NOT NULL DEFAULT 0 COMMENT '是否高危操作(@Audit.risky)' AFTER biz_code;

ALTER TABLE sys_audit_log ADD KEY idx_module_action (module, action);
ALTER TABLE sys_audit_log ADD KEY idx_biz_code (biz_code);

-- 历史行回填：result_code 本身就是业务码的失败行（经 R.fail 而非异常返回），
-- 把同一个值补进 biz_code，让新旧行口径一致。0/403/500 三类结果类别码不回填。
UPDATE sys_audit_log SET biz_code = result_code
 WHERE result_code NOT IN (0, 403, 500);

-- 历史脏数据脱敏：只匹配「有引号包裹的值」，不会误伤 "password":null
UPDATE sys_audit_log
   SET params = REGEXP_REPLACE(params, '"password":"[^"]*"', '"password":"***"')
 WHERE params LIKE '%"password":"%';
