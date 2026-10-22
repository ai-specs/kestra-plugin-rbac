# 应用组与 Kestra 权限实施设计

## 2026-10-22 当前候选与正式宿主输入

宿主提交 `de8a0c05d9f9b9832a52a33fee02b841ec9af453` 的 CI `39702163788` 全部通过，
正式镜像已推广。本仓 `host-contract.properties` 固定该 digest，`prepare-host.sh` 实际拉取
并提取公开契约及 UI SDK；没有读取相邻源码或使用未发布 jar 作为构建输入。

`ApplicationGroupDirectoryService` 同时提供只读协议投影、标准身份生命周期 guard 和
插件内部目录读取。应用组管理直接使用本仓模型、存储与事务。旧 `PublishedApplicationDirectoryAccess`
及 `PublishedApplicationGroupManagement` 已删除，不保留旧宿主 SPI 代理。首次初始化由
本仓 `ApplicationGroupDirectoryCommand` 完成；preview 不写库，apply 核对整份审查 hash，
通过公开协议服务验证本地安装凭据、账号和 client，在同一安全锁事务中创建内置目录。

组变更、实际策略提交、声明修改在事务内重新验证当前操作者。标准账号/client guard
复用宿主连接，分别检查用户管理和实际策略管理的有效管理员，不以声明元数据、首方成员
标记或名称推断授权。pending client 仅供支持登记和 consent 预览，不能绕过标准批准签发。
普通组删除及退役 ID、内置外键约束已在插件存储实现。API 输出 `builtIn`，UI 只对普通组
在显式编辑后显示删除入口；服务端仍独立拒绝内置组删除。

完整 `clean check` 已通过：Java 217 项零失败、零跳过，前端逻辑、翻译、构建与薄 jar 门通过。
新增目录管理 8 项和业务服务 12 项取代旧宿主业务代理检查；保留查看/修改分离、精确目标、
scope 不可改、旧字段拒绝、scope 删除引用、撤权、最后管理员和失败无副作用的断言，
不把移除的首方成员保留或旧目录分页代理行为计为新目标通过。
最终 PostgreSQL 发布门通过 75 项，零失败、零跳过，含自有目录存储 15 项、业务服务 12 项
和安装 CLI 5 项；前端逻辑 39 项通过。日志位于 `.integration/application-group-owned-final-check.log`
和 `.integration/application-group-postgres-final-tests.log`。插件 CI、独立克隆与 root 真实组合
尚未完成，不能以本地候选代替正式产物。下方带日期的旧候选说明仅作历史调查记录。

> **职责纠正（2026-10-21，优先于下方旧候选说明）**：应用组、scope、应用支持登记、用户授予、内置/退役约束及其存储全部归本仓。下方“宿主维护组目录、插件代理 CRUD、跨两套存储分步登记”的候选设计被替代，不作为当前目标。宿主只消费版本化只读协议投影，并负责标准账号/client 与 OIDC 签发校验；本仓只通过正式发布的公开协议契约检查账号、client 和安装前置，不读宿主私有表。应用权限元数据与组支持关系在本仓存储中维护，不能因旧归属而要求全组恢复或双存储配置状态。

## 职责迁移方案与风险

2026-10-22 权限恢复后的新增证据：插件自有业务模型、变更和存储在真实 PostgreSQL
执行 26 项，零失败。存储新增借用调用方连接的方法，不提交、回滚或关闭该连接；
只接受显式可写 `READ_COMMITTED` 事务。自行打开事务时先调用注入的安全锁入口，
再运行目录工作。新增三项测试验证调用方回滚、guard 写入回滚和错误事务状态拒绝。
日志为 `.integration/application-group-postgres-owned-tests.log`。这只验证插件自有存储；
新宿主 SPI 的正式产物接线、最后管理员业务判断及真实 API 仍未通过，不作为插件发布证据。

