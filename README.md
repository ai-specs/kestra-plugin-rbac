# kestra-plugin-rbac

> 最新职责纠正：应用组、scope、应用支持、用户授予、内置/退役约束和业务存储由本仓实现；宿主仅提供标准 OIDC 与只读协议输入机制。前序“插件代理宿主组 CRUD”的候选被替代，当前尚未完成迁移或正式交付。实施和风险顺序见 `docs/application-groups.md`。

> 当前应用组实施规范见根仓 `docs/application-group-target-model.md`。本文现有应用项目、完整恢复和“本地绑定”说明记录的是旧发布模型；新安装目标尚未由本插件发布或通过组合验收。

当前未发布的应用组设计和候选 API 见 `docs/application-groups.md`。候选实际策略接口已改为具体应用的 `runtime-permissions` 路径；旧全局策略与完整恢复入口已从候选源码删除。只有匹配宿主正式发布后重新编译、通过本仓 CI 并发布的插件才可用于最终部署；本文后续旧路径示例不得作为新模型接入说明。

Independent, database-backed authorization extension for the Kestra OSS overlay. The business policy lives in this repository. Output: **`build/libs/kestra-rbac-authorizer.jar`**. The jar is thin, includes Micronaut generated bean indexes, and contains no Kestra, Micronaut, Jackson or other dependency classes.

The earlier runtime authorization implementation passed independent CI and real monorepo Compose
acceptance; that historical result does not validate the instance-admin change. See [validation evidence](docs/validation.md) for the tested scope. Build dependencies
are extracted from the immutable published image, independently of any overlay source checkout.

## 管理功能方案

用户、角色和授权管理功能的业务调查与阶段方案见
[RBAC 管理功能方案](docs/rbac-management-plan.md)。该方案属于本插件，相关宿主机制
需求向 overlay 独立提案；管理 API、页面和数据库策略正在实施，尚未完成发布与组合验收。

当前设计见 [应用组与 Kestra 权限实施设计](docs/application-groups.md)，HTTP 和修订语义见
[管理接口实施契约](docs/management-api.md)。schema2 使用 applicationGroups 保存 Kestra
实际策略，scope 可映射应用自身的资源与管理授权。应用组业务及其存储属于插件；标准账号
通过 UserDirectoryV1 管理，签发投影与生命周期检查使用正式协议 SPI，不查询宿主身份表。
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
`/app/kestra` without starting a server, checks the public protocol and typed SPI classes, and writes
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

应用组、scope、应用支持登记、用户 scope 授予和应用权限声明由插件自己的数据库与
`/ui/main/extensions/rbac` 管理页面维护。scope 使用不可改的 `key`、可改的角色 `name`
与 `description`。应用支持范围与用户授予求交，决定用户在该应用可获得的 scope。
应用权限声明只供查看与 OAuth2 授权页展示，不作为 Kestra 实际资源授权的事实来源。
声明缺失或变更不会自行收窄 scope；撤销支持与用户授予会推进组撤权版本。

IDENTITY、LOCAL_POLICY 和 BUSINESS_INSTANCE 是机器契约的三个独立管理域，READ 与
MANAGE 不互含。用户界面分别称用户及应用组管理、Kestra 实际权限和实例资源管理。
LOCAL_POLICY 的空 `applicationGroupIds` 只指所属应用组，非空集合精确列出目标组。
BUSINESS_INSTANCE 不授予另外两个管理域。新增账号不自动授予任何 scope。

应用组入口是卡片。点击卡片先进入只读详情，显式点击编辑后修改。scope、应用支持和
用户授予使用各自对象修订；创建子对象使用组修订。Kestra 实际权限使用全局策略修订。
写入必须发送强 If-Match。查看与修改分开检查，保存回执不附带未经 READ 授权的策略。

宿主签发、refresh、账号/client 生命周期与插件业务写入复用正式公开协议安全锁。
业务写入在可写 READ_COMMITTED 事务中重新校验可信身份、当前对象修订、支持与授予，
并保护有效管理员；失败回滚全部写入。标准身份状态只通过正式 OidcProtocolStateV1
读取，插件不查询宿主私有表。运行时使用当前应用组快照和实际策略，故障失败关闭。
scope 名称和说明不授予资源权限。旧项目路径、完整恢复和旧目录代理不保留兼容入口。

