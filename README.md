# kestra-plugin-rbac

> 应用组、scope、统一应用权限、派生支持集、用户授予、内置/退役约束和业务存储由本仓实现；宿主提供标准 OIDC 与公开协议机制。统一权限模型、DTO 和初始化输入见 `docs/application-groups.md` 顶部。

> 当前目标以维护者应用组模型为准；本仓自足契约见 `docs/application-groups.md` 和 `docs/management-api.md`。正式 CI 及 root 组合验收必须对应最终提交，历史记录不能替代。

设计来源限定：R24同借用连接原子清理、R25配置登录client部署维护轮换是主控在维护者
授权范围内补充的设计裁决，属于“维护者可复核设计”，不表示维护者已逐项批准。
插件消费边界以ai-specs/kestra-overlay正式README的“账号与client凭据V2”和“受限
维护CLI”公开契约为准，具体发布输入由host-contract.properties固定。

统一权限模型采用应用下独立 `permissions` CRUD，同一实体保存 Kestra typed grants 或外部 details。
旧 `scope-declarations` 与 `runtime-permissions` 路由不注册，无兼容转发。当前代码不表示
新契约 CI、PG、宿主 F3/F4 或 Compose 验收通过；只有匹配正式宿主重新编译、独立 CI
及发布通过的产物才可部署。历史测试记录不能证明本轮 R21 行为。

当前模型与验证边界：description为可选HTTP字段、权限列表先按key排序再分页，默认25；
新安装使用目录四表加管理审计表，生产不注册旧policy/declaration store bean。正式协议
允许空资源audiences登记，不补clientId；临时账号姓名/邮箱可空，资料更新可清空，不补
用户名或虚构邮箱。当前正式编译输入只由host-contract.properties及独立prepare-host生成的
.integration/host-provenance.json共同确定；旧版本测试记录不作为当前pin。公开SDK
通过helper适配已声明原生组件，生产插件UI及根仓Compose仍须独立验收。
具体输入、缺口和版本证据入口见 `docs/application-groups.md`；本地候选测试不是发布结论。

已验证独立检查点 `73fa96e`（成功 CI `40999171025`、已下载产物 `12952369487`）包含账号HTTP：只接受username/name/email创建并提供强修订DELETE入口；Published适配器
消费正式UserDirectoryV2，唯一参与者借用连接完成全组清理、可信actor复查、最后管理员
与提交审计。凭据HTTP已接正式ClientCredentialsV1。缺失/重复组件失败关闭。该检查点已通过
Java286、PostgreSQL108、生命周期24及UI172，包含原生抽屉、计数间距和删除焦点修复。
后续保存图标公开icon属性、卡片标题纵向布局及新宿主消费仍须对应源码重新完成本地门、
独立CI和发布。独立产物不表示根仓组合通过。

Independent, database-backed authorization extension for the Kestra OSS overlay. The business policy lives in this repository. Output: **`build/libs/kestra-rbac-authorizer.jar`**. The jar is thin, includes Micronaut generated bean indexes, and contains no Kestra, Micronaut, Jackson or other dependency classes.

The earlier runtime authorization implementation passed independent CI and real monorepo Compose
acceptance; that historical result does not validate the instance-admin change. See [validation evidence](docs/validation.md) for the tested scope. Build dependencies
are extracted from the immutable published image, independently of any overlay source checkout.

## 管理功能方案

用户、角色和授权管理功能的业务调查与阶段方案见
[RBAC 管理功能方案](docs/rbac-management-plan.md)。该方案属于本插件，相关宿主机制
需求向 overlay 独立提案。既有管理 API、页面和数据库策略的独立检查点见 `73fa96e`
对应成功 CI `40999171025` 及已下载产物；之后改动必须重新取得独立发布证据，根仓完整组合
验收仍待独立完成，不将既有独立发布写成当前组合通过。

当前设计见 [应用组与 Kestra 权限实施设计](docs/application-groups.md)，HTTP 和修订语义见
[管理接口实施契约](docs/management-api.md)。schema2 使用 applicationGroups 保存 Kestra
实际策略，scope 可映射应用自身的资源与管理授权。应用组业务及其存储属于插件；标准账号
通过 UserDirectoryV2 管理，签发投影与生命周期检查使用正式协议 SPI，不查询宿主身份表。
宿主公开契约必须先通过正式发布门，再运行下方 prepare-host 取得固定编译输入；源码
或候选 jar 不能作为正式构建依赖。插件本轮 CI、发布及 Compose 状态以实际报告为准。