1. P0：先冻结最小宿主输入契约。只读投影包含当前 client 所属组、组启用/撤权版本、scope key/name/description、应用支持范围和指定用户授予；host 仍先独立验证 issuer、账号、client、audience 及签名，再验证投影。缺 provider、重复 provider、错误版本、异常和错误目标均失败关闭。公开接口不得提供组业务写入方法。
2. P0：组快照、首次内置 ID、scope 删除引用约束和退役 ID 移入本仓自己的业务模型与事务，不查询 host 的 `oidc_application_group_*` 表，也不保留旧宿主 CRUD 适配。账号/client 是否存在、能否登录及本地安装前置由宿主公开协议服务给出；接口候选未发布前不将其作为本仓编译输入。
3. P1：对账号停用、密码重置和最后管理员检查，先定义标准身份生命周期的最小公开 guard；对连接复用、锁顺序、并发和异常回滚须有真实 PostgreSQL 证据。不得为避免死锁放宽撤权校验，也不得让本仓读取 OIDC 账号私有表。
4. P1：管理 UI/API 直接调用本仓业务服务。只读详情/显式编辑、声明副本不作为运行时权限、支持与授予交集、零授权退出、缺插件及关闭功能的门保持不变。
5. P2：宿主独立通过测试并正式发布只读契约后，本仓取得固定 digest，完成适配、clean 编译和独立 CI；root 最后选择正式组合，替换安装 CLI 与存储观察入口并运行全部 new-install 门。历史宿主组表和旧 root 矩阵不代表新目标已交付。

以下旧候选源码和测试结果仍保留，只有与上述职责不冲突的协议、安全和 UI 结论可作为调查输入；原宿主目录编译、组表重放或 CRUD 探针通过不证明本仓业务归属已经落实。

### 插件自有存储候选证据（2026-10-22）

`ApplicationGroupDirectoryStore` 现以本仓模型保存组快照，创建 `rbac_application_group_state`、`rbac_application_group_bootstrap`、`rbac_application_group_invalidation` 和 `rbac_application_group_retired`。首次初始化固定内置 ID；数据库外键和删除检查保护内置组。组变更使用目录锁和预期修订，推进组修订与撤权版本；普通组必须清空 scope、应用和用户授予后才能删除。退役 ID 与删除在同一事务提交，禁止重新创建同名组。client 在安装范围内只能属于一个组。未知 JSON 字段、重复字段、无效引用和存储版本不一致不能作为有效目录读取。

本轮使用公开 Jackson、JUnit 和 H2 依赖独立编译业务模型与存储，没有使用任何宿主 jar。4项模型测试、7项业务变更测试和12项存储测试通过。新增 `ApplicationGroupChanges` 管理 scope 元数据、应用支持和精确 issuer/subject 用户授予，保留稳定 scope key；所有变更经不可变快照检查，scope 被任一应用支持或仍有用户授予时拒绝删除。支持范围和用户授予分别修改，只通过交集产生可获得 scope。业务变更在存储修订检查内运行，保存后同步推进撤权版本；失败的引用删除不能留下新修订。存储读取还要求独立撤权记录存在且与快照完全一致。

上述测试现为23项：4项模型、7项业务变更和12项存储。新增内置组数据库外键拒绝直接删除，以及删除与同 ID 创建并发时不能重置身份的检查。测试还覆盖并发写入、并发 client 登记、退役 ID、目录版本溢出后的完整回滚及损坏存储拒绝。日志位于 `.integration/application-group-owned-storage-compile.log` 和 `.integration/application-group-owned-storage-tests.log`；发布检查器要求所有新增测试零失败、零跳过。

新增存储测试已接入现有 `RBAC_STORE_TEST_JDBC` PostgreSQL profile，显式提供该变量时不得回退 H2。测试报告记录实际 JDBC 产品名，PostgreSQL 发布检查器要求这12项测试确实输出 `PostgreSQL` 且不能出现 `H2`。当前实际运行的是 H2，真实 PostgreSQL profile 未运行，不能将该门计为通过。

### 宿主业务测试退役后的归属

宿主已删除其旧可写目录、业务表、组 CLI 及相应32项旧模型检查。这些检查没有作为通过项转入本仓；新目标必须由本仓和部署仓证明，不能按数量替代覆盖：

