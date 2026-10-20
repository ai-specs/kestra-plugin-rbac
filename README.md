# kestra-plugin-rbac

> 当前应用组实施规范见根仓 `docs/application-group-target-model.md`。本文现有应用项目、完整恢复和“本地绑定”说明记录的是旧发布模型；新安装目标尚未由本插件发布或通过组合验收。

Independent, database-backed authorization extension for the Kestra OSS overlay. The business policy lives in this repository. Output: **`build/libs/kestra-rbac-authorizer.jar`**. The jar is thin, includes Micronaut generated bean indexes, and contains no Kestra, Micronaut, Jackson or other dependency classes.

The earlier runtime authorization implementation passed independent CI and real monorepo Compose
acceptance; that historical result does not validate the instance-admin change. See [validation evidence](docs/validation.md) for the tested scope. Build dependencies
are extracted from the immutable published image, independently of any overlay source checkout.

## 管理功能方案

用户、角色和授权管理功能的业务调查与阶段方案见
[RBAC 管理功能方案](docs/rbac-management-plan.md)。该方案属于本插件，相关宿主机制
需求向 overlay 独立提案；管理 API、页面和数据库策略正在实施，尚未完成发布与组合验收。

应用项目目标见 [应用项目授权模型](docs/application-projects.md)，HTTP 和修订语义见
[管理接口实施契约](docs/management-api.md)。schema2 以应用项目 scope 直接绑定完整本地
grants，不再使用本地 roles、subject 绑定或 roleMapping。共享目录与账号由宿主公开
ApplicationDirectoryV1/UserDirectoryV1 提供；插件只保存本地绑定，不查询宿主身份表。
宿主公开契约必须先通过正式发布门，再运行下方 prepare-host 取得固定编译输入；源码
或候选 jar 不能作为正式构建依赖。插件本轮 CI、发布及 Compose 状态以实际报告为准。

独立存储测试默认使用 H2；设置 RBAC_STORE_TEST_JDBC 可对隔离 PostgreSQL 执行相同
检查。该变量只用于测试，账号为 postgres、密码为公开测试常量 policy-test-only，
每个用例使用独立 schema，不得指向生产数据库。CI 不构建或部署另一个仓库。

## Build independently

Requirements: Docker, JDK 25, Node.js 24 (including npm), Python 3 and read access to the private GHCR host image.
Authenticate to GHCR with a credential that has `read:packages` access, then run:

```bash
./scripts/prepare-host.sh
./gradlew clean check --console=plain
```

`prepare-host.sh` pulls the immutable image in `host-contract.properties`, extracts
`/app/kestra` without starting a server, checks the public legacy and typed SPI classes, and writes
ignored local compilation dependencies and provenance. It is safe to rerun. No overlay
checkout, upstream clone, patch replay or Kestra build is required.

The host jar is a compile/test dependency only; it is never included in the extension jar.
Unit tests remove host bean indexes from temporary test copies so extension bean discovery
can be tested without booting Kestra. Real deployment discovery is verified by monorepo
Compose acceptance. CI uses the same published-image extraction and build commands. The host package grants
this repository Read access under Manage Actions access, so CI uses its own GITHUB_TOKEN
with packages:read; no personal token or cross-repository source deploy key is required.

构建同时执行独立前端的 `npm ci --ignore-scripts`、翻译完整性检查、ESM 与 CSS 构建及
管理资源清单生成。Java jar 包含插件自有前端资源，不包含宿主源码或框架 Java 类。
前端框架来自固定 npm 依赖；UI SDK 从同一个已发布宿主镜像的 executable 提取，写入
被忽略的 `.integration/ui-sdk`。SDK 缺失时构建失败，不从相邻仓补文件。

## 数据库管理模式

正式使用要求：选择已通过宿主完整发布门的固定镜像，独立插件 CI/产物发布通过，并完成
部署仓 Compose 验收。公开契约的正式输入由 host-contract.properties 和提取 provenance
确定；不能仅根据本 README、源码或单元测试启用生产管理功能。

```yaml
kestra:
  rbac:
    enabled: true
    typed:
      enabled: true
    evaluation:
      enabled: true
    management:
      enabled: true
    application-groups:
      enabled: true
    policy-source: database
```

该片段须合并到宿主已发布 README 的完整 OIDC/PostgreSQL 配置，不替代 issuer、client、
audience、回调、静态资源和首次安装要求。数据库是唯一运行策略来源。必须移除旧 roles、
subjects、role-mapping、instance-admin-roles 和 management-admin-subjects；它们不能提供
普通或管理员权限。无项目 scope 的账号零授权，协议 scopes 与旧 roles claim 也不补授权。

