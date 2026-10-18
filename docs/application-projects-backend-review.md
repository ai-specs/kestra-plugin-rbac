# 应用项目后端候选核查

本记录对应基线 `2f5d41d8affbbd8e35915b0f8f09f0ad05b792bc` 的隔离工作树，
是未提交候选检查结果，不是插件发布或组合验收结论。共享需求见 application-projects.md。

## 已核对的实现与边界

- `ProjectAuthorization` 按完整 grant 判断单动作；列表先在同 scope 内相交
  READ/LIST，再合并，关联查询项保持 namespace 与 ID 的对应关系。
- 项目身份 DTO 检查 issuer、audience、project、tenant、目录修订；未知或禁用
  scope 混入时拒绝。DTO 本身不认证 token；真实可信身份仍需宿主公开 adapter。
- `ProjectPolicyBoundary` 检查全部保存候选，不从其他 scope 借用上界。
- `ProjectPolicyStore` 使用不可变快照与 head 修订，事务内 CAS；旧快照仍保持原值。
  迁移必须明确批准源修订、源内容、目标内容和操作者；不覆盖旧数据。
- `ProjectPolicyManagement` 是未注册的本地服务，不是已可访问 HTTP API。
  其目录边界和最后管理员检查由必需回调提供；测试中的空回调不代表真实管理员可达性。
- `ProjectManagementSafety` 使用调用方提供的可信目录与可达管理 scope 集合。
  真实 subject/client/assignment 可达性仍依赖正式宿主机制。

## 本轮修复

普通 `updateProject` 原先可以改变 `directoryRevision`。现在必须保持原目录修订，
改变修订只能经完整 `reconcileProject` 保存。新增测试验证拒绝后 JSON 与策略 revision
均不变，并验证相同目录修订可以保存。

新增并发存储测试：两个写入者使用相同 expected revision，必须恰好一个成功、一个
冲突；最终 revision、previousRevision、操作者与不可变旧快照保持一致。

## 实际独立检查

使用已有固定公开宿主输入运行；未读取相邻源码参与编译，未消费候选 SPI。
本地宿主 executable 的 SHA-256 已与 `.integration/host-provenance.json` 核对。
未在本轮重新核验宿主远端发布 CI，不能据此批准任何新宿主契约。

```bash
./gradlew test --tests 'io.kestra.extensions.rbac.project.*' \
  --tests 'io.kestra.extensions.rbac.store.Project*' \
  -x processResources --console=plain
git diff --check
```

两项最终退出码均为 0。定向测试共 53 项，失败 0、错误 0、跳过 0：
ProjectAuthorizationTest 26、ProjectPolicyBoundaryTest 3、ProjectManagementSafetyTest 3、
ProjectMigrationReviewTest 9、ProjectPolicyManagementTest 7、ProjectPolicyStoreTest 5。
报告为 `build/reports/tests/test/index.html`，机器报告为 `build/test-results/test/TEST-*.xml`。
首轮测试使用 H2 的 PostgreSQL 兼容模式。后续以相同测试选择在本次专属 PostgreSQL
18.6 容器运行，设置 RBAC_STORE_TEST_JDBC 指向随机本机端口，各用例建立独立 schema。
PostgreSQL 测试同样为 53 项、失败/错误/跳过均为 0，Gradle 退出码 0；其中实际连接
数据库的 ProjectPolicyStoreTest 5 项与 ProjectPolicyManagementTest 7 项均通过，
包含迁移审批/CAS、并发写入与拒绝无策略副作用。其余 41 项为不连接数据库的模型/审批测试。
本次 PostgreSQL 报告完整副本保留在
`build/backend-matrix/postgres18-20261018-085317/`，包含 summary.json、gradle.log、
test-results、html-report 和 postgres.log。summary.json 记录实际镜像 digest、服务版本、
随机端口、命令、逐套测试数量与清理结果。专属容器及匿名卷已删除，清理退出码 0，
再次 inspect 确认容器不存在；未连接现有目录容器或用户数据库。
明确排除 processResources，避免运行其他负责人正在修改的 UI 构建；未运行完整 check、
未编译发布 jar，也未验证 thin jar、HTTP、真实 token、浏览器、MCP 或 Compose。

## 必须由主控继续协调的事项

1. 提供新公开宿主契约对应的成功 CI、正式镜像 digest、可信身份和目录快照 API。
   本轮未修改 host-contract.properties，不使用候选 SPI 作为编译输入。
2. 正式 adapter 必须将真实目标项目传入管理授权，并使用同一可信目录快照完成授权、
   保存边界与最后管理员可达性检查；不能用空回调或 scope 名称猜测管理员。
3. HTTP adapter 需提供身份、CSRF、If-Match、错误码及权限过滤后的读取接口；
   本地服务方法不检查 HTTP 身份。恢复 GET 的 upperBound 只能包含获准目标的公开目录。
4. 运行时仍是 RuntimePolicies/PolicyEngine 的 schema1、旧 subject/roles 映射与
   subject 管理员旁路。新身份 SPI 正式发布前未删除这些入口，不能启用或宣称新模式。
   切换时须一次替换旧运行链，删除旧 API 和配置入口，并验证旧会话/凭据失效。
5. 离线 schema2 工具只做结构和审批校验，不连接真实目录验证 grants 上界、管理员
   可达性或 token/session 撤销。不得直接把它当作生产安全切换工具。
6. 迁移批准必须补齐真实账号授权差异、备份和匹配产物的恢复步骤；随后运行 PostgreSQL、
   真实管理 API 及 UI/REST/MCP 安全矩阵，拒绝写入必须检查持久化与异步副作用。

本轮未修改 UI、共享 application-projects.md、正式子仓、宿主或根仓，未提交、推送、发布。

Adapter 设计、全部 UI client 路径与请求体核对、旧消费者清理清单和迁移审批限制见
[应用项目后端实施设计](application-project-implementation.md)。新增审批规范 hash 稳定性
测试，并修正伪造报告测试，使其确实在重算外层 hash 后检查内容重建拒绝。

按主控字段裁决增加目录 revision=3、authorizationVersion=97 的防混用测试。
迁移命名空间交集只接受明确 NAMESPACE grants；TENANT 单列人工审查项，不自动转换；
INSTANCE 仍由源策略验证器拒绝。新增报告字段改变审批 hash，必须重新审查后批准。