| 行为 | 本仓当前证据与必须门 |
| --- | --- |
| scope key/name/description、支持与授予交集、引用约束 | 模型4项与业务变更7项通过；真实管理API与无副作用仍待验证 |
| 内置不可删除、普通组受限删除、退役ID、并发/CAS、损坏读取 | 存储12项H2通过；同套PostgreSQL必须执行，未运行不能记通过 |
| 首装计划、安装审批、内置ID与配置应用核对、初始化原子性 | 初始化CLI与业务接线待实现；只允许取得正式宿主输入后消费协议服务，不访问宿主私有表 |
| 管理授权、最后管理员、账号/client变更与策略/目录并发 | 业务服务与正式SPI适配待实现；宿主假guard探针不证明本仓实际角色与管理员判断正确 |
| 旧共享意图、首方成员类别、全组恢复与历史凭据迁移 | 不是新目标；不得恢复兼容路径。历史迁移仅显式profile，未运行不记通过 |

root 的 REST、MCP、安全、撤权、refresh、无副作用、UI、缺插件及关闭功能门保持必须。

该类是尚未接入管理控制器的内部持久化候选。`initializeBuiltin` 只执行存储初始化，不验证宿主安装审批、账号或 client 是否真实有效；调用方必须先通过正式宿主协议契约验证这些前置。DAO 不代替管理授权、最后管理员检查、账号生命周期保护或协议撤权校验。真实 PostgreSQL、Micronaut 装载、正式 CLI、业务服务接线和完整插件构建均未验证，不得将 H2 测试称为新安装验收通过。

宿主事务机制的当前设计要求同一事务连接、统一安全锁和固定锁顺序。本仓现有存储的独立事务入口尚未消费该机制；正式发布后必须增加受该契约约束的业务服务接线，不能直接把内部 DAO 注册成不带授权的管理 API。最后管理员检查必须使用真实账号可登录状态、配置登录 client 的有效注册、本仓支持与用户授予交集，以及 Kestra 实际管理策略；不能使用成员首方标志、权限元数据副本或仅仅存在一条用户授予来替代。

应用登记还必须支持在标准 owner consent 前处理有效 pending client。宿主候选新增只读
`pendingClient` 查询和 consent 预览，严格签发仍拒绝 pending。取得正式产物后，本仓登记
服务可以核对当前有效 client 或有效 pending 登记；后者只允许登记应用支持元数据，不得
标为已经能够取得 token，不得读取宿主私有表推断所有者。必须验证登记后预览、所有权
确认后签发，以及过期/撤销时不签发；当前未消费未发布接口，也未通过真实组合验证。

状态：目标设计与部分候选实现。根仓 `docs/application-group-target-model.md` 与维护者 2026-10-20 授权裁决是目标。宿主 `036145814a0abf9eed6c43953bc75e213b879585` 的 CI `39453574911` 通过并发布镜像 digest `sha256:f42f4bc98b419e59f165287ea460f0f9900a33ae76562784e9bbe2d5d6e11881`，但它只提供部分新契约。本插件工作树已加入声明存储、提供者、API 和部分卡片 UI；独立插件 CI、产物发布及根仓新模型组合验收尚待核对。旧项目路径、意图上界和完整恢复仍在源码中，不能声称目标模型已交付。

## 职责和事实来源

应用组是用户、应用和 scope 标识的组织范围。宿主 OIDC 目录维护内置应用组、scope `key/name/description`、应用成员及其已登记的支持 scope key、用户授予；宿主签发经验证的应用组 claim。插件通过已发布的公开目录 SPI 读写，不访问宿主目录表。

管理授权候选：宿主验证实际签名身份与目录成员关系后，调用 `ApplicationGroupManagementV1`。插件候选实现只从 `ApplicationGroupPolicyStore` 已提交的 Kestra 实际策略读取同一 scope 的 `IDENTITY.READ/MANAGE`，并分别判断当前登录身份与宿主构造的最后管理员候选；应用权限声明副本不参与。该 SPI 的最终正式镜像、插件 CI 与组合验证尚待完成；当前发布仍有共享管理意图与完整恢复依赖，不能将候选描述为目标交付。