独立存储测试默认使用 H2；设置 RBAC_STORE_TEST_JDBC 可对隔离 PostgreSQL 执行相同
检查。该变量只用于测试，账号为 postgres、密码为公开测试常量 policy-test-only，
每个用例使用独立 schema，不得指向生产数据库。CI 不构建或部署另一个仓库。

当前编译输入为正式宿主 commit `ff707ddd05ed4a888a8f459d33b9a95f511a59bf`（CI `41129076310`），
镜像 digest `15c7cace895531459fa1ef03446ec3945cd9ec1440c36a165c62b52e21472bac`；
arm64 executable SHA256 `7142f7d86b29a611bd08a471616dc28d57f47c43b81081cef7cdb2462d218f22`。
该正式输入隔离授权初始化与关闭工厂的阻塞调用；公开SPI类型不变，插件仍须重新完成
独立编译和全部本地/CI门，不直接读取宿主源码或其开发目录。
该版本还在 typed REST 入口显式绑定本次真实 HTTP 请求的最小调用来源，覆盖缺失或陈旧
ambient 来源，不改变公开 SPI、授权业务或 SDK。宿主新增五项精确具名来源回归及全部既有
发布门已通过；此前部署并发早拒绝是否同源仍须根仓真实组合复验，不把机制反例作为根因定论。
构建以 `host-contract.properties` 和本仓独立提取的 `.integration/host-provenance.json` 为准。
宿主发布与插件本地检查不表示最终生产 HTTP、UI 或 Compose 组合验收通过。

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

原生Element Plus文案通过公开locale模块消费正式context.locale，使用SDK相同的
13语言、pt-BR/zh-CN别名与en回退。根ElConfigProvider随宿主locale更新，使Select
空态、无匹配与加载文案和业务vue-i18n同语言；分页复用相同解析函数。没有复制
宿主私有翻译。真实组件可见文案、主题和 Esc 栈行为由本仓独立 Chromium 矩阵验证；
root 验证生产装载、登录、持久化、授权与跨仓流程。

抽屉消费同一公开组件包的 `KsDrawer` 默认标题栏、最大化/关闭 SVG、body
内边距和 footer slot；字段使用 `KsForm/KsFormItem` 的 `ks-horizontal` 布局。保存时隐藏
原生关闭按钮，关闭入口仍执行保存/草稿检查，并禁止编辑、取消与重复提交。原生最大化
仅切换呈现，保存期间仍可切换，不改变字段、不提交也不导航；不替换全局按钮、不派生
或转接抽屉 setup。关闭时保留本轮原生组件与公开 SDK 注册，直到原生 `closed` 事件后
才卸载并恢复焦点，避免迟到的原生 focus-trap 释放把焦点送回 document BODY。
重新打开使用独立组件周期复位最大化；旧周期的 closed 回调不能关闭新周期。
卡片计数在插件自有 wrapper 使用 `--ks-spacing-1` 显式分隔数量与
名称，不覆盖全局按钮样式。Happy DOM 组件测试不证明主题、窄屏或贴底几何通过，相关呈现由本仓独立 Chromium 门验证。
保存按钮通过公开 `icon` 属性取得原生图标 wrapper 与文案间距；卡片标题、显示名和
内置标记在插件自有纵向 wrapper 排列，不改原生按钮的全局 slot 样式。这两项后续
修正必须包含在新提交的独立 Chromium CI 中，旧172项报告不能替代新源码检查。

## 插件独立真实浏览器门

本仓独立承接管理页面视觉和交互矩阵：真实 Chromium 装载本仓生产 UI bundle，
消费固定发布镜像的公开 SDK 与 lock 固定原生组件。四主题 × en/zh_CN ×
1280/768/390 × 30 具名行为共 720 项；其他 11 语言继续执行公开翻译完整性检查。
运行 `cd ui && npm run test:browser`，随后执行 `node browser/check-report.mjs`。
先执行上方独立构建并安装固定 Chromium；完整命令及报告 schema 见
[浏览器门契约](ui/browser/README.md)。

正式 `kestra-rbac-authorizer` artifact 纳入 `.evidence/ui-browser/report.json`、
`manifest.json`、输入 provenance、jar/bundle 一致报告、HTML、失败 trace 和截图。
未执行项明确为 notRun，不计通过。此门的内存管理 transport 仅用于页面呈现和交互；
真实生产 CRUD、权限、拒绝副作用与认证流程仍由部署仓验证，不运行主题与几何矩阵。
本地报告不表示 CI 或发布通过，Java 与 PostgreSQL 发布门保持独立执行。

## 数据库管理模式

