-- Flyway 12.x 初始化连接时会查询 performance_schema.user_variables_by_thread
-- （用于检测 user variable reset 能力），面板账号仅有业务库权限会导致
-- "SELECT command denied" → 迁移失败。此脚本在容器首次初始化时补授权。
GRANT SELECT ON performance_schema.user_variables_by_thread TO 'panel'@'%';
FLUSH PRIVILEGES;