插件维护两份明确分离的数据。`application_scope_declaration` 是应用主动登记的 `应用 × scope key × 权限 key/name` 元数据副本；它驱动授权页展示，并参与该应用可申请 scope 的范围检查，但不驱动 Kestra 资源授权。`kestra_application_policy` 是 Kestra 应用对 scope 的完整资源与动作策略，属于 Kestra 内部运行时权限事实来源。其他应用的代码自行执行其资源授权，不读取 Kestra 策略。

宿主成员支持范围与插件声明元数据必须同时存在，才算应用有效支持该 scope。创建支持关系时，先登记宿主成员支持范围，再保存插件声明；任何中间失败使签发端拒绝。撤销时，先停用插件声明，宿主签发和可信身份复查立即拒绝，再撤销宿主成员支持范围。插件必须记录并显示部分成功以便精确重试；不得把双存储写入称作原子事务。宿主删除 scope 在自身事务中检查所有成员均已撤销支持且用户授予均已撤销。并发登记与删除由宿主目录同一组写锁串行化。

宿主提供版本化、只读的应用声明投影 SPI，用于授权页显示和签发／refresh／已签发凭据在线复查。SPI 按应用组 ID 与 client ID 返回声明版本、支持的 scope key、每项权限的 key/name；组件缺失、多个实例、版本不符、读取失败或与宿主成员登记不一致均拒绝。宿主不能根据权限 `name` 推导角色或 Kestra 资源动作。只有 RBAC 应用组功能开启时才要求该 SPI；关闭功能的标准 OIDC 登录保持原有路径。

## 管理与安全

管理 API 路径统一为 `/api/v1/{tenant}/extensions/rbac/application-groups`。集合只读；详情读取需要对应 READ，写入需要 MANAGE；scope、应用登记、声明元数据、用户授予分别核对实际目标。所有写入需要宿主 CSRF、强 `If-Match` 与当前可信身份。内置应用组 ID 由安装状态固定，不提供可成功删除或改 ID 的路径。scope key 使用 `[a-z][a-z0-9-]{0,63}`，创建后不可改；name 必填且同组唯一，可独立编辑；description 可空且不参与授权。权限 key 在应用内唯一且稳定，name 必填、可编辑。

应用声明 API 按应用和 scope 列出权限 key/name，提供新增、详情、修改名称、删除。删除最后一项声明后，该应用停止支持该 scope；删除声明不自动改应用代码或 Kestra 实际策略。Kestra 实际权限另设明确页面和 API；页面不能把声明元数据的保存解释为实际策略保存。用户 scope 授予只改宿主目录，不自动增加应用支持或资源权限。

运行时同一请求固定当前可信身份和一份 Kestra 策略快照。同一资源动作必须由同一 scope 的一条完整 grant 覆盖；列表按 scope 内 READ/LIST 求交，再对 scope 求并，不跨 scope 拼接范围和动作。未知、已停用、已撤销、应用不再支持的 scope 混入 token 时整体拒绝。声明元数据改变本身不改变 Kestra 实际 grant；应用支持范围改变和用户撤权必须阻止旧 cookie、access token、refresh token 和 MCP session 继续获得权限。

## 页面和发布门

### 当前未发布源码检查（2026-10-21）

候选源码已删除管理 HTTP 的全组恢复路由、恢复授权方法及全组覆盖存储入口；管理页面不再提供恢复标签或跳转。目录修订变化不再要求重存整组 Kestra 策略；当前身份仍必须通过目录修订、撤权 epoch 和受众校验。scope 表单及 API 只接受标识、名称和说明；不再保存启停状态、资源意图或管理意图。

插件策略 schema 已删除 `directoryRevision` 字段。策略写入 API 不接受该字段，严格解析器拒绝携带该旧字段的输入；不提供兼容别名或自动确认路径。可信身份及宿主目录分页快照中的目录修订保留，用于当前身份校验和并发读取，不能混同为策略配置状态。策略中的 scope key 与组目录使用同一小写稳定标识规则。目录测试数据单独构造快照，不再从资源策略对象隐式取得目录修订。

