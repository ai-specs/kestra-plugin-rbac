# 应用项目后端实施设计

状态：候选、未发布。业务设计归本插件。本文件不接收或发布宿主契约，不表示 HTTP
API 已实现。当前 RuntimePolicies、ManagementController 仍运行 schema1 与 subject
管理员；新 SPI 未正式发布前不得编译或装载候选 adapter。不得同时启用两套普通授权。

## 1. 正式产物接收门

主控须提供成功宿主 CI、正式镜像 digest、公开契约说明及产物可获取证据，再按独立
README 入口提取依赖。当前 host-contract.properties 不变。所需公开能力包括：

- 验证后的 subject、issuer、适用 audience、tenant、project、client 绑定、项目 scopes
  与协议 scopes 分类、身份认证版本和目录修订；不读取任意 Authentication attributes
  猜测这些值，不解码未验证 token，不把 roles 改名为 scopes。
- 一致的当前共享目录快照、资源意图和管理意图上界、成员 client、subject 授予及真实
  管理员可达性；目录管理与用户管理使用所属宿主公开服务，不访问宿主数据库表。
- 项目及身份修订的并发写入保护、最后管理员保护、UI/API/MCP 撤权与旧凭据失效机制。
- 关联 namespace/ID 查询域的真实消费入口，以及 UI、REST、MCP 共用的评估生命周期。

以上名称是能力需求，不预设未发布 Java 类名、方法签名或 HTTP 实现。

主控已核对候选宿主的字段语义：Snapshot.directoryRevision 等于 Project.revision，
TrustedIdentityV1 传播该目录快照 revision。authorizationVersion 是独立 token/session
撤权 epoch，不是目录修订；即使正常 mutate 时二者同时加一，也不得互换。
候选公开 PolicyBoundary 提供 project(revision,authorizationVersion)，adapter 必须取
revision 写入本地 Project.directoryRevision 和 Identity.directoryRevision，并核对快照
一致。authorizationVersion 只由宿主认证/撤权机制校验，不能拿它确认本地绑定。
纯模型测试使用目录修订 3 与 epoch 97，验证身份、本地和上界任何一处误用 epoch 都拒绝。
该测试没有引入或编译候选宿主类型，不能替代正式 adapter 的字段映射测试。

## 2. Adapter 与评估边界

新的运行时服务每次完整评估只调用一次 ProjectPolicyStore.readCurrent，固定其 policy
和 revision，并固定一份宿主可信目录快照。其不可变评估对象持有 ProjectAuthorization，
所有子操作、列表、总数、统计和聚合都用此对象，不在授权中途再取本地策略。
下一次评估必须读取当前状态；存储或身份服务异常拒绝，不保留旧允许缓存。

单动作必须匹配一个完整 grant；列表使用 authorizeList 的关联项，不把 namespace 与
ID 拆成两个独立集合，不支持关联查询的入口失败关闭。宿主操作到本地资源/动作使用
穷尽显式映射，未知类型拒绝。METADATA、MCP_SERVER 只按已裁决的 TENANT/main
例外；BUSINESS_INSTANCE 不是任意资源旁路。复合操作全部子操作通过后才允许副作用。

HTTP 管理 adapter 使用宿主认证及 CSRF 链。main 以外拒绝；身份目录、普通本地
策略、完整恢复分别授权。LOCAL_POLICY 必须传实际目标项目；IDENTITY 必须匹配精确
issuer。READ 不授予 MANAGE，不能借用其他 scope 的管理动作或目标。
恢复授权仅忽略本地目录版本过期，其他信任检查保持；保存必须完整重验当前全部上界。

保存先校验强 If-Match、严格 JSON 和不可变路径标识，再校验权限、当前目录上界和
真实最后管理员可达性，最后 CAS 保存。跨系统不能宣称单数据库事务；目录在校验与
本地提交间变化时需要正式服务的版本保护或等价接收机制，否则拒绝并要求重试。
现有 DirectoryBoundary 回调尚不能自行证明这一点。身份写入不顺带创建本地 grants，
本地保存不顺带授予用户 scope。部分成功明确返回并重新读取，不能补授权。

候选错误语义：401 未认证或身份过期；403 无权；400 输入无效；404 无可见目标；
409 已存在/目录变化；412 修订冲突；428 缺 If-Match；503 依赖不可用。响应保留
`{code}`，不输出 token、密码或存储异常细节；成功 GET 与策略响应 no-store。

