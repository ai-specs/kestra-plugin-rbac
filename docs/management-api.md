# 应用组管理接口契约

状态：当前公开源码契约。宿主正式输入由 `host-contract.properties` 固定；正式插件
检查点 `73fa96e6dbbb614daa466703b8355c54abff7727` 的成功 CI `40999171025` 已实际下载核对
独立 Java286/PG108/生命周期24/UI172。该结果仅证明独立能力层，不代表之后源码或宿主
输入变化已通过发布门，也不代表最终生产 HTTP/UI/Compose 组合通过。
2026-10-27 R21 取代旧声明/实际策略双入口；自足模型、安装与显式升级形状见
`application-groups.md` 顶部。用户/凭据已消费正式 UserDirectoryV2、TrustedIdentityV2、
IdentityLifecycleParticipantV1 与 ClientCredentialsV1；ProjectionV2/currentResources 已接线，
允许空资源 audiences 登记，不补 clientId；资源 audience 与登录 audience 规则仍分别校验。
所有管理列表HTTP默认size=25；UI消费10/25/50/100。
权限按key、目录按稳定key/subject先排序再分页，Unicode标识使用码点比较。

R24借用连接与R25部署维护轮换为主控在维护者授权范围内的补充裁决，保留“维护者
可复核设计”来源限定，不表示维护者已逐项批准。标准事务/轮换边界引用固定正式宿主
README“账号与client凭据V2”“受限维护CLI”公开契约，不由本API新增宿主承诺。

## 身份与事务

根路径为 `/api/v1/main/extensions/rbac`。其他 tenant 返回 404。宿主生产 OIDC、签名、
会话、CSRF 和标准 issuer 校验保持有效。身份基础类型与生命周期保持 `TrustedIdentityV1` 兼容，核对登录 subject，不自行解析 token。
普通授权优先消费已发布 `TrustedIdentityV2.currentForAuthorization()`；旧实现保留 V1 `current()` 回退，生命周期借用连接语义不变。请求体为单个 UTF-8 JSON 对象，最多 1 MiB；重复键、未知字段、顶层
字段值为 null、尾随文档、错误类型及路径与正文标识不一致拒绝。嵌套 typed grant 按其
完整模型校验；TENANT grant 的 namespace 必须显式为 null，见下文，不适用顶层 null 拒绝。
响应使用 `Cache-Control: no-store`。

IDENTITY 的 READ/MANAGE 控制本 issuer 的用户、应用组、scope、支持登记和用户授予。
LOCAL_POLICY 的 READ/MANAGE 控制指定应用组中配置的 Kestra 应用实际权限。二者不互含；
MANAGE 不隐含 READ。BUSINESS_INSTANCE 不授予上述管理权限。能力响应仅用于界面展示，
每次实际操作仍重新校验。

身份目录入口先检查调用人的 IDENTITY.READ 或 IDENTITY.MANAGE。授权通过后，未知
应用组或已删除父组返回404；实际目标跨issuer仍返回403。没有所需动作时，已存在与
不存在目标均返回403，不提供存在性信息。目录或策略依赖不可用仍返回503，不伪装404。
该规则不改变capabilities未知目标403或LOCAL_POLICY上界失败关闭行为。

业务写入在 `READ_COMMITTED` 事务中先取得正式 `OidcProtocolStateV1` 的安全锁，再读取
当前标准账号/client、组修订、撤权版本和 scope 交集，重新校验操作者。目录写入、实际策略
条件提交与生命周期 guard 使用同连接标准身份检查。拒绝回滚所有相关写入。组、scope、
应用支持、用户授予、内置与退役记录均存于插件表。插件不读取宿主私有表。

## 标识与修订

### UI query与HTTP查询参数边界

UI列表导航按§12.3消费permissionKey/filterSubject/scopeKeys/page/size及手工filter、
公开KsFilter编码。已约定字段的非法类型、重复单值、非法page/size和畸形filter显示
可清除的invalid_query，不作为403授权拒绝；App在能力/目录/subject读取前校验，
无效filterSubject不请求空subject或重复subject。清除非法query恢复默认page1/size25；
正常清除可保留合法非默认size。删除末页唯一项后router.replace到最后有效页，保留
合法size及其他合法条件。当前memory router逻辑不代替正式宿主URL/history验收。