插件策略 JSON 的组集合字段现为 `applicationGroups`，实际管理目标字段为 `applicationGroupIds`，Java 组值类型为 `ApplicationGroupPolicy.ApplicationGroup`。解析器严格拒绝旧 `applicationProjects/projectIds` 字段；默认新安装直接使用新 JSON，不经过旧字段重试或自动转换。`schemaVersion` 暂仍为 2，历史 schema1 迁移继续只在显式 profile 验证，本轮定向检查不表示历史迁移通过。

插件核心模型包现为 `io.kestra.extensions.rbac.applicationgroup`，类型为 `ApplicationGroupPolicy`、`ApplicationGroupPolicyParser`、`ApplicationGroupAuthorization`、`ApplicationGroupPolicyBoundary`、`ApplicationGroupManagementSafety`。运行时服务、资源适配和配置工厂分别为 `ApplicationGroupRuntimePolicies`、`ApplicationGroupTypedPolicy`、`ApplicationGroupRuntimeConfiguration`；存储、管理写入、离线命令分别为 `ApplicationGroupPolicyStore`、`ApplicationGroupPolicyManagement`、`ApplicationGroupPolicyCommand`。旧类文件源码已删除，不添加兼容转发。发布检查器同步新测试类的完整名称；发布 jar 必须在正式宿主输入下 clean 编译并验证无旧类，当前尚未完成该门。

新安装实际策略表使用 `rbac_application_group_policy_head` 和 `rbac_application_group_policy_snapshot`。不把旧项目表作为新模型读写入口，不提供自动表别名或物理转换。新增定向存储测试在独立空库执行两次初始化，核对新表存在且旧项目表不存在，保存并修改策略后从新实例和实际 SQL 重新读取；H2 `1/1` 通过。PostgreSQL 同一测试仍待执行，未将 H2 结果宣称为 PostgreSQL 持久化验收。

schema1 建表 bean `PolicyStoreMigration` 现额外要求 RBAC 已开启且应用组功能未开启。RBAC 关闭的 OIDC 首装阶段以及应用组启用阶段，不应自动创建旧策略表；独立旧模式保留自身初始化，不作为新应用组模式的存储入口。该注解变更已在已发布宿主输入下编译，Micronaut 条件元数据的完整生成和实际启用行为仍待正式 clean 编译及 CI。根仓新增真实表集合门，必须在新安装启动后实测所需应用组表存在且旧项目/schema1 策略表不存在，不能仅靠类名或本段源码判断通过。

删除普通组后的应用内部策略记录可以原样保留，但不形成当前目录目标。运行时管理检查要求目标确实存在且 issuer 匹配；旧组策略和指向已删除组的旧管理 grant 不得继续授权。编辑其他组时，仅允许这些不再可达的映射原样保留或撤除；新增或更改为不存在的管理目标拒绝。候选校验使用前一次已提交策略确认保留关系，最后管理员检查只考虑当前目录可达组和 scope，避免把孤立策略当作管理员。本轮控制器测试用真实策略存储验证其他组保存成功、旧组读取及能力查询拒绝、新无效目标写入拒绝且无存储变化；真实组删除后的 OIDC 组合仍须正式产物验证。

应用组卡片数据只来自目录；scope 列表只展示 key、角色名称和说明，不合并 Kestra grants 或生成绑定状态。表格不再附加默认操作列，记录标识为可交互入口。普通应用表单不显示首方 UI 标志。13 语言目录已删除身份目录、本地绑定及分开保存相关旧翻译键，并将应用组、应用、scope 授予和角色名称文案统一。

Kestra 实际权限表单已从组概览、scope 详情及旧绑定创建路径移到具体应用详情，与应用声明元数据并列。当前应用由宿主公开配置 `micronaut.security.oauth2.clients.kestra.client-id` 标识；此值只定位当前应用，不授予管理权限，不从 client 名称、首方标志或角色名称推断应用身份。配置缺失时不显示实际权限表单。`GET /capabilities?applicationGroupId=<id>` 返回该目标的独立查看、修改能力以及配置中的当前应用 ID；未知目标或跨 issuer 请求拒绝。页面必须同时匹配目标组、当前应用 ID 及实际权限 READ；修改仍要求 MANAGE，表单初始只读。

