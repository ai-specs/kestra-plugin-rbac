# 管理接口实施契约

状态：schema2 实施契约。宿主公开 SPI 已正式发布；插件 HTTP、独立 CI 和 Compose
验收状态以实际报告为准。源码和控制器单元测试不能代替生产认证、CSRF 与浏览器验收。
管理功能默认关闭，策略来源只有 database。schema1 只用于离线迁移读取，旧 subject
管理员、roles claim、roleMapping 和 OAuth 协议 scope 均不能取得新管理授权。

根路径为 `/api/v1/main/extensions/rbac`。前端从宿主 `context.apiBase` 拼接后缀。
身份只取自宿主 `TrustedIdentityV1`，并核对已认证 subject。每个请求固定一份本地策略
快照和一份 `policyBoundaries()` 目录快照；普通管理、恢复、候选验证共用此 Context。
其他 tenant 返回 404。GET 和策略响应使用 `Cache-Control: no-store`，不返回凭据或 SQL。

## 请求和授权

IDENTITY.READ 与 IDENTITY.MANAGE 分别控制精确可信 issuer 的目录/账号读取和修改。
LOCAL_POLICY.READ 与 LOCAL_POLICY.MANAGE 分别控制实际目标项目；MANAGE 不隐含 READ。
每次操作重新授权，不信任 capabilities、请求体、URL 中声明的调用人或 scope。
BUSINESS_INSTANCE 不授予任何本接口权限。所有写入保留宿主认证和 CSRF 过滤链；
插件不注册匿名写入例外，不自行解析 token，不复制宿主身份表。

请求体必须是单个严格 UTF-8 JSON 对象，最多 1 MiB。重复键、未知字段、缺字段、null、
错误布尔/数值类型、重复集合项、尾随文档、非法枚举和路径/body 标识不一致均拒绝。
项目 ID 为 `[A-Za-z0-9][A-Za-z0-9_-]{0,127}`。scope/client ID 不透明，路径键为
`~` 加 UTF-8 base64url 无 padding；服务端解码后回编码验证规范形式，拒绝别名和非法 UTF-8。
subject 保持精确原值，以 URL 编码查询参数传输，不再解码为 base64。

带修订的写入必须使用强 `If-Match: "<revision>"`；缺少返回 428。弱标签、列表、通配、
前后空白、前导零、负数和溢出返回 400；过时修订返回 412。身份项目与账号的首次创建
没有 If-Match；共享 scope/client/assignment 创建使用项目 revision；更新/删除使用对应
对象 revision；账号更新允许 revision=0；本地写入一律使用全局 policy revision。

## 发现与读取投影

- GET `/capabilities` 返回 allowed、subject、identityManagement、identityRead、
  localPolicyManagement、localPolicyManage、localPolicyRead、reconciliationAllowed、
  needsReconciliation、userDirectory、mode 和 readOnly。localPolicyManagement 表示可以
  进行普通或恢复的本地管理交互；localPolicyManage 仅表示普通 MANAGE。目录过期时可以
  needsReconciliation=true、localPolicyManagement=true，而普通 CRUD 与身份管理仍拒绝。
  这些字段不是授权凭证，实际目标仍逐次检查。
- GET `/policy`（别名 GET `/projects`）返回 `{mode,revision,readOnly,policy}`，policy 下为
  schemaVersion=2、applicationProjects，仅含当前 LOCAL_POLICY.READ 允许的项目。
  无权项目不能通过全局策略封套泄露。响应 ETag 是当前全局策略 revision。
- GET `/projects/{id}/scopes` 和 `/projects/{id}/scopes/{K}` 读取精确目标的本地绑定，
  要求 LOCAL_POLICY.READ；不存在的已授权 scope 返回 404。
- GET `/projects/{id}/binding-options` 仅要求实际目标的普通 LOCAL_POLICY.MANAGE，
  不要求 IDENTITY 或一般 READ。返回 `{policyRevision,directoryRevision,upperBound:{project,scopes}}`。
  project 只含 id、issuer、audiences、enabled、revision；scopes 只含 scope、description、
  active、resourceIntents、managementIntents。不返回用户、client、assignment、reachable
  管理 scope、凭据或 authorizationVersion。目标尚无获批 client/audiences 时返回 409
  binding_target_incomplete，不能伪造 audience 或令一般目录页失败。

`directoryRevision == upperBound.project.revision`，来自同一目录快照；它与
`authorizationVersion`（token/session 撤权 epoch）不同。完整目标上界一次返回，不分页
拼接或截断。投影不会写入任何策略、目录或账号状态。目录失配时普通投影拒绝，不自动进入恢复。

## 本地项目与完整 grants

- POST `/projects` 和 PUT `/projects/{id}` 接收完整元数据
  `{id,issuer,audiences,enabled,directoryRevision}`。更新 id 不变，且不能改变 directoryRevision；
  不接收 scopes。首次创建也须已获准实际目标，不能通过此 HTTP 首次引导管理员。