## 3. UI client 路径和请求体核对

以下逐项核对隔离树 ui/src/client.js、project-model.js 和 App.vue，仅只读。
公共前缀为宿主 SDK 的 apiBase（含 /api/v1/main）加 /extensions/rbac。
P 表示项目 ID；K 表示 UTF-8 base64url 无 padding、强制 `~` 前缀的规范标识。
后端必须严格解码并回编码核对 K，拒绝非规范编码、非法 UTF-8 和路径/请求体不一致。
现有本地服务接收解码后的 scope 原名；不能直接把 K 当原名。

| client 方法 | HTTP 与相对路径 | 输入/修订、目标对应 |
|---|---|---|
| capabilities | GET /capabilities | 目标返回 allowed、identityManagement、localPolicyManagement、needsReconciliation；旧控制器缺后三字段，当前不兼容 |
| policy | GET /policy | 目标 `{policy:schema2,revision,mode,readOnly}`；仅可见目标投影，不泄露其他项目；旧控制器返回 schema1，当前不兼容 |
| createProject | POST /projects | `{id,issuer,audiences,enabled,directoryRevision}`，本地全局 revision；对应 createProject，不接受 scopes |
| updateProject | PUT /projects/P | 同上，id 不变；目录修订必须不变；对应 updateProject |
| reconciliation | GET /projects/P/reconciliation | `{project,policyRevision,directoryRevision,upperBound:{project,scopes}}`；只给获准目标 |
| reconcileProject | PUT /projects/P/reconciliation | 完整项目含 id 与全部 scopes；If-Match 为 policyRevision；对应 reconcileProject，返回完整可见 policy view |
| scopes / scope | GET /projects/P/scopes；GET /projects/P/scopes/K | 尚无 HTTP 实现；不得用 identity scopes 代替本地绑定 |
| createScope | POST /projects/P/scopes | `{name,description,enabled,grants,managementGrants}`；本地全局 revision；对应 createScope |
| updateScope / deleteScope | PUT /projects/P/scopes/K；DELETE 同路径 | PUT 同上且 name 不变；DELETE 无 body；本地全局 revision；对应 replaceScope/deleteScope |
| identityProjects / identityProject | GET /identity/projects；GET /identity/projects/P | 前者 page/size；宿主共享目录公开服务，插件只做代理和管理域授权 |
| createIdentityProject | POST /identity/projects | `{id,tenant:"main",displayName}`，无 If-Match（创建）；需正式宿主契约确认 |
| updateIdentityProject | PUT /identity/projects/P | `{displayName,active}`；项目 revision |
| identityScopes / identityScope | GET /identity/projects/P/scopes；GET /identity/projects/P/scopes/K | 前者 page/size；共享意图，不是本地 grants |
| createIdentityScope / updateIdentityScope | POST /identity/projects/P/scopes；PUT /identity/projects/P/scopes/K | `{scope,description,active,resourceIntents,managementIntents}`；创建用项目 revision，更新用 scope revision |
| clients / client | GET /identity/projects/P/clients；GET /identity/projects/P/clients/K | 前者 page/size；K 为 clientId |
| createClient / updateClient / deleteClient | POST /identity/projects/P/clients；PUT/DELETE /identity/projects/P/clients/K | `{clientId,approved,firstPartyUi,audiences,allowedProjectScopes}`；创建用项目 revision，更新/删除用成员 revision；DELETE 无 body |
| assignments / assignment | GET /identity/projects/P/assignments；GET /identity/projects/P/assignments/detail?subject=... | 前者 page/size；subject 为 encodeURIComponent 查询值，不能按 base64 解码 |
| createAssignment / updateAssignment / deleteAssignment | POST /identity/projects/P/assignments；PUT/DELETE /identity/projects/P/assignments/detail?subject=... | `{issuer,subject,projectScopes}`；创建用项目 revision，更新/删除用 assignment revision；DELETE 无 body |
| users / user | GET /users；GET /users/detail?subject=... | 前者 page/size；需改为 IDENTITY 授权，不能复用永久 subject 旁路 |
| createUser | POST /users | `{username,name,email,password}`；无 If-Match（创建） |
| updateProfile | PUT /users/profile | `{subject,name,email}`；用户 revision |
| updateState / resetPassword | PUT /users/state；PUT /users/password | `{subject,active}` / `{subject,password}`；用户 revision；宿主处理认证撤销和真实管理员保护 |