未知顶层UI query当前不参与过滤和REST请求，列表解析器忽略它们；清除操作会移除。
这不表示未知顶层query已有拒绝门。REST控制器只绑定各入口声明的查询参数，当前
没有对原始HTTP query key集合执行统一未知参数拒绝；不得把JSON body的未知字段
拒绝或UI列表校验写成HTTP query已经严格验证。授权、issuer、目标、scope及强修订
仍由API独立检查，未知参数不能提供权限或扩大API返回范围。

应用组 ID 为 `[A-Za-z0-9][A-Za-z0-9_-]{0,127}`。scope key 为 `[a-z][a-z0-9-]{0,63}`，
路径直接使用稳定 key。应用 client 路径键为 `~` 加无 padding 的 UTF-8 base64url，服务端
解码后回编码检查规范形式。用户 subject 使用 URL 编码查询参数传递，保持精确原值。

条件写入使用强 `If-Match: "<revision>"`。缺少返回 428，弱标签、通配、负数、前导零、
溢出或列表返回 400。过时修订返回 412，创建冲突返回 409。创建应用组和账号无需修订；
创建 scope、应用支持或用户授予使用组修订；修改或删除子对象使用子对象修订。
账号更新可以使用修订 0。权限创建用应用修订，修改/删除用权限修订；不再用全局策略修订。

## 应用组 API

| 方法及路径 | 请求或响应 | 权限 |
| --- | --- | --- |
| GET `/application-groups?page=1&size=20` | 分页组卡片数据 | IDENTITY.READ |
| POST `/application-groups` | `id/tenant/displayName/description` | IDENTITY.MANAGE |
| GET `/application-groups/{id}` | `id/tenant/issuer/displayName/description/active/revision/authorizationVersion/builtIn/counts` | IDENTITY.READ |
| PUT `/application-groups/{id}` | `displayName/description/active` | IDENTITY.MANAGE |
| DELETE `/application-groups/{id}` | 删除回执 | IDENTITY.MANAGE |

内置组固定 ID 且 `builtIn=true`，始终不可删除；合法删除请求返回409 `protected_group`。
普通组删除要求没有 scope、应用或用户授予；非空组返回409 `group_not_empty`。这两类
业务冲突不改变目录、退役记录或审计。非法输入仍400、过时修订412；先检查IDENTITY.MANAGE，
无权限403、已授权未知目标404，MANAGE不隐含READ。成功删除在同一事务登记退役 ID；退役 ID 不可重用。卡片进入只读详情，点击编辑后才显示
表单；有修改权限不等于有查看权限。页面没有默认操作列或配置状态。

## Scope、应用和用户授予

以下路径均在 `/application-groups/{id}` 下。列表返回 `items/total/page/size/directoryRevision`，
跨页消费者必须核对同一修订，不得只依赖总数。

| 路径 | 创建及修改输入 | 响应 |
| --- | --- | --- |
| `/scopes`、`/scopes/{key}` | `key/name/description` | `applicationGroupId/key/name/description/revision` |
| `/applications`、`/applications/{clientKey}` | `clientId/description/audiences` | `applicationGroupId/clientId/description/audiences/supportedScopeKeys/implementationKind/revision` |
| `/scope-grants`、`/scope-grants/detail?subject=...` | `issuer/subject/scopeKeys` | 输入字段及 `applicationGroupId/revision/authorizationVersion` |

三类入口支持 GET、POST、PUT、DELETE。表中响应是读取形状；写成功只返回提交的稳定标识、
revision、parentRevision 和 ETag，不返回对象副本。GET 要求 IDENTITY.READ，写入要求 IDENTITY.MANAGE。
scope key 不可修改；角色 name 必填且同组精确唯一，创建或改名遇到已存在name返回409 `scope_name_exists`；自身name不变允许，description 不参与授权。删除 scope
要求组内全部应用和用户授予均已释放引用。应用登记须存在标准 active 或有效 pending
client；pending 只允许先登记支持并展示 consent，不能直接签发凭据。用户授予要求同一
issuer 的真实标准账号。当前 UI client 是普通成员，无 `firstPartyUi` 输入或输出。

用户在应用可获得的 scope 是应用支持与该用户授予的交集。混入未知、已撤销或不再支持
scope 必须整体拒绝。支持或授予变更原子推进组修订与撤权版本；旧会话须重新生产登录。
最后管理员保护分别检查用户管理及实际策略管理可达性，不按角色名称或永久 subject 推断。