- POST `/projects/{id}/scopes` 和 PUT `/projects/{id}/scopes/{K}` 接收
  `{name,description,enabled,grants,managementGrants}`；managementGrants 可省略为空。
  PUT 的 name 必须与路径解码值相同。DELETE 同路径删除一个本地绑定。
- business grant 为 `{scope,tenant,namespace,descendants,resource,actions,ids,allIds}`。
  普通资源必须为具体 NAMESPACE/main，METADATA 与 MCP_SERVER 仅接受已裁决的 TENANT/main
  例外。namespace、动作和 ID 必须由同一 scope 的完整授权提供，不跨 scope 拼接。
- management grant 为 `{domain,actions,projectIds}`；域为 IDENTITY、LOCAL_POLICY 或
  BUSINESS_INSTANCE，动作 READ/MANAGE 分开。只有 LOCAL_POLICY 可以列出非空精确目标；
  空集合只表示所属项目，不是全部项目。不使用旧本地角色间接共享授权。

每次保存完整解析候选 schema2，并对全部绑定检查同目录快照中的 issuer、audiences、
目录修订、scope、资源/管理上界和真实最后管理员可达性，然后用全局策略 revision 执行
一次 compare-and-set（比较后交换）保存。拒绝不保存部分 scopes，不先推进目录确认版本。
成功响应只含实际修改项目及新 revision，保持 `{mode,readOnly,revision,policy}` 封套；
不能因操作者有 MANAGE 就返回其他项目或完整全局策略。

## 完整恢复

GET `/projects/{id}/reconciliation` 与 PUT 同路径只使用实际目标的独立恢复授权。
恢复只忽略本地 directoryRevision 过期；真实身份仍须当前、已知且启用的 scope 必须完整，
未知 scope 混入仍拒绝，并由同一 scope 的本地与当前共享 LOCAL_POLICY.MANAGE 覆盖目标。
恢复不授予一般读取、账号管理、身份目录管理或业务操作。

GET 返回 `{project,policyRevision,directoryRevision,upperBound:{project,scopes}}`；外层 project
为含 id 的完整本地项目。PUT 接收完整 `{id,issuer,audiences,enabled,directoryRevision,scopes}`，
必须审查并一次保存全部 scopes。不能先只改修订、自动剪裁越界 grants 或部分提交。
普通项目更新和普通 scope 更新均不得隐式确认目录变更。

本地策略单次保存是数据库事务。公开目录快照与本地策略不具有跨系统条件提交或共享锁，
因此当前实现不能保证目录在校验后、提交前变化时绝不产生一个随即过期的本地版本。
后续运行请求仍按当前目录失败关闭；这一限制不能描述为全局原子提交或已通过并发目录漂移门。
需要严格的跨系统保存保证时，必须先接收并发布对应公开契约，再完成该验收。

## 共享身份目录代理

以下入口只调用公开 ApplicationDirectoryV1，不保存第二份身份事实源：

|入口|输入|修订|
|---|---|---|
|GET/POST `/identity/projects`|分页 / `{id,tenant:main,displayName}`|创建无 If-Match|
|GET/PUT `/identity/projects/{id}`|读取 / `{displayName,active}`|project revision|
|GET/POST `/identity/projects/{id}/scopes`|分页 / `{scope,description,active,resourceIntents,managementIntents}`|创建用 project revision|
|GET/PUT `/identity/projects/{id}/scopes/{K}`|读取 / 同上|scope revision|
|GET/POST `/identity/projects/{id}/clients`|分页 / `{clientId,approved,firstPartyUi,audiences,allowedProjectScopes}`|创建用 project revision|
|GET/PUT/DELETE `/identity/projects/{id}/clients/{K}`|读取 / 同上 / 无 body|member revision|
|GET/POST `/identity/projects/{id}/assignments`|分页 / `{issuer,subject,projectScopes}`|创建用 project revision|
|GET/PUT/DELETE `/identity/projects/{id}/assignments/detail?subject=...`|精确 subject / 同上 / 无 body|assignment revision|

资源意图使用 `{vocabularyVersion:kestra-resources-v1,resourceType,namespaces,resourceIds,actions}`。
管理意图与本地 managementGrants 分开提交，不自动复制。创建身份项目不会创建本地绑定；
授予成功不保证本地已允许。新空项目可在身份目录显示，但先批准真实 client/audiences
后才能建立本地绑定。同一第一方 UI client 不得同时属于多个项目。

分页为 `{items,total,page,size}`，page>=1、1<=size<=100。client 详情通过公开分页精确查找，
检查项目目录修订、页元数据、total、重复 ID 和读取前后修订，完整扫描上限 10000；
漂移/缺页/超限返回 409 directory_changed，不用旧页拼接为成功。
目标共享 scopes、clients、assignments 的每页响应额外包含 directoryRevision，值为该请求
Context 的目标 Project.revision，adapter 在读取前后核对相同修订。跨请求完整收集必须核对
每页 directoryRevision 相同；只比较 total 不够。当前 UI collector 仍只核对 total，尚未消费
此新增字段，因此不能宣称 UI 跨页为同一快照。项目清单没有 issuer 级全局修订，账号列表
也没有全局账号目录修订；这两个列表不提供跨请求快照保证，不伪造版本。
任何目标 issuer 与可信身份 issuer 不同都拒绝。创建账号不附加任何项目 scope。