除两个创建资源请求外，client 所有写请求均要求 revision 并发送强引号 If-Match；
没有提供 revision 的本地调用会先报错。分页期望 `{items,total,page,size}`，本地收集器
会检查完整性。相同 total 不证明目录快照一致，正式契约还须确定目录变化的修订保护。
client 不自行发送 CSRF token，必须由正式 SDK request 提供已验证保护；不能因此删除
服务端 CSRF 门。项目 ID 为受限字母数字编码，scope/client ID 为 K，subject 为查询值。

核对结论：本地项目/完整恢复/完整 grants 的请求体与候选服务一致，但只有 Java 服务，
没有 HTTP 路由、读取投影和权限接线。身份目录路径全部尚无插件控制器；其字段和各类
revision 归属必须对正式宿主契约核对后才可实现，不能记录为已经一致或可以联调。
旧 /users 路由形状相同但权限模型不合目标。capabilities、policy 当前明确不兼容。
禁止为迁就页面把身份业务、client 或 assignment 写入插件表。

## 4. 旧消费者清理清单与保留理由

本表是正式切换时的定点变更清单；当前未删除，以免未发布 SPI 下形成不可运行状态。

| 文件/消费者 | 切换动作与验收 |
|---|---|
| RuntimePolicies | 删除 administrativePolicy、administrators、isAdministrator、protectedSubjects、schema1 snapshot；替换为 schema2 不可变评估；无 subject 旁路 |
| PolicyEngine | 删除普通 effectiveRoles、subject/roleMapping 与 instanceAdminRoles 授权；最终只允许离线迁移保留必要旧语义分析，不注册成运行时 bean |
| ConfiguredEvaluationProvider | 改为正式可信身份+目录+单策略快照的评估工厂，追踪全部子操作 |
| ConfiguredTypedAuthorization、ConfiguredRbacAuthorizer | 改为共用新评估；不能从 legacy principal.roles 补授权，旧无可信项目身份入口必须拒绝 |
| SafeFlowDependencies | 删除 PolicyEngine 测试构造/运行时依赖；保留安全 ProcessedFlow 声明检查，使用同一新评估 |
| management/ManagementController | 删除 /roles、/bindings、/role-mappings 及 subject guard；实现上表本地、目录代理和账号路由；未知旧写路由不产生副作用 |
| management/PolicyManagementService | 删除旧普通 role/binding/mapping 管理运行服务，不迁移为 scope 间接角色 |
| management/UserDirectoryAccess 及实现 | 只消费正式身份目录公开服务，重做 IDENTITY 权限与最后管理员保护；删除 protectedSubjects 规则 |
| RbacConfiguration、store/PolicySourceSelection | 删除永久 management-admin-subjects 和旧策略运行字段消费者；旧字段显式启动失败，不能静默忽略 |
| store/JdbcPolicyStore、PolicyDocumentValidator、PolicyDocument | 仅保留离线源策略读取/审批所需能力；不得从新运行时注入或双读，旧快照保留作备份 |
| store/PolicyImportCommand | 移除旧生产初始化入口；文档改为正式审查后的 schema2 命令，不提供 schema1 回退授权 |
| examples/management-policy.json、README、management-api.md、authorization-flow.md | 新默认示例和命令改 schema2；旧调查保留历史标记，不把旧永久 subject 管理员写成当前建议 |
| 旧 RuntimePolicies/InstanceAdmin/PolicyEngine/TypedPolicy/ManagementController/BeanDiscovery 等测试 | 替换生产断言，不通过直接删除安全断言获得绿灯；必要旧测试限离线迁移语义 |
| UI 旧页面、翻译和根部署脚本 | 由 app-ui 与主控分别负责，本范围只提供消费者证据，不修改 |

## 5. 迁移审批核查与未覆盖项

ProjectMigrationReview 已要求每个旧 role 明确 MAP 或 DROP、原因、有效目标 scope；
报告包含完整旧策略、目标策略、roleDecisions、每角色 added/removedGrantDefinitions、
legacyCrossRoleListDependencies 和确认警告。审批 SHA-256 覆盖整个规范报告、操作者、
源 revision 和源/目标规范 hash；readReview 会根据内嵌输入重新生成报告，防止修改
差异后只重算外层 hash。migrateApproved 再锁源修订、核对源内容、目标与操作者，
不改写旧策略。对象键与集合数组排序、去除空白形成该版本内部规范，不宣称通用 JCS。