## 统一应用权限

路径 `/application-groups/{id}/applications/{clientKey}/permissions`，对象路径 `/{key}`。
GET 分页返回 items/total/page/size/parentRevision/implementationKind，详情返回完整权限
DTO 和 ETag。key为1–128小写规则不可改，name最多256 Unicode code point，description
最多4096，scopeKeys是组内不重复集合，可空；多条权限可引用同一scope。

POST/PUT 公共必需字段为 key/name/scopeKeys，description选填默认空。Kestra体还需 grants/managementGrants；
POST enabled可省略并默认true，PUT enabled必填。外部体只需details，不能提交enabled、
grants、managementGrants或implementationKind。details最多16384字符纯文本，不执行。
服务端以配置 `micronaut.security.oauth2.clients.kestra.client-id` 精确决定类型。
读DTO统一包含上述字段及implementationKind/revision/parentRevision，外部typed列表为空。
写成功只返回 `{key,revision,parentRevision}`。MANAGE不包含READ，不回传对象/策略。

Kestra读取/写入分别要求精确目标LOCAL_POLICY.READ/MANAGE；改变派生支持集的写入还
要求IDENTITY.MANAGE。外部权限使用IDENTITY.READ/MANAGE。enabled只影响真实执行，
不影响支持集；停用与空grants不能授予资源或管理员动作。名称/描述不产生授权。

资源 grant 使用 `scope/tenant/namespace/descendants/resource/actions/ids/allIds`，每项必须
显式提供全部八个字段（包括空 ids 数组和 false 布尔值），不得省略 JSON creator 字段。
TENANT grant 仅用于 METADATA/MCP_SERVER，tenant 必须为 main，namespace 必须显式
写为 null，descendants 必须为 false；省略 namespace 或改为空字符串均返回 400。
METADATA 仅允许 READ/LIST，必须 allIds=true、ids=[]；MCP_SERVER 允许
READ/LIST/CREATE/UPDATE/DELETE，allIds=false 时 ids 必须非空。其他业务资源要求
NAMESPACE 和具体合法 namespace。以下是可放入 Kestra 权限请求 grants 数组的两项完整示例，
不是省略 key/name/scopeKeys/managementGrants 等外层必需字段的完整 POST 请求：

```json
[
  {"scope":"TENANT","tenant":"main","namespace":null,"descendants":false,"resource":"METADATA","actions":["READ","LIST"],"ids":[],"allIds":true},
  {"scope":"TENANT","tenant":"main","namespace":null,"descendants":false,"resource":"MCP_SERVER","actions":["READ"],"ids":["server-a"],"allIds":false}
]
```

管理 grant 使用 `domain/actions/applicationGroupIds`。同一个操作所需权限必须由同 scope
完整提供，不跨 scope 拼接 READ/LIST、namespace 或资源 ID。名称和声明元数据不授予权限。

策略条件提交在安全锁内重新授权与验证真实管理员可达性，不依赖先前 capabilities。
没有 READ 的合法写入不返回策略，后续读取仍独立校验。功能关闭返回 404，缺少配置返回
503，其他应用返回 404。旧全局 `/policy`、`/projects`、reconciliation、roles、bindings 与
role-mappings 路由不注册，不保留兼容别名。旧scope-declarations和runtime-permissions
所有HTTP路由均不注册。协议consent由相同目录权限投影，只读按scope分组，无第二写源。

已知应用的 `permissions/-/mutation-metadata` 返回 clientId/revision/actions；已知权限的
`permissions/{key}/mutation-metadata` 返回 key/revision/parentRevision/actions。需要MANAGE，
不返回名称、列表、计数或策略；失权后仍独立拒绝。scope_in_use为409，阻塞引用只在对应
READ范围内返回，MANAGE-only只返回冲突代码。metadata-only不推进epoch且旧凭据可继续
校验；真实typed变更推进策略版本，固定评估快照受部署期限约束，默认60秒后失败关闭。