组内分页带 `directoryRevision`；消费者必须核对各页同版本。账号列表使用标准账号目录
自己的分页契约。删除非内置组要求对象修订匹配且没有 scope、应用或用户授予；退役 ID
不可复用。第一个内置应用组始终不可删除。

## 新安装应用组

先使用宿主正式首装流程创建初始账号和配置中的 UI client。停止业务 JVM，在同一部署的
数据库和持久卷上运行插件 CLI。命令要求 `RBAC_POLICY_JDBC_URL`、`RBAC_POLICY_JDBC_USER`、
`RBAC_POLICY_JDBC_PASSWORD` 和宿主完整 `KESTRA_CONFIGURATION`；它通过公开协议服务
核对 issuer、真实安装凭据、标准安装 ID、账号可登录性及已注册的应用。

`group-plan.json` 包含 `formatVersion:1`、`installationId`、`issuer`、`uiClientId`、
`builtInApplicationGroupId` 与一个 `applicationGroups` 项。该项包含 `applicationGroup`
（`id/displayName/active/revision/authorizationVersion`）、`scopes`（`key/name/description/revision`）、
`applications`（`clientId/approved/audiences/supportedScopeKeys/revision`）及 `userGrants`
（`issuer/subject/scopeKeys/revision`）。初始修订和撤权版本均为 1。UI client 是普通应用成员。
不接受 `firstPartyUi`、`projects`、旧 scope 字段或恢复快照。初始账号必须获授实际管理策略
所用的 scope；完整管理员可达性还需随后初始化 Kestra 实际策略并通过真实登录验证。

```bash
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupDirectoryCommand preview group-plan.json
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupDirectoryCommand apply group-plan.json <reviewed-approval-hash>
```

preview 不创建表或更改数据库，输出规范计划与完整计划的 SHA-256。apply 必须提供审查过的
同一 hash，并在协议安全锁内再次核对安装状态，只允许空目录初始化。它原子保存内置组、
scope、应用支持、用户授予与撤权版本；不能覆盖已安装目录。之后初始化实际策略与可选的
权限声明，启用上述配置并启动 JVM，完成部署仓的真实 new-install 验收。

## 离线 schema2 维护与显式迁移

应用组新安装还需为当前 Kestra 应用登记支持 scope 的权限声明元数据。此登记用于签发范围与授权页展示，不替代下方 Kestra 实际资源策略。先从成功宿主 CI 的固定镜像运行 `./scripts/prepare-host.sh`，再编译插件 jar 或下载成功插件 CI 的 jar，最后用同一 PostgreSQL 连接执行以下命令；`declarations.json` 含 `applicationGroupId`、`clientId` 与 `scopes:[{key,permissions:[{key,name}]}]`。`initialize` 在一个事务中写入全部声明，重复或无效 key 拒绝，不修改已有记录。

```bash
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationScopeDeclarationCommand validate <declarations.json>
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationScopeDeclarationCommand initialize <declarations.json>
```

命令读取下方相同的 `RBAC_POLICY_JDBC_URL`、`RBAC_POLICY_JDBC_USER` 和 `RBAC_POLICY_JDBC_PASSWORD` 环境变量。应用可申请范围由插件应用支持登记与用户授予决定；声明不是额外的签发或授权开关，新增或删除声明不会改写 Kestra 资源策略。当前应用组功能仍是部分候选，完整安装与组合验收须以根仓目标模型为准。

以下工具仅在兼容正式宿主 executable 与本插件 jar 的 classpath 使用，不是生产 HTTP
首次引导接口，也不自动创建登录账号或授予 scope。凭据必须放入环境变量或被忽略的
.env，不写入命令历史。首次初始化只允许空库；已有 schema1 必须显式审批迁移，不双读。

```bash
export RBAC_POLICY_JDBC_URL RBAC_POLICY_JDBC_USER RBAC_POLICY_JDBC_PASSWORD
export RBAC_POLICY_OPERATOR_SUBJECT
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupPolicyCommand validate <schema2-policy.json>
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupPolicyCommand initialize <schema2-policy.json>
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupPolicyCommand preview-migration \
  <schema2-policy.json> <role-decisions.json> <new-review.json>
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupPolicyCommand migrate \
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

部署只接受 schemaVersion=2 的 applicationGroups 策略。schema1 数据仅作为离线迁移
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