这些证据有明确限制：grant 差异是定义比较，不是集合包含或实际授权行为差异；例如
序列化默认字段差异也可能列为变化。跨角色记录只检查 allIds READ/LIST、资源、租户和
命名空间交集，并标记不能按分离 scopes 保留；它不是逐 subject 的最终权限损失报告，
没有证明该 subject 实际拥有两角色，也未计算目标完整 scope 是否恢复该列表范围。
commonNamespace 现在只对两个明确 NAMESPACE 且 namespace 非空的旧 grant 求交，
不得把 TENANT 的空 namespace 当作任意 namespace。报告单列
legacyNonNamespaceGrantsRequiringReview，明确 automaticNamespaceMappingAllowed=false。
旧 INSTANCE/SYSTEM grant 被现有 schema1 验证器拒绝，不能先删字段或降成 namespace
再生成审批。TENANT 也只能保留原定义并人工裁决明确目标，不能静默映射；MAP 是显式
候选对应，不证明语义等价或真实账号已经迁移。改变报告内容会改变审批 hash，旧审批
不能直接套用于新生成报告，必须重新审查。
旧动态角色映射的真实用户集合更不能从策略 JSON 推断。requiredConfirmations 是警告，
不是已经执行的管理员登录、撤权或恢复演练证据。

生产切换必须附真实账号/真实 client 的批准授予清单、前后允许/拒绝矩阵、预期增减与
需求依据，特别覆盖跨角色 READ/LIST 损失；再核对可信当前目录、实际管理员可达性和
最后管理员保护。离线命令当前没有这些可信输入，不能作为独立生产切换批准门。
不得用手工签名 Identity DTO 或 reachableManagementScopes 集合替代真实登录证明。

现有纯模型可以验证 grant、规范 hash、审批、存储 CAS、上界和目标关联。必须等正式
宿主产物才能实施：可信身份 adapter、真实目录上界转换、目录并发保护、HTTP 管理与
用户服务、管理员安装/切换、旧凭据撤销、运行时旧链删除、真实 REST/UI/MCP 验收。
届时仍需 PostgreSQL 检查、独立插件 check/CI/发布、Compose 与拒绝无副作用验收。
当前定向纯模型测试通过不能写成整个插件交付。

## 6. 主控裁决：部署管理员迁移与 BUSINESS_INSTANCE 映射

状态：已接收的实施设计，接线、发布与真实验收均未完成。此节具体化第 2 节的显式
typed 映射，不将 BUSINESS_INSTANCE 解释为任意操作通行旁路。

旧真实部署的永久 subject 管理员必须转为由身份端实际授予的显式项目 scope，且由
可信目录同 scope 的 managementIntents 与本地 managementGrants 同时提供完整边界。
不得按 scope 名称含 admin、旧 subject 名称、roles claim 或旧部署管理员名单推断权限。
普通项目 scope 仍按完整 grants 授权，不借用管理 scope 的 namespace 或动作拼接。

BUSINESS_INSTANCE 仅适用于可信当前 project、tenant=main。其语义是该项目在 main
中的全部 namespace 实例管理边界，projectIds 必须为空，不跨项目，不扩大到其他 tenant。
只有宿主已经成功解析的明确 typed 操作可以进入以下穷尽映射：

```text
资源：FLOW / EXECUTION / NAMESPACE / LOG / METRIC / TRIGGER / KVSTORE /
      NAMESPACE_FILE / METADATA / SYSTEM / MCP_SERVER / DASHBOARD / BLUEPRINT

BUSINESS_INSTANCE.READ   -> READ / LIST
BUSINESS_INSTANCE.MANAGE -> CREATE / UPDATE / DELETE / EXECUTE / ACCESS_OUTPUTS
```

READ 与 MANAGE 不互含。管理 READ 不能写入、执行或取输出；只有 MANAGE 不能读取或
列表。复合操作必须分别满足实际子操作，不能只因入口的 EXECUTE 或 UPDATE 已允许就
跳过其余检查。映射只授予明确操作，不保证资源具有每一种业务动作或路由。
SYSTEM 在此是明确 typed 资源，不授权把所有未知操作降成 SYSTEM；不能为适配它而
把普通 ProjectPolicy.Grant 放宽为 SYSTEM、INSTANCE 或全 namespace 通配。