集合元数据固定使用`/-/mutation-metadata`，其中独立`-`段不符合权限key规则；不会
与任何合法权限key重叠。`mutation-metadata`本身仍是合法key，其普通GET遵循READ，
PUT/DELETE遵循MANAGE；逐项`/{key}/mutation-metadata`仍遵循MANAGE。没有为修复
路由预留key，也不保留旧集合`/mutation-metadata`别名。实际Micronaut路由回归和
同名key CRUD/只读只写权限回归分别验证分派和业务行为；网络HTTP仍由组合验证。

LOCAL_POLICY.READ+MANAGE但没有IDENTITY.READ的UI使用精确当前Kestra client深链进入
策略页，不读取组、scope或用户目录。编辑使用已获准读取的权限自身revision，允许只
改名称/typed内容并保持scopeKeys；新建通过现有`permissions/-/mutation-metadata`取
父application revision。该metadata不返回名称、scope列表或策略，不作为额外授权。
无目录READ时ScopePicker显示原生KsInput多行已知scope key输入，仅验证ASCII格式、
重复及数量；存在性、同组引用和支持并集变化由服务端验证。改变支持并集仍必须额外
IDENTITY.MANAGE，无该权限时服务端403且不能有副作用；UI不通过枚举、隐式READ或
伪造支持范围绕过。READ-only不显示写入口，MANAGE-only不能加载策略或枚举。

ScopePicker唯一外层`data-test="scope-picker"`真实包裹正常原生选择器；其子选择器
标识`scope-select`，无目录READ模式标识`scope-known-keys`。subject与permission使用
同一组件，不再依赖不同HTML标签的:last-of-type推测控件。测试适配器须按实际模式定位。

插件自身失权错误页换号链接为`data-test="sign-in-again"`，沿公开GET `/oidc/logout`
退出；安全部署base前缀从host apiBase保留，不接受外站、query、fragment、编码路径
分隔符或路径穿越。不是简单跳`/ui/`。本地helper/组件检查不证明真实会话已清除；
必须实际点击到生产登录表单，并以同cookie capabilities拒绝及重新登录确认。

## 管理生命周期审计（正式插件已包含，组合验收独立执行）

GET `/audit?page=1&size=25`返回items/total/page/size。每项仅含
id/time/issuer/actorSubject/operation/targetSubject/clientId/groupId。操作只包括
ACCOUNT_DELETE、ACCOUNT_ACTIVATE、ACCOUNT_DEACTIVATE、PASSWORD_RESET和
CLIENT_SECRET_ROTATE。不记录创建/资料更新，不包含secret/hash/hint/请求或错误正文。
记录只能在生产参与者的借用事务连接append；标准操作失败则无提交记录。

读取在同连接重新校验可信身份，以当前有效组为机制入参要求IDENTITY.READ，授权范围
保持issuer；SQL在items分页和total前统一限制可信issuer，包含同issuer跨组和已退役/
删除组历史。MANAGE-only和无READ返回403，跨issuer不泄漏。无组目标账号事件只记
actor当前可信组，有成员关系则按操作前所属组逐组记录。账号/actor/组删除不改写
历史标识，无账号/组级联外键；groupId只是事件归属，不改变IDENTITY授权范围。正式插件 `862ccf9`（CI `40803869433`、jar SHA256 前缀 `99f1`）已包含审计接口、存储及参与者接线，独立 PostgreSQL 生命周期门已执行。
此前本地检查点针对当时正式宿主输入通过 Java 286、PostgreSQL 108、生命周期 24 及 UI 111 项，
该数量保留为历史记录，不作为当前 UI 测试总数。d099 成功 CI `40950658945` 的独立
Java/PG 门见本文顶部；后续未提交变更仍须对应最终提交 CI 与产物检查，生产 HTTP、
根仓 UI 与 Compose 组合仍须独立验收。

### 标准账号与错误状态

`/users`、`/users/detail?subject=...` 提供分页、详情与创建；`/users/profile`、`/users/state`、
`/users/password` 修改标准账号。用户创建不自动授予 scope。标准账号读取、资料、状态和
密码操作当前通过正式 UserDirectoryV2；停用和密码变更在宿主事务内调用插件 guard，
保留最后管理员与撤权检查。