共享项目/scope、client 成员批准和 subject 授予通过宿主公开目录维护；本地项目及完整
grants 在 `/ui/main/extensions/rbac` 维护。两种保存互不替代。先建立共享语义、批准真实
client 与 audience、授予真实账号并重新登录，再配置获准目标的本地绑定。新账号创建
不附加授权。scope 名称不透明，不能按名称猜测动作或管理员身份。

IDENTITY、LOCAL_POLICY 和 BUSINESS_INSTANCE 三域独立。READ 与 MANAGE 不互含。
本地管理检查每次请求实际目标；LOCAL_POLICY 空 projectIds 只指所属项目，非空集合只指
精确列出项目。BUSINESS_INSTANCE 不提供身份目录或本地策略管理权限。

每次请求固定一份 schema2 策略和一致目录投影。数据库或身份服务故障失败关闭，不使用
旧允许缓存或 schema1 回退。DirectoryRevision 是共享 Project.revision；authorizationVersion
是独立撤权 epoch，不能替代本地确认版本。目录变更后普通请求拒绝，仍有最新目录同
scope LOCAL_POLICY.MANAGE 的管理员可审查全部绑定并执行完整恢复；未知 scope 混入仍拒绝。
普通项目保存不得改变 directoryRevision，恢复不得部分保存或静默裁掉越界 grants。

本地写入用全局策略 revision，目录创建/更新和账号更新使用各自对象 revision，均按
[管理接口实施契约](docs/management-api.md) 发送强 If-Match。保存响应只包含实际目标
与新 revision，不因 MANAGE 泄露其他项目。身份目录修改可能令当前会话需重新登录。

公开目录没有与本地策略共用的条件提交锁；本地单事务不等于跨系统原子提交。候选提交
前后若目录并发变化，后续请求必须按最新版本拒绝。严格“目录校验后变化绝不提交”的
并发门仍须独立公开机制及验收，不能以单元测试通过代替。目标共享 scope/client/assignment 列表的每页返回
目录 revision；跨页消费者必须核对各页同版本，UI 对这些项目内列表逐页核对 directoryRevision，并与已加载项目修订一致；同总数的修订变化也拒绝展示。
项目清单与账号列表没有公开全局修订，不提供跨请求快照保证。

## 离线 schema2 维护与显式迁移

应用组新安装还需为当前 Kestra 应用登记支持 scope 的权限声明元数据。此登记用于签发范围与授权页展示，不替代下方 Kestra 实际资源策略。先从成功宿主 CI 的固定镜像运行 `./scripts/prepare-host.sh`，再编译插件 jar 或下载成功插件 CI 的 jar，最后用同一 PostgreSQL 连接执行以下命令；`declarations.json` 含 `applicationGroupId`、`clientId` 与 `scopes:[{key,permissions:[{key,name}]}]`。`initialize` 在一个事务中写入全部声明，重复或无效 key 拒绝，不修改已有记录。

```bash
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationScopeDeclarationCommand validate <declarations.json>
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationScopeDeclarationCommand initialize <declarations.json>
```

命令读取下方相同的 `RBAC_POLICY_JDBC_URL`、`RBAC_POLICY_JDBC_USER` 和 `RBAC_POLICY_JDBC_PASSWORD` 环境变量。宿主成员登记的支持范围与插件声明必须同时存在，应用才能申请该 scope；新增或删除声明不会改写 Kestra 资源策略。当前应用组功能仍是部分候选，完整安装与组合验收须以根仓目标模型为准。

以下工具仅在兼容正式宿主 executable 与本插件 jar 的 classpath 使用，不是生产 HTTP
首次引导接口，也不自动创建登录账号或授予 scope。凭据必须放入环境变量或被忽略的
.env，不写入命令历史。首次初始化只允许空库；已有 schema1 必须显式审批迁移，不双读。

```bash
export RBAC_POLICY_JDBC_URL RBAC_POLICY_JDBC_USER RBAC_POLICY_JDBC_PASSWORD
export RBAC_POLICY_OPERATOR_SUBJECT
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ProjectPolicyCommand validate <schema2-policy.json>
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ProjectPolicyCommand initialize <schema2-policy.json>
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ProjectPolicyCommand preview-migration \
  <schema2-policy.json> <role-decisions.json> <new-review.json>
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ProjectPolicyCommand migrate \
  <schema2-policy.json> <review.json> <explicitly-approved-review-sha256>
```

role-decisions 必须逐旧角色明确 MAP 或 DROP、目标项目/scope 与原因；每个目标 scope 必须
存在。review 保留旧角色、subjects、roleMapping、完整旧/新 grants 和定义增减，标记旧
跨角色 READ/LIST 组合的不等价。操作者必须审查并显式批准完整报告 SHA-256；执行再次锁定
源 revision、源规范 hash、批准目标与操作者，任何变化拒绝，不覆写旧快照。
旧 PolicyImportCommand 的 schema1 initialize 已拒绝，schema1 validator 只用于离线源解析。
历史 examples/management-policy.json 是 schema1 调查输入，不得当成新运行策略初始化。