异常、未知资源、未知动作、未知路由与 EXTENSION 均拒绝。资源表未包含 SECRET、AI
或任何后续新枚举，不得随宿主枚举增加自动扩大；新增项必须重新裁决。EXTENSION 页面
和 API 必须按实际所需 IDENTITY 或 LOCAL_POLICY 管理域授权。BUSINESS_INSTANCE
不能授予身份目录、账号/client/assignment 管理、本地绑定管理或完整恢复权限。
宿主路由解析失败必须先拒绝，不能再尝试管理域映射作为兜底。

列表范围必须表达上述 main/当前项目的完整实例边界，同时满足宿主已解析操作的查询
范围；不能使用空 namespace 列表造成全部拒绝，也不能跳过 tenant 或项目核对。
普通 namespace/ID grants 的范围计算继续保持关联。正式 adapter 需要分别验证允许
读写、执行、输出，以及错误项目/tenant、未知路由、未知 scope 混合和 EXTENSION 拒绝。
执行/输出/写入必须分别用真实请求验收，拒绝操作比较持久化、队列和延迟副作用。

### 与 application-projects 约束的核对

本次裁决与用户新模型没有实质冲突，理由如下：

- 第 3 节及第 12.2 节禁止跨 scope 拼接限定条件；本映射由同一个显式管理 scope 独立
  表达完整项目/main/namespace 边界与动作，不从别的 scope 借条件。
- 第 4 节要求全局或租户例外逐项裁决，不得无条件开放 TENANT、SYSTEM、INSTANCE。
  此处使用固定资源/动作表和可信项目限制，保留未知操作失败关闭，不修改普通 grant 规则。
- 第 12.4 节要求管理 scope 和完整范围在实施前冻结、普通业务 scope 不取得管理权。
  本裁决冻结 BUSINESS_INSTANCE 的业务资源边界，IDENTITY 与 LOCAL_POLICY 仍独立。
- 文末“管理模型冻结补充”已接收 BUSINESS_INSTANCE 并要求只经显式 typed 资源表集成；
  “已接收的非命名空间资源范围”要求它不得成为普通业务资源的通行旁路。本节把该表
  具体化，不将 authorizeManagement 的布尔结果应用到任意路由。

现有纯模型只提供管理域判定，尚未实现上述 typed 操作映射。不得把已接收设计写成
当前能力。共享 application-projects.md 本轮不修改，由主控协调同步这一具体资源表。

### 旧 revision 3 备份的迁移输入

以下由主控报告，本轮未访问或改写真实备份与原卷：旧 policy revision=3 包含
examples-reader、examples-writer 的精确 examples grants，普通测试账号 mgmt-test
绑定两者；admin 权限来自部署 subject 名单。原卷未修改。

迁移必须分开记录两种来源：数据库中普通 roles/subjects 的明确对应，以及配置中的
admin 永久 subject 旁路。后者不在旧 policy JSON 中，现有 ProjectMigrationReview
的 roleDecisions 和 grantDifferences 无法自动覆盖，不能因报告通过而漏掉管理员迁移。
必须单列经操作者批准的部署授权来源、移除项、新项目 scope、完整管理域和动作、身份
端实际授予、client 与 audience、重新登录及旧凭据失效证据；不能只改旧 JSON。

examples-reader/writer 必须保持精确 examples 边界，不能迁成 namespace 子树或实例
管理范围。须依据实际 grant 动作判断同 scope READ/LIST 完整性，不能仅按角色名猜测。
mgmt-test 必须改为由身份端批准授予真实项目 scopes，旧 subject 双角色绑定不再生效；
需对照原实际动作验证迁移增减，不能默认它与 admin 一起获得 BUSINESS_INSTANCE。
admin 的新 BUSINESS_INSTANCE scope 只承接上表明确语义；是否另授 IDENTITY 与
LOCAL_POLICY 必须分别明确批准，不能从旧管理员身份自动推导三域权限。

该输入不包含已核对的备份文件路径/hash、完整 grant 内容或部署配置 hash，因此本节
不是可执行迁移计划，也不生成批准目标。主控后续必须核对实际源文件、配置与 hash，
在维护窗口按匹配正式产物完成真实管理员切换、普通账号对照与恢复演练。
