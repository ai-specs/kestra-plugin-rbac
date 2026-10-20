# 应用项目后端实施设计

> 历史实现设计：当前实施目标见根仓 `docs/application-group-target-model.md`。本文中的旧应用项目、项目级 grants、目录上界和恢复流程只用于差异调查，不能作为新实现的业务依据。

状态：schema2 实现已接入正式宿主公开契约；本地独立检查通过，插件 CI、正式发布和组合验收尚待核对。本文记录设计职责与迁移清理依据。

## 1. 正式产物接收门

主控须提供成功宿主 CI、正式镜像 digest、公开契约说明及产物可获取证据，再按独立
README 入口提取依赖。当前 host-contract.properties 固定已发布的应用项目宿主。所需公开能力包括：

- 验证后的 subject、issuer、适用 audience、tenant、project、client 绑定、项目 scopes
  与协议 scopes 分类、身份认证版本和目录修订；不读取任意 Authentication attributes
  猜测这些值，不解码未验证 token，不把 roles 改名为 scopes。
- 一致的当前共享目录快照、资源意图和管理意图上界、成员 client、subject 授予及真实
  管理员可达性；目录管理与用户管理使用所属宿主公开服务，不访问宿主数据库表。
- 项目及身份修订的并发写入保护、最后管理员保护、UI/API/MCP 撤权与旧凭据失效机制。
- 关联 namespace/ID 查询域的真实消费入口，以及 UI、REST、MCP 共用的评估生命周期。

以上名称是能力需求，不预设未发布 Java 类名、方法签名或 HTTP 实现。

已发布宿主的字段语义：Snapshot.directoryRevision 等于 Project.revision，
TrustedIdentityV1 传播该目录快照 revision。authorizationVersion 是独立 token/session
撤权 epoch，不是目录修订；即使正常 mutate 时二者同时加一，也不得互换。
公开 PolicyBoundary 提供 project(revision,authorizationVersion)，adapter 必须取
revision 写入本地 Project.directoryRevision 和 Identity.directoryRevision，并核对快照
一致。authorizationVersion 只由宿主认证/撤权机制校验，不能拿它确认本地绑定。
纯模型测试使用目录修订 3 与 epoch 97，验证身份、本地和上界任何一处误用 epoch 都拒绝。
公开 adapter 另以目录 revision 19、epoch 7 验证字段映射；纯模型测试与 adapter 测试职责独立。

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
当前公开目录不提供与本地数据库共用的提交锁；新请求仍须重新检查最新目录修订与上界，不能据此声称跨系统条件提交原子性。身份写入不顺带创建本地 grants，
本地保存不顺带授予用户 scope。部分成功明确返回并重新读取，不能补授权。

管理错误语义：401 未认证或身份过期；403 无权；400 输入无效；404 无可见目标；
409 已存在/目录变化；412 修订冲突；428 缺 If-Match；503 依赖不可用。响应保留
`{code}`，不输出 token、密码或存储异常细节；成功 GET 与策略响应 no-store。

## 3. UI client 路径和请求体核对

以下为 ui/src/client.js、project-model.js、App.vue 与管理控制器的当前接口。
公共前缀为宿主 SDK 的 apiBase（含 /api/v1/main）加 /extensions/rbac。
P 表示项目 ID；K 表示 UTF-8 base64url 无 padding、强制 `~` 前缀的规范标识。
后端必须严格解码并回编码核对 K，拒绝非规范编码、非法 UTF-8 和路径/请求体不一致。
现有本地服务接收解码后的 scope 原名；不能直接把 K 当原名。