实际策略候选 API 为 `/api/v1/{tenant}/extensions/rbac/application-groups/{id}/applications/{clientKey}/runtime-permissions`，`clientKey` 是现有公开 base64url 标识编码。GET 需要目标 READ，只返回 `revision/applicationGroupId/clientId/policy`，不返回其他组或全局策略包装；尚无策略时 `policy` 为 null。POST 创建配置，PUT 修改 issuer、audiences、enabled；配置体不含组 ID、scope 或目录修订。`/scopes` 的 POST 新增实际策略，`/scopes/{key}` 的 GET/PUT/DELETE 查看、修改或删除实际策略；key 使用稳定 scope key，不使用旧 opaque scope 编码。写入体为 `scopeKey/description/enabled/grants/managementGrants`，路径与体内 key 必须相等。旧 `name`、`id`、`directoryRevision` 输入拒绝。

每个实际策略请求要求配置中的当前应用与路径 client 精确匹配，并校验应用组目标和所需操作。关闭功能返回 404，当前应用配置缺失返回 503，其他应用返回 404。写入需强 If-Match；成功只返回 `{revision}` 和 ETag，不借 MANAGE 返回完整策略。仅有 MANAGE 时可以从能力响应取得 `runtimePolicyRevision` 并执行已有明确输入的写入，但 GET 仍拒绝。UI 保存后重新取得目标能力，仍有 READ 才单独读取策略；不把回执伪装成 READ 权限。旧 `/policy`、`/projects` 实际策略路由及 `/roles`、`/bindings`、`/role-mappings` 处理器已删除，不保留 410 兼容入口。

实际策略入口额外核对配置中的当前应用确实是目标组登记成员：通过公开目录适配器按当前组快照修订读取精确 client 成员，不增加 IDENTITY 权限，也不要求首方标志。目录适配器仍核对 issuer、分页期间的组修订和唯一 client；控制器再检查返回的组 ID、client ID 和正成员修订。缺登记保留 404；缺数据、错组、错 client 或无效修订返回 503；任何失败不得修改实际策略。配置中的 client ID 只用于定位应用，不能代替成员登记。单纯 READ 或 MANAGE 不会因此获得组目录写入能力；该检查最多调用只读成员 FIND，不调用成员创建、更新或删除。当前定向测试使用模拟目录，真实目录和并发变化仍须正式产物组合验证。

组目录候选 HTTP 路径已改为 `/application-groups`；其 scope、应用、用户授予分别使用 `/scopes`、`/applications`、`/scope-grants`。旧 `/identity/projects` 路由已删除。UI 使用同一组路径，空入口进入应用组卡片；旧 project、role、binding 页面路径不会自动转入新入口。UI 模块改名为 `application-group-model.js` 和 `ApplicationGroupList.vue`，无旧模块转发文件。

scope 的公开创建、修改输入只有 `key/name/description`。详情路径中的 key 为小写稳定标识，旧 scope base64 编码不接受。公开响应使用插件 `ApplicationGroupScope` DTO，字段为 `applicationGroupId/key/name/description/revision`；不序列化宿主旧字段及权限意图。宿主返回其他组、错误 key 或无效字段时返回 503。列表另带当前目录分页快照修订。宿主 OIDC 公开 SPI 当前仍使用 `projectId/scope` 类型字段，插件暂在单一目录适配边界读取；该 SPI 及其存储、策略 JSON 的全栈改名仍是明确剩余工作，不能把插件 DTO 投影当作宿主模型已经完成。

普通应用成员输入使用 `clientId/approved/audiences/supportedScopeKeys`。响应 DTO `ApplicationGroupApplication` 使用 `applicationGroupId`，不输出 `projectId/allowedProjectScopes/firstPartyUi`。用户 scope 授予输入使用 `issuer/subject/scopeKeys`；响应 DTO `ApplicationGroupScopeGrant` 使用 `applicationGroupId/issuer/subject/scopeKeys/revision/authorizationVersion`，不输出 `projectId/projectScopes`。详情与写入响应必须与请求的应用、用户和应用组精确匹配；用户授予的 issuer 也必须匹配当前可信 issuer。错误投影返回 503。