离线审批仅批准本地存储变换，不证明真实授予、管理员登录或撤权已经发生。旧部署 subject
管理员旁路来自配置，不在旧 JSON 中，必须单列其来源配置 hash、明确移除项、批准的新
scope、完整管理域/动作、client/audience、身份端实际授予及管理员重新登录证据。不得根据
旧 admin 名称推导 IDENTITY、LOCAL_POLICY 或 BUSINESS_INSTANCE，不得用警告代替撤权。

维护窗口必须备份本地策略、身份目录与安装凭据，停止旧写入口，执行已批准身份授予，
移除部署旁路并切换正式产物。必须真实验证旧 cookie、code、access/refresh/UI/IdP/MCP
凭据失效、新管理员可达、普通账号前后允许/拒绝矩阵和完整恢复。只重启 JVM 不证明撤权。
部分成功必须保持拒绝并记录实际状态；回退须恢复匹配的备份、身份授予与旧产物，不能只换 jar。

## Configure and deploy

Consume an independently accepted overlay image whose published manifest classpath includes `libs/kestra-rbac-authorizer.jar` and whose unified SPI/execution gates match the contract pin. Mount the jar **read-only at `/app/libs/kestra-rbac-authorizer.jar`**. `/app/plugins` is for Kestra task/trigger plugins and does not discover this extension's web beans. Restart the container JVM after replacing the jar. Apply policy configuration changes with Compose to update the container.

部署只接受 schemaVersion=2 的 applicationProjects 策略。schema1 数据仅作为离线迁移
来源保留，生产启动不注册或读取旧策略 store。首次引导、维护授权与最后管理员保护按宿主
已发布契约执行，不保留永久 subject 超级权限。允许/拒绝、查询域、执行输出、账号撤权、
组件缺失和关闭功能仍须通过部署仓真实验收，单个仓库的绿灯不能替代组合检查。

## Input/output dependency policy

`SafeFlowDependencies` implements the host `McpAuthorizationResolver` consumed by both actual execution gates. It inspects the **runtime ProcessedFlow declaration**, not an uploaded/latest raw flow. Runtime `tenantId` may be absent/null or equal `main`; another tenant denies. Inputs, variables, checks and labels allow literals only; output expressions allow only an entire `{{ outputs.task.field }}` reference (plain dotted names), or literals. Secret/KV/namespace/file functions, filters, dynamic indexing, interpolation, unknown expression syntax and malformed YAML deny before execution. A safe declaration returns an explicit empty extra-read plan. This policy does not sandbox tasks or reinterpret their later engine privileges.

## 真实组合验收

旧 OidcRbacFixtureTest 已删除，其 roles 账号不能证明新项目 scope 已签发。
新部署矩阵必须通过公开身份目录准备真实项目、scope、成员 client 和测试账号，使用生产
UI 与 MCP 登录流程取得可信身份。测试密码仅为隔离环境常量，不得写入生产配置。

部署仓消费固定镜像 digest、独立插件 jar/hash 和匹配的公开宿主输入，执行 Compose。
必须覆盖真实 bean 装载、单/多 scope、精确 namespace 与资源 ID、scope 内 READ/LIST
交集、允许写入与执行/输出、零授权退出换号、未知 scope 混合、issuer/audience/tenant
错误、CSRF、撤权、最后管理员保护、目录恢复、旧端点 410、组件缺失及关闭功能回归。
拒绝必须比较持久化、存储、队列与延迟副作用；菜单可见、HTTP 200、mock 身份或单元
测试绿灯都不能代替真实操作。UI 必须覆盖发布宿主支持的语言、主题与窄屏。

Build/test results, artifact hash and image acceptance are recorded in [validation evidence](docs/validation.md).

For a runtime explanation of the complete authentication and authorization path, see
[OAuth2/OIDC 到 RBAC 的运行机制](docs/authorization-flow.md). That document explains the
execution model and boundaries; deployment and configuration instructions remain in this README.

## Obtain the built jar

Successful [build runs](https://github.com/ai-specs/kestra-plugin-rbac/actions/workflows/build.yml)
publish a `kestra-rbac-authorizer` artifact containing the jar, host provenance and test reports.
Choose a successful run matching the desired host image contract, then download:

```bash
gh run download -R ai-specs/kestra-plugin-rbac <run-id> \
  --name kestra-rbac-authorizer --dir /tmp/kestra-rbac-artifact
```

When only replacing a jar in the deployment bind mount, run `docker compose restart kestra`
to restart the container JVM and load it. This does not compile Kestra or rebuild the host image.
Use `docker compose up -d` for the initial deployment or configuration changes.