## 登录账号

GET `/users`、GET `/users/detail?subject=...` 需要 IDENTITY.READ。
POST `/users` 接收 `{username,name,email,password}`，不使用 If-Match。
PUT `/users/profile` 接收 `{subject,name,email}`，PUT `/users/state` 接收 `{subject,active}`，
PUT `/users/password` 接收 `{subject,password}`。写操作需要 IDENTITY.MANAGE，更新使用账号
revision。subject 不可改名，不提供物理删除。密码不回显，资料、身份授予与本地绑定分别保存。
宿主负责账号停用、密码修改后的认证撤销及最后身份管理员保护；不保留 subject 特权名单。

公开 ApplicationDirectoryV1.LAST_ADMIN 映射 409 last_administrator，FORBIDDEN 映射 403。
若宿主先将该异常封装为 UserDirectoryV1.UNAVAILABLE，插件只能映射 503，不能根据错误
文字猜测 409。实际版本错误保留行为必须通过宿主与组合验收核对。

## 已移除的旧写入口与错误

POST `/roles`、PUT/DELETE `/roles/{id}`、PUT `/bindings` 和 PUT `/role-mappings`
明确返回 410 legacy_policy_api_removed，不读取或修改旧表，不自动转换 scope，不触发账号
或身份目录写入。旧管理业务服务已删除，schema1 validator/store 只保留离线迁移用途。

插件错误体为 `{"code":"..."}`：401 authentication_required；403 access_denied 或
invalid_identity；400 invalid_request；404 not_found；409 already_exists、directory_changed、
last_administrator 或 binding_target_incomplete；412 revision_conflict；428 revision_required；
503 policy_unavailable 或 directory_unavailable。宿主认证/CSRF 拒绝保留宿主格式。
未知内部异常返回 500 management_failed，不泄露异常详情，不自动重试写入。

## 页面与请求

扩展页组件样式、主题和筛选必须与上游同构页一致。验收以所消费正式宿主的 MCP Servers
详情顶部 tabs 和按钮、KV Store／Flows 数据表及条件筛选为参照。Namespaces 只作搜索控件参照，
其树形结构不得当作表格。不能以组件导入成功或 CSS 文件存在代替
实际样式验证。组件必须消费 `.integration/ui-sdk` 指定版本与 integrity 的公开组件包，
Element Plus 必须在扩展自己的 Vue 上下文中配置 `kel` 命名空间，不借用宿主 Vue 实例。
tab 使用公开 KsTabs/KsTabPane，条件筛选使用 KsFilter，数据表使用 KsDataTable 与 KsTable。
顶部 tabs 直接衔接宿主导航，不重复显示大标题。Create 使用主按钮并置于右侧工具栏。
这些组件来自 SDK 指定的同一公开 npm 包；并不表示 SDK 承诺所有弹出层自动兼容。
插件用 Vue 公开 renderer 将 Teleport 的 body 目标解析到自有 ShadowRoot 容器，
KsPopover／KsTooltip 禁止传送到 document。上游 KsDataTable 的分页固定英文，因此插件
用同一 Element Plus 分页组件传入当前语言，保留组件包样式。禁止修改 vendor 源码。
筛选条件存入按 tenant、已认证 subject 和 tab 隔离的 sessionStorage，刷新恢复；不是授权来源。

浏览器必须在 light 和 dark 分别断言：按钮不是浏览器默认边框和圆角；tab 的字号、字重、
pill 形态与圆角和参考页一致；表头及行分隔线正确；背景、文字、按钮、输入与表格随主题
翻转；搜索确实改变项目、用户和 scope 绑定列表，清空恢复。账号筛选必须覆盖全部分页，而非只
过滤当前页；筛选后的总数和分页必须对应可见结果。刷新、切换页面、空结果及窄屏同时检查。
条件编辑、关闭按钮、清空、Contains／Equals、组合 AND／OR 和页大小切换必须实际操作。
计算样式之外，必须保留同视口截图并检查布局、工具栏、留白和 tab 相对宿主导航的位置。

页面根内路径为 projects、projects/{id}/scopes、projects/{id}/bindings/new、
projects/{id}/reconciliation 和 users。scope/client 的 API 路径键必须使用 `~` 加 UTF-8
base64url 无 padding 编码。subject 使用查询参数或 JSON 中的原值，不用显示名称替代。

页面采用发布SDK和公开组件包的兼容入口；基础输入、按钮、表格优先复用。原生选择框、
复选框与页内确认采用同一主题语义；详情走路由，不使用未经验证的vendor弹出层。
宿主负责页面外壳和菜单，插件只挂载自己的ShadowRoot内容，所有样式由SDK工具准备后
放入context.styles，不向document写CSS。全部文案来自ui/src/translations，支持发布
SDK的全部语言、主题与窄屏。请求使用context.request，处理401/403、过时版本、网络
异常和加载状态；保存期间禁止重复提交，保存冲突提示重新加载，不覆盖他人修改。