正式 POST `/users` 只允许 username/name/email，姓名与邮箱可省略并默认空。
调用方 password、未知字段和 null 均拒绝。成功201只返回
`{subject,revision,temporaryPassword}`，带强 ETag 和 `Cache-Control: no-store`。
DELETE `/users/detail?subject=...` 必须发送强 If-Match，账号修订允许0；自身删除拒绝，
成功204无响应体，修订冲突412。MANAGE不包含READ，两种操作均不读取目录枚举。
临时密码和物理删除已接固定正式UserDirectoryV2；不回退旧password创建。缺少或重复
正式目录/生命周期组件返回503并失败关闭。独立能力层结果不替代最终生产 HTTP/组合验收，
后续变更的正式 CI 必须对应最终提交。

目标凭据路径为 `/application-groups/{id}/applications/{clientKey}/credentials` 和
`/credentials/rotate`。内部业务服务已定义READ状态与MANAGE轮换、public400
`public_client_no_secret`、登录client409 `maintenance_required`和修订冲突412；
HTTP控制器与正式宿主适配器已接线，本地PG覆盖精确状态/回执及MANAGE-only；实际
HTTP/CSRF仍需部署组合验证，不把直接控制器测试当作网络请求或浏览器通过。
目标响应及事务参与者要求见 `application-groups.md` 的F3/F4段落。

认证失败 401，权限拒绝 403，未知对象 404，修订/业务冲突 409 或 412，缺修订 428，
依赖不可用 503。最后管理员返回 409 `last_administrator`。错误响应不包含密码、token、
数据库语句或内部异常内容。锁后actor失效或明确权限拒绝返回403；并发失败不得用
依赖503冒充安全拒绝。参与者仅将明确INVALID_IDENTITY/FORBIDDEN映射安全拒绝，
目录/策略/SQL/投影故障仍为503。真实 HTTP、CSRF、浏览器、撤权与无副作用门由部署仓验收。

普通授权前置门使用正式 `TrustedIdentityV2.currentForAuthorization()`。明确目录/策略依赖不可用通过宿主 `AuthorizationUnavailableException` 返回固定安全503 ProblemDetail，不携带SQL、凭据或底层异常消息。该前置响应不保证包含控制器的 `directory_unavailable`/`policy_unavailable` 业务码。若宿主公开异常在管理控制器 `request` 边界内产生，控制器精确返回HTTP503及业务码 `authorization_unavailable`，不输出底层消息；其他控制器内已分类故障仍保留原业务响应。同步/异步评估使用相同分类，安全拒绝、未知异常和缺失/重复授权provider保持原失败关闭门；生命周期借用连接接口不变。

同一clientId跨应用组重复登记返回409 `client_already_assigned`。上述唯一性冲突在事务写入边界精确分类，不改变目录、修订、epoch或审计；CAS412、非法输入/引用400及权限403保持。组显示名和应用权限name不新增唯一约束；Snapshot损坏数据校验仍独立失败关闭。

内联编辑（组、scope、应用）遇到409/412时保留草稿并锁定输入和保存；取消仍可聚焦及点击，脏草稿必须经显式丢弃确认。取消丢弃继续保留草稿；确认后退出编辑。冲突提示的刷新同样先确认丢弃，再重新读取服务端数据并恢复可编辑状态，不自动重试原写入。保存期间输入、保存及取消均禁用，事件入口也拒绝重复保存/取消。四类抽屉遵循同一冲突/保存边界。本轮候选复用公开 KsDrawer 默认 header/title、原生最大化及关闭 SVG、body padding 和 footer slot，表单复用 KsForm/KsFormItem 的 ks-horizontal 规范。保存期间原生关闭按钮隐藏、编辑和 footer 操作禁用，beforeClose 仍拒绝关闭；纯呈现的原生最大化允许切换，不改变字段、不提交也不导航，不替换全局按钮或派生/转接抽屉 setup；原生组件重建使最大化在重开后复位，不访问组件私有状态。drawer-kind 标记覆盖真实 drawer 的 header/body/footer，焦点与 Escape 继续通过公开 shadow overlay SDK 管理。实际语言、主题、窄屏与 footer 贴底仍须由部署仓生产浏览器验证。

用户、应用权限及用户授予列表通过公开KsFilter configuration将已支持字段设为可分组，使原生高级筛选Add condition实际创建条件。permission支持key精确匹配、name包含及scopeKeys；用户支持subject精确匹配，用户授予另支持scopeKeys。同一列表的原生条件与深链条件按AND求交，scopeKeys单条件内仍按任一值匹配；URL编码与历史回退保留条件和结果。未支持的OR或比较符明确拒绝，不降级扩大列表。此配置不改变服务端授权范围。