账号生命周期另设必需PostgreSQL发布门；H2默认test不执行该suite，也不将其计为跳过。
CI必须在公开测试数据库执行以下独立入口，并保留失败报告：

```bash
RBAC_STORE_TEST_JDBC=jdbc:postgresql://localhost:5432/postgres \
  ./gradlew lifecyclePostgresTest --console=plain
python3 scripts/check-lifecycle-tests.py build/test-results/lifecyclePostgresTest
```

suite为`io.kestra.extensions.rbac.applicationgroup.AccountLifecyclePostgresTest`，6个具名用例。
真实插件目录、统一权限投影、身份复查、最后管理员检查与清理都在同一PG连接执行；
标准账号、可信身份与参与者调度来自固定正式镜像，生产插件适配器/参与者实际接线。
同任务另执行PublishedManagementPostgresTest的15项真实管理集成，包含身份目录的
缺组404、零授权403和真实创建/更新/删除事务回归。
Controller互删/互停用检查一个成功及一个明确安全拒绝/冲突，不接受任意RuntimeException。
SQL和投影依赖故障保持503并验证无副作用。检查器核对两个suite的精确名称集合、PostgreSQL及hostImage/executable hash；摘要gatePassed仅
表示此门通过，不表示整仓可发布。正式HTTP/CSRF、插件浏览器及Compose仍须独立验收。
CI证据入口为`.evidence/lifecycle/results/TEST-io.kestra.extensions.rbac.applicationgroup.AccountLifecyclePostgresTest.xml`
及`.evidence/lifecycle/summary.json`；根仓T6必须核对具名用例、实际PostgreSQL和最终提交。

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
应用支持集由全部权限的 scopeKeys 并集推导，不提供独立写入口，不保留插件 approved。
当前配置 Kestra client 的同一权限实体是实际 typed 策略唯一来源；外部 details 仅是纯文本
说明，不授予本实例资源权限。enabled 只控制 Kestra 执行，不改变支持集。有效支持或
用户授予变化推进组 epoch；纯 name/description/details 不推进，真实 grants 变化只更新
策略版本。新评估读取同连接当前快照，固定评估快照最长60秒后失败关闭，不无限复用旧允许。

IDENTITY、LOCAL_POLICY 和 BUSINESS_INSTANCE 是机器契约的三个独立管理域，READ 与
MANAGE 不互含。用户界面分别称用户及应用组管理、Kestra 实际权限和实例资源管理。
LOCAL_POLICY 的空 `applicationGroupIds` 只指所属应用组，非空集合精确列出目标组。
BUSINESS_INSTANCE 不授予另外两个管理域。新增账号不自动授予任何 scope。

应用组入口是卡片。点击卡片先进入只读详情，显式点击编辑后修改。scope、应用支持和
用户授予使用各自对象修订；创建组子对象使用组修订，创建权限用应用修订，修改权限用权限修订。
写入必须发送强 If-Match。查看与修改分开检查，保存回执不附带未经 READ 授权的策略。

宿主签发、refresh、账号/client 生命周期与插件业务写入复用正式公开协议安全锁。
业务写入在可写 READ_COMMITTED 事务中重新校验可信身份、当前对象修订、支持与授予，
并保护有效管理员；失败回滚全部写入。标准身份状态只通过正式 OidcProtocolStateV1
读取，插件不查询宿主私有表。运行时使用当前应用组快照和实际策略，故障失败关闭。普通授权消费正式 `TrustedIdentityV2.currentForAuthorization()`；只有明确目录/策略依赖不可用传播宿主 `AuthorizationUnavailableException` 并返回503，安全拒绝与未分类异常保持拒绝。V1回退及生命周期借用连接契约不变。
scope 名称和说明不授予资源权限。旧项目路径、完整恢复和旧目录代理不保留兼容入口。

组内分页带 `directoryRevision`；消费者必须核对各页同版本。账号列表使用标准账号目录
自己的分页契约。删除非内置组要求对象修订匹配且没有 scope、应用或用户授予；退役 ID
不可复用。第一个内置应用组始终不可删除。

## 新安装应用组

先使用宿主正式首装流程创建初始账号和配置中的 UI client。停止业务 JVM，在同一部署的
数据库和持久卷上运行插件 CLI。命令要求 `RBAC_POLICY_JDBC_URL`、`RBAC_POLICY_JDBC_USER`、
`RBAC_POLICY_JDBC_PASSWORD` 和宿主完整 `KESTRA_CONFIGURATION`；它通过公开协议服务
核对 issuer、真实安装凭据、标准安装 ID、账号可登录性及已注册的应用。