| client 方法 | HTTP 与相对路径 | 输入/修订、目标对应 |
|---|---|---|
| capabilities | GET /capabilities | 返回 allowed、identityManagement、localPolicyManagement、needsReconciliation；不读取旧永久管理员名单 |
| policy | GET /policy | `{policy:schema2,revision,mode,readOnly}`；只投影获准目标，不返回 schema1 |
| createProject | POST /projects | `{id,issuer,audiences,enabled,directoryRevision}`，本地全局 revision；对应 createProject，不接受 scopes |
| updateProject | PUT /projects/P | 同上，id 不变；目录修订必须不变；对应 updateProject |
| reconciliation | GET /projects/P/reconciliation | `{project,policyRevision,directoryRevision,upperBound:{project,scopes}}`；只给获准目标 |
| reconcileProject | PUT /projects/P/reconciliation | 完整项目含 id 与全部 scopes；If-Match 为 policyRevision；对应 reconcileProject，返回完整可见 policy view |
| scopes / scope | GET /projects/P/scopes；GET /projects/P/scopes/K | 读取本地绑定；不得用 identity scopes 代替本地绑定 |
| createScope | POST /projects/P/scopes | `{name,description,enabled,grants,managementGrants}`；本地全局 revision；对应 createScope |
| updateScope / deleteScope | PUT /projects/P/scopes/K；DELETE 同路径 | PUT 同上且 name 不变；DELETE 无 body；本地全局 revision；对应 replaceScope/deleteScope |
| identityProjects / identityProject | GET /identity/projects；GET /identity/projects/P | 前者 page/size；宿主共享目录公开服务，插件只做代理和管理域授权 |
| createIdentityProject | POST /identity/projects | `{id,tenant:"main",displayName}`，无 If-Match（创建）；通过公开目录创建 |
| updateIdentityProject | PUT /identity/projects/P | `{displayName,active}`；项目 revision |
| identityScopes / identityScope | GET /identity/projects/P/scopes；GET /identity/projects/P/scopes/K | 前者 page/size；共享意图，不是本地 grants |
| createIdentityScope / updateIdentityScope | POST /identity/projects/P/scopes；PUT /identity/projects/P/scopes/K | `{scope,description,active,resourceIntents,managementIntents}`；创建用项目 revision，更新用 scope revision |
| clients / client | GET /identity/projects/P/clients；GET /identity/projects/P/clients/K | 前者 page/size；K 为 clientId |
| createClient / updateClient / deleteClient | POST /identity/projects/P/clients；PUT/DELETE /identity/projects/P/clients/K | `{clientId,approved,firstPartyUi,audiences,allowedProjectScopes}`；创建用项目 revision，更新/删除用成员 revision；DELETE 无 body |
| assignments / assignment | GET /identity/projects/P/assignments；GET /identity/projects/P/assignments/detail?subject=... | 前者 page/size；subject 为 encodeURIComponent 查询值，不能按 base64 解码 |
| createAssignment / updateAssignment / deleteAssignment | POST /identity/projects/P/assignments；PUT/DELETE /identity/projects/P/assignments/detail?subject=... | `{issuer,subject,projectScopes}`；创建用项目 revision，更新/删除用 assignment revision；DELETE 无 body |
| users / user | GET /users；GET /users/detail?subject=... | 前者 page/size；要求 IDENTITY 授权，无永久 subject 旁路 |
| createUser | POST /users | `{username,name,email,password}`；无 If-Match（创建） |
| updateProfile | PUT /users/profile | `{subject,name,email}`；用户 revision |
| updateState / resetPassword | PUT /users/state；PUT /users/password | `{subject,active}` / `{subject,password}`；用户 revision；宿主处理认证撤销和真实管理员保护 |

除两个创建资源请求外，client 所有写请求均要求 revision 并发送强引号 If-Match；
没有提供 revision 的本地调用会先报错。分页期望 `{items,total,page,size}`，本地收集器
会检查完整性。项目内 scope/client/assignment 页含 directoryRevision；收集器要求逐页修订与已加载项目一致，相同 total 下的修订变化也拒绝。项目清单与用户目录没有全局修订，不提供跨请求快照保证。
client 不自行发送 CSRF token，必须由正式 SDK request 提供已验证保护；不能因此删除
服务端 CSRF 门。项目 ID 为受限字母数字编码，scope/client ID 为 K，subject 为查询值。

本地项目、完整恢复、完整 grants、共享目录代理及账号路由均已接入 schema2 管理控制器。
源码和本地检查不能代替真实联调；生产 UI、目录修订、拒绝副作用与浏览器操作仍由部署仓验收。
共享身份业务和数据仍归宿主，插件不保存 client、assignment 或账号凭据。

## 4. 旧消费者清理清单与保留理由

本表是正式切换时的定点变更清单；旧运行入口已按下表替换；schema1 解析仅保留离线迁移用途。

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
| UI 旧页面、翻译和根部署脚本 | UI 由插件维护；组合配置和最终浏览器验收由部署仓维护 |

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

纯模型与正式公开契约 adapter 已实现 grant、审批、存储 CAS、上界和目标关联，
以及可信身份转换、HTTP 管理与旧运行链移除。本地独立 check 和 PostgreSQL 检查
已执行；插件远端 CI、正式产物和真实 Compose/UI/REST/MCP 结果须独立核对。
管理员切换、旧凭据撤销与恢复演练不能从上述本地结果推定。

## 6. 主控裁决：部署管理员迁移与 BUSINESS_INSTANCE 映射

状态：已接收并接入 ProjectTypedPolicy，独立测试已覆盖；正式插件发布与真实组合验收尚待核对。此节具体化第 2 节的显式
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

ProjectTypedPolicy 使用以上固定表映射 typed 操作；未知项拒绝。该源码能力不证明
实际业务路由、执行、输出和管理隔离均已通过组合验收。

### 部署迁移的职责

插件维护离线迁移的输入格式、审批 hash 与存储保护。真实部署中旧策略和管理员配置
的来源 hash、备份、实际身份授予、允许/拒绝差异及回退证据归部署仓。
永久 subject 管理员不在旧策略 JSON 中，迁移计划必须单列该配置来源、移除项、
明确批准的三域 scope、client/audience 和重新登录证据。不得从旧管理员名称推导三域
权限，也不得把普通精确 namespace grants 扩大为子树或 BUSINESS_INSTANCE。