访问控制管理的组卡片、权限、用户及用户授予筛选均采用AND；DS0.5原生高级层保留合法条件编辑，仅由版本绑定owned适配禁用Add condition group和AND/OR切换，显示本地化AND说明及aria-describedby，并阻止鼠标/Enter/Space。目标必须由注册区域触发并唯一关联本根portal；结构未知或多匹配显示可清除错误，不改其他filter、不把OR改写为AND。手工OR URL仍拒绝并支持清除恢复。该组件回归不代表生产浏览器验收通过。

保存筛选不是本管理页面能力，四处KsFilter原配置savedFilters.shown=false在DS0.5移动层由同一owned适配精确落实：隐藏Saved tab与Save入口并禁用激活，保留All Filters、字段/比较符、清除及Apply。关联仅用本实例结构与当前语言的精确标签；未知结构卸载该实例，错误区Clear后重新挂载并释放原生modal。此处不提供保存/加载/删除能力，本次适配代码不读取、迁移或删除旧共用localStorage记录；原生KsFilter自身setup仍可能由useStorage读取默认key；此前prefix隔离方案已收窄为非当前能力。
UI test:logic使用Node原生spec+JUnit双reporter，所有test/*.test.mjs均执行；机器报告保留于.evidence/ui/results.xml，失败仍保存并返回原失败exit，最终CI artifact必须实际下载核对。

### 详情侧栏的分页与选中项

scope 与应用详情侧栏在首次装载、选中标识变化或目录标识集合变化时，定位到当前搜索结果中选中项所在页；删除后新的回退选中项使用相同规则。人工翻页保持有效，不在每次渲染时强制返回选中页。搜索变化回到第 1 页；选中项不匹配搜索时不绕过搜索条件。组件回归不代替生产深链、导航和浏览器组合验收。

### 同路径筛选 query 与授权刷新

原生筛选的同路径 query 变化仍通过公开 `context.navigate` 同步宿主 URL，并完整重读 capabilities、目录和列表；`filterSubject` 的实时授予与账号检查保持执行。读取成功且公开身份/能力响应未变时保留组件实例、弹层和输入焦点，集合在当前 generation 成功后一起更新。身份或能力响应变化、tenant 或业务路径变化仍清敏并重载；403 清敏拒绝，503 清除旧集合并显示可重试错误，非法 query 失败关闭且可显式清除。

query 读取不作为业务保存锁，后续同路径 query 可以覆盖旧读取；旧 generation 不得提交数据或修改新读取的忙状态。仅同路径 query 的宿主导航串行发送并保留最新待发目标，较旧回传不覆盖原生最新输入；外部路径/tenant 变化取消旧待发目标，已发请求的延迟回传按导航 generation 恢复当前宿主目标。每个回传记录在 finally 释放，卸载取消队列。真实保存、脏草稿和一次性凭据的导航保护保持有效。

本地真实 App 组件回归使用公开 DS0.5、内存 router、受控 API 响应和实际 `beforeNavigate`/`context.navigate` 回传，验证连续输入、多条件、慢旧响应、宿主回传顺序、历史/原生刷新、失权/身份变化、依赖失败恢复和卸载。它不代表生产签名会话、浏览器几何或 Compose 组合验收通过。

### 移动筛选完整性失败的 owned 关闭顺序

DS0.5 移动筛选层由正式 SDK renderer 迁移至本扩展 portal。已 claim 的层发生完整性错误时，本适配通过该次 activation 唯一关联的原生 scrim 关闭筛选，等待原生层退出后再报告错误并卸载 KsFilter；不得先卸载原父子树后手工移除 portal 节点。原生关闭必须经过 renderer.remove 释放 SDK modal、inert 和焦点，再允许错误区 Clear 重新挂载。清理不移除其他实例或其他 modal，不调用整个扩展 scope.dispose。本地正式 SDK 集成组件测试核对释放及其他 modal 隔离；生产浏览器恢复仍须独立验收。恢复仅支持本次 activation 唯一关联且仍连接于本 portal 的真实原生关闭节点；未知或多匹配关闭节点保持失败关闭，不点击猜测目标，也不保证错误区可恢复。外部物理移除原生 scrim 的情况未获公开 SDK 安全恢复保证，独立诊断仍有 renderer 异步错误；不得宣称该情况通过。

### 删除成功后的规范路径与公开基础样式

删除 scope、应用或组成功后，先完成授权数据重读，再通过公开宿主导航确认规范路径；事务与重读期间保留保存锁，不放行外部离开。删除非当前侧栏对象保留当前选中项；删除当前对象选择后继，最后项删除后进入父列表。规范导航异常必须显示错误，不自动重发删除；旧 generation 不覆盖新状态。

管理资源构建必须保留公开 `@kestra-io/design-system/styleBase` JS 入口的 CSS side effect；该公开基础样式包含 kel namespace 的原生 overlay 定位、背景及主题变量。不得以自造遮罩或修改原生焦点样式替代缺失导入。构建规则与组件回归仅证明静态输入和导航链路；遮罩计算样式、全部主题及窄屏仍须生产浏览器验证。App 级原生分页和内存宿主 router 历史回归通过不代表已复现或解决生产运行的 query/历史失败。

### 列表每页数量的原子更新

管理表格切换每页数量时，ManagementTable 只转发一次公开 pageSize 事件，由列表消费者在同一次 query 更新中设置新 size 与 page=1，保留原有筛选和 scope 条件。不得在同 tick 再发基于旧 route 状态的 page1 更新覆盖新 size。组卡片的独立分页直接更新本地 size/page，不走这条 query 写入链路。真实 App、ManagementTable 和原生 ElPagination 的100选项回归检查宿主回传、page1、全部授权行及筛选保留；本地组件回归不代替生产浏览器验收。

### 原生 box 导航与正式移动视口输入

顶层应用组/用户及组内四详情 tab 显式消费公开 `KsTabs type="box"`，与宿主 MCP Servers 同类导航采用相同组件模式；不以本地字号 CSS 覆盖默认 tab。公开 DS0.5 的 box/segmented item 使用 `--ks-font-size-xs`，默认模式使用 `--kel-font-size-base`。组件测试核对真实 box DOM、选中态及切换后的授权内容；计算字号、主题与窄屏图样仍须生产浏览器对照，不能据组件挂载宣称完成。

当前编译输入唯一由 `host-contract.properties` 和独立 `prepare-host` 生成的 `.integration/host-provenance.json` 确定。`27520a2`（成功 CI `40941713707`）仅为已发布的移动边界历史基线；桌面高级筛选及移动 sheet 的 viewport 边界、逻辑父/邻接与清理由新正式宿主承担，插件不复制定位或修改 vendor。本轮输入消费与筛选/导航增量仍须对应最终插件 CI、实际下载产物及根仓组合验收，不能以宿主发布或本地门代替。

subject 深链每次读取继续通过公开 scope-grant 与账号入口重验当前授予和账号状态。
URL 中的 scopeKeys 只保留与当前授予的交集；交集为空时移除全部 scopeKeys，保留
filterSubject 并显示零条权限，不退回未筛选列表。部分交集只保留有效 scope。
读取自身的同路径 query 规范化也适用于显式刷新，不解除业务保存、凭据或脏草稿保护。
真实 App、公开设计系统、内存 router 与宿主导航回传组件测试覆盖撤权、部分交集、
历史路径重验、缺失主体、禁用账号和非法主体 query。同路径 subject 慢读期间，通过
公开凭据面板真实轮换覆盖凭据写锁与 oneTime 的 tab/宿主导航拒绝；关闭 oneTime 后
释放读取，仍须保留 filterSubject、零行并规范 URL。该测试不覆盖 editor.saving 与
subject 慢读的同时交错，也不代替生产组合及浏览器验收。

### 受控 tab 与丢弃确认

公开 KsTabs 的 before-leave 在原生 active 改变前调用现有导航保护；未获宿主受控路径
确认时不改变 active。取消丢弃保留草稿及原 active，重复点击同一目标必须再次确认。
宿主 modelValue 同步后才允许对应 active；不重建 tabs，不访问组件私有状态。
真实保存、一次性凭据和脏草稿继续使用既有保护。英文 box tabs 的真实 App 组件测试覆盖
组/scope/应用草稿取消后重试、确认后导航、取消焦点恢复、正常切换，以及账号保存与
一次性凭据期间的 tab/宿主导航拒绝；不代替生产浏览器或最终根仓组合验收。