`group-plan.json` 包含 `formatVersion:2`、`installationId`、`issuer`、`uiClientId`、
`builtInApplicationGroupId` 与一个 `applicationGroups` 项。该项包含 `applicationGroup`
（`id/displayName/description/active/revision/authorizationVersion`）、`scopes`（`key/name/description/revision`）、
`applications`（`clientId/description/audiences/permissions/revision`）及 `userGrants`
（`issuer/subject/scopeKeys/revision`）。`subject` 是成员的登录用户名（账号表 `username`
列），不是安装回执 UUID 或账号内部标识；安装校验与管理 API 都按登录用户名查询账号。
初始修订和撤权版本均为 1。UI client 是普通应用成员。
不接受 `firstPartyUi`、`projects`、旧 scope 字段或恢复快照。初始账号必须获授实际管理策略
所用的 scope。权限字段、enabled 和完整示例见 `docs/application-groups.md` 的“初始化 CLI
输入”段落。一次事务初始化目录及全部权限，不再运行策略或声明初始化 CLI；安装计划
必须提供实际 IDENTITY 与 LOCAL_POLICY 管理 grants，不按管理员名称推断。真实登录
仍属于部署验收，离线初始化不能证明会话/撤权已通过。

```bash
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupDirectoryCommand preview group-plan.json
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupDirectoryCommand apply group-plan.json <reviewed-approval-hash>
```

preview 不创建表或更改数据库，输出规范计划与完整计划的 SHA-256。apply 必须提供审查过的
同一 hash，并在协议安全锁内再次核对安装状态，只允许空目录初始化。它原子保存内置组、
scope、应用内全部权限、用户授予与撤权版本；不能覆盖已安装目录。启用上述配置并启动
JVM，完成部署仓的真实 new-install 验收。

## 离线 schema2 维护与显式迁移

旧 schema2 升级使用独立 declaration-upgrade，不并入 schema1 历史迁移。维护窗口停写，
使用正式产物和同一数据库环境，先导出 sourceHash，编写完整显式映射，再 preview/apply。
映射字段、冲突规则和初始版本见 `docs/application-groups.md` 的“schema2 declaration-upgrade
命令与显式映射”。不得从显示名或外部 details 推断 Kestra grants。

```bash
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationPermissionUpgradeCommand source
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationPermissionUpgradeCommand preview mapping.json
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationPermissionUpgradeCommand apply mapping.json <reviewed-approval-hash>
```

命令读取相同的 `RBAC_POLICY_JDBC_URL`、`RBAC_POLICY_JDBC_USER`、`RBAC_POLICY_JDBC_PASSWORD`
及完整 `KESTRA_CONFIGURATION`。旧策略/声明初始化命令不再作为新安装流程，旧表不再是
另一份运行事实源。升级后重新登录，验证真实允许/拒绝和旧凭据没有自动扩权，再结束维护窗口。
本轮真实 PG 发布门由 `scripts/check-declaration-upgrade-tests.py .evidence/postgres/results`
检查；检查器末行 JSON 由部署仓消费，H2 报告或旧 schema1 结果不能替代。

旧ApplicationScopeDeclarationMigration不再作为生产MigrationScript bean发现，即使
功能关闭且repository=postgres也不会自动创建旧声明表。旧迁移类及其scriptId/checksum、
显式ApplicationScopeDeclarationCommand和schema2升级读取保留，不能将它们作为默认
新安装步骤。NewInstallMigrationTest对实际生产迁移集合执行feature-off、五表初始化、
feature-on及再次关闭回归；PostgreSQL发布门还独立运行原declaration-upgrade八例。

### schema1 历史调查工具（非本轮 declaration-upgrade）

以下工具仅在兼容正式宿主 executable 与本插件 jar 的 classpath 使用，不是生产 HTTP
首次引导接口，也不自动创建登录账号或授予 scope。凭据必须放入环境变量或被忽略的
.env，不写入命令历史。首次初始化只允许空库；已有 schema1 必须显式审批迁移，不双读。

```bash
export RBAC_POLICY_JDBC_URL RBAC_POLICY_JDBC_USER RBAC_POLICY_JDBC_PASSWORD
export RBAC_POLICY_OPERATOR_SUBJECT
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.ApplicationGroupPolicyCommand validate <schema2-policy.json>
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
测试绿灯都不能代替真实操作。插件独立 Chromium 门覆盖语言、主题与窄屏，部署仓消费匹配正式 jar 的完整成功报告，
不重复主题及几何矩阵。

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