普通成员管理 API 不再接收首方 UI 标志。新增成员时适配器显式使用 false；更新成员时按同一目录快照读取现有成员，保留其宿主标志，不从 client 名称、ID 或应用权限推断该值。安装流程仍按宿主安装凭据建立必要首方登录关系。旧成员和授予输入字段、直接传入的 `firstPartyUi` 均拒绝，不提供兼容别名。此适配器变更已补充独立测试源码；完整执行须等待匹配宿主正式发布，目前不能列为通过证据。

应用权限声明页面不再要求已删除的 `scope.active`。可新增声明的范围是当前组定义的合法 key、应用登记的支持 key，并排除已登记的声明；未知 key 和未登记支持的 key 不可选择。相应定向测试证明只有 `key/name/description` 的 scope 可以正常被选择，声明副本仍不产生实际资源权限。

主控裁决：应用内部策略可以保留未在当前组目录定义、或已删除 scope 的映射。这些记录不是 scope 声明或用户授予，不得因其存在阻塞其他策略编辑。签发范围仍来自组目录、应用主动支持登记和用户授予；运行时仍要求每个请求 scope 当前有效，混入未知 scope 必须整体拒绝。未在目录中可达的管理 scope 不得维持最后管理员。实际权限页面可以展示并删除这些应用内部记录，不得把它们合并到组 scope 列表。对应定向测试验证保留旧映射时已知角色正常授权、未知角色及混合请求拒绝，并验证不可达管理角色不能替代最后管理员。

目录投影使用独立 `ScopeCatalog/DirectoryScope`，不构造空权限的实际策略。纯策略定向测试 `38/38`、存储管理定向测试 `7/7`、控制器 Java 层定向测试 `18/18` 通过，输入为已发布宿主的公开类型；控制器测试使用模拟的宿主目录及真实策略存储，不替代真实 HTTP、身份验证或 CSRF 检查。UI 逻辑测试 `39/39`、公开 UI SDK 翻译检查和 UI 构建通过，包含声明范围及新成员字段测试。移除的 5 项 UI 测试属于已删除的恢复编辑器及恢复修订 helper；旧目录绑定编辑器的 2 项模型测试由当前应用识别、元数据不授予权限、实际策略编辑共 3 项测试替代，并新增精确目标策略投影及 `constructor` scope 的原型属性拒绝测试。原 scope 元数据和保存测试已改为目标行为，未将旧恢复测试跳过计为通过。Java 目录投影的 4 项旧意图测试由独立目录模型的 6 项测试取代，控制器新增只读拒绝写入、错误应用与功能关闭无副作用、scope key DTO、成员与授予 DTO、旧输入拒绝和错误宿主投影测试。目录适配器测试源码新增 2 项，期望完整矩阵为 8 项，但尚未完整执行，不计入上述定向通过数。发布检查器仍要求所有保留及新增测试零失败、零跳过。

上述检查不是完整插件构建或最终发布证据。正式宿主先发布的门尚未执行，插件必须用其固定 digest 重新完整编译并运行自身 CI。最终管理 HTTP、UI 权限分离、撤权、缺插件和组合无副作用仍由根仓复测；不得用此次定向测试代替这些门。

应用组集合使用卡片；卡片进入只读详情，点击显式“编辑”才可修改。概览不显示全组授权配置状态。scope 页管理 key/name/description；应用页管理支持范围和权限声明 key/name；用户页管理 scope 授予；Kestra 实际权限页只展示 Kestra 策略。旧“身份目录”“本地绑定”“完整应用组恢复”不出现在用户文案和路径。主题、语言、窄屏和键盘焦点按已发布宿主 UI 契约复验。

必须先取得宿主正式镜像与公开 SPI，随后独立编译插件 jar、通过插件 CI 并发布。根仓使用正式镜像与 jar 运行 `new-install` REST、MCP、安全、撤权、refresh、无副作用、UI、缺插件与关闭功能矩阵；最终根仓 CI 报告必须对应最终提交。schema1 与物理回滚只进入显式 `legacy-migration` profile，未运行不计通过。
