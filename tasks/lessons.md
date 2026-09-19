# 经验教训记录

> 每次收到修正意见或排查出问题时，将经验教训录入本文件，会话启动时优先回顾。

## 2026-09-19 MongoDB 集成验证

### 1. Spring Boot 4 MongoDB 属性前缀变更为 spring.mongodb
- **现象**：`spring.data.mongodb.uri` 配置了用户名密码，但启动日志显示 `credential=null`，所有 MongoDB 写操作报 `Unauthorized (code 13)`；`ping` 命令匿名可用，故连通性检查未暴露问题。
- **根因**：Spring Boot 4 将 MongoDB 连接属性前缀从 `spring.data.mongodb` 迁移到 `spring.mongodb`（属性类 `org.springframework.boot.mongodb.autoconfigure.MongoProperties`），旧前缀被静默忽略，MongoTemplate 以默认 `mongodb://localhost:27017` 无凭据连接。
- **教训**：升级 Spring Boot 大版本后，数据源连接属性前缀可能变化；凭据未生效时应先查启动日志中驱动客户端的 `credential=` 字段，再怀疑密码本身。

### 2. IDEA 编译错误桩污染 target/classes，导致 Maven 假 BUILD SUCCESS
- **现象**：`mvn package` BUILD SUCCESS，但运行时抛 `java.lang.Error: Unresolved compilation problem`（ECJ 错误桩特征）；clean 全量构建后才暴露真实编译错误。
- **根因**：IDEA 增量编译把带编译错误的 class（错误桩）写入 `target/classes`；Maven 增量判断 .class 比 .java 新则跳过重编译，错误桩被直接打包进 jar。
- **教训**：
  - 出现过编译错误后，Maven 构建前先 `clean`；
  - `BUILD SUCCESS` 后仍需启动验证，运行时 `Unresolved compilation problem` 说明 jar 里有 IDEA 残留的错误桩。

### 3. 单个文件编译错误可导致整个模块 Lombok 注解处理失效
- **现象**：`UserPreferenceDocument` 的 `Document` 歧义 import（`org.bson.Document` 与 `org.springframework.data.mongodb.core.mapping.Document` 冲突）修复前，server-system 模块所有 Lombok getter/setter/log 全部"找不到符号"。
- **教训**：Maven 报大量 Lombok 符号缺失时，先找同轮编译中的其他真实错误（如 import 歧义），不一定是 Lombok 配置问题。

### 4. Spring Data MongoDB 5.x 移除 PersistentEntityIndexResolver
- **现象**：`new PersistentEntityIndexResolver(...)` 编译失败。
- **修正**：改用工厂方法 `IndexResolver.create(mongoTemplate.getConverter().getMappingContext())`。

### 5. 本机 java 命令为 JDK 17，构建产物为 Java 21
- **现象**：`java -jar` 启动报 `UnsupportedClassVersionError: class file version 65.0`。
- **教训**：本机需用完整路径 `C:\Users\99754\.jdks\ms-21.0.12.1\bin\java.exe` 启动后端 jar，或先设置 `JAVA_HOME`。
