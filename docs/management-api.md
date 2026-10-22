# 应用组管理接口契约

状态：当前候选源码契约。宿主正式输入由 `host-contract.properties` 固定；插件正式 CI、
产物与部署仓真实组合验收尚须独立取得。本文不把单元测试或源码存在写成正式交付。

## 身份与事务

根路径为 `/api/v1/main/extensions/rbac`。其他 tenant 返回 404。宿主生产 OIDC、签名、
会话、CSRF 和标准 issuer 校验保持有效。插件只消费 `TrustedIdentityV1`，核对登录 subject，
不自行解析 token。请求体为单个 UTF-8 JSON 对象，最多 1 MiB；重复键、未知字段、null、
尾随文档、错误类型及路径与正文标识不一致拒绝。响应使用 `Cache-Control: no-store`。

IDENTITY 的 READ/MANAGE 控制本 issuer 的用户、应用组、scope、支持登记和用户授予。
LOCAL_POLICY 的 READ/MANAGE 控制指定应用组中配置的 Kestra 应用实际权限。二者不互含；
MANAGE 不隐含 READ。BUSINESS_INSTANCE 不授予上述管理权限。能力响应仅用于界面展示，
每次实际操作仍重新校验。

业务写入在 `READ_COMMITTED` 事务中先取得正式 `OidcProtocolStateV1` 的安全锁，再读取
当前标准账号/client、组修订、撤权版本和 scope 交集，重新校验操作者。目录写入、实际策略
条件提交与生命周期 guard 使用同连接标准身份检查。拒绝回滚所有相关写入。组、scope、
应用支持、用户授予、内置与退役记录均存于插件表。插件不读取宿主私有表。

## 标识与修订

应用组 ID 为 `[A-Za-z0-9][A-Za-z0-9_-]{0,127}`。scope key 为 `[a-z][a-z0-9-]{0,63}`，
路径直接使用稳定 key。应用 client 路径键为 `~` 加无 padding 的 UTF-8 base64url，服务端
解码后回编码检查规范形式。用户 subject 使用 URL 编码查询参数传递，保持精确原值。

条件写入使用强 `If-Match: "<revision>"`。缺少返回 428，弱标签、通配、负数、前导零、
溢出或列表返回 400。过时修订返回 412，创建冲突返回 409。创建应用组和账号无需修订；
创建 scope、应用支持或用户授予使用组修订；修改或删除子对象使用子对象修订。
账号更新可以使用修订 0。实际权限写入使用全局策略修订。

## 应用组 API

| 方法及路径 | 请求或响应 | 权限 |
| --- | --- | --- |
| GET `/application-groups?page=1&size=20` | 分页组卡片数据 | IDENTITY.READ |
| POST `/application-groups` | `id/tenant/displayName` | IDENTITY.MANAGE |
| GET `/application-groups/{id}` | `id/tenant/issuer/displayName/active/revision/authorizationVersion/builtIn` | IDENTITY.READ |
| PUT `/application-groups/{id}` | `displayName/active` | IDENTITY.MANAGE |
| DELETE `/application-groups/{id}` | 删除回执 | IDENTITY.MANAGE |

内置组固定 ID 且 `builtIn=true`，始终不可删除。普通组删除要求没有 scope、应用或用户
授予，并在同一事务登记退役 ID；退役 ID 不可重用。卡片进入只读详情，点击编辑后才显示
表单；有修改权限不等于有查看权限。页面没有默认操作列或配置状态。

## Scope、应用和用户授予

以下路径均在 `/application-groups/{id}` 下。列表返回 `items/total/page/size/directoryRevision`，
跨页消费者必须核对同一修订，不得只依赖总数。

| 路径 | 创建及修改输入 | 响应 |
| --- | --- | --- |
| `/scopes`、`/scopes/{key}` | `key/name/description` | `applicationGroupId/key/name/description/revision` |
| `/applications`、`/applications/{clientKey}` | `clientId/approved/audiences/supportedScopeKeys` | 输入字段及 `applicationGroupId/revision` |
| `/scope-grants`、`/scope-grants/detail?subject=...` | `issuer/subject/scopeKeys` | 输入字段及 `applicationGroupId/revision/authorizationVersion` |

三类入口支持 GET、POST、PUT、DELETE。GET 要求 IDENTITY.READ，写入要求 IDENTITY.MANAGE。
scope key 不可修改；角色 name 必填且同组精确唯一，description 不参与授权。删除 scope
要求组内全部应用和用户授予均已释放引用。应用登记须存在标准 active 或有效 pending
client；pending 只允许先登记支持并展示 consent，不能直接签发凭据。用户授予要求同一
issuer 的真实标准账号。当前 UI client 是普通成员，无 `firstPartyUi` 输入或输出。

用户在应用可获得的 scope 是应用支持与该用户授予的交集。混入未知、已撤销或不再支持
scope 必须整体拒绝。支持或授予变更原子推进组修订与撤权版本；旧会话须重新生产登录。
最后管理员保护分别检查用户管理及实际策略管理可达性，不按角色名称或永久 subject 推断。

## 应用权限声明

路径为 `/application-groups/{id}/applications/{clientKey}/scope-declarations` 及 `/{scopeKey}`。
GET 列表或详情需要 IDENTITY.READ。POST、PUT、DELETE 需要 IDENTITY.MANAGE，修改与删除
使用声明自己的强修订。写入体为 `permissions:[{key,name}]`，机器 key 与人类名称均必填。
创建和修改要求目标应用已支持该 scope。删除声明不改变应用支持，也不改变实际资源策略。

此处保存的是应用内部逻辑的元数据镜像，可与应用代码不同步，只用于声明与 OAuth2 展示。
它不是应用运行时授权事实来源，不是额外 scope 授予或撤权开关。

## Kestra 实际权限

路径为 `/application-groups/{id}/applications/{clientKey}/runtime-permissions`。client 必须与
配置 `micronaut.security.oauth2.clients.kestra.client-id` 精确匹配并确为目标组成员。
只读成员核对按 LOCAL_POLICY 的实际操作权限执行，不额外要求 IDENTITY.READ。
GET 要求目标 LOCAL_POLICY.READ，返回 `revision/applicationGroupId/clientId/policy`。
POST 创建、PUT 更新配置，输入 `issuer/audiences/enabled`，不接受组 ID 或 scopes。
写入要求目标 LOCAL_POLICY.MANAGE，成功只返回 `{revision}` 与 ETag。

`/scopes` 与 `/scopes/{scopeKey}` 管理该应用的实际 scope 策略。输入为
`scopeKey/description/enabled/grants/managementGrants`，managementGrants 可以省略。
资源 grant 使用 `scope/tenant/namespace/descendants/resource/actions/ids/allIds`；
管理 grant 使用 `domain/actions/applicationGroupIds`。同一个操作所需权限必须由同 scope
完整提供，不跨 scope 拼接 READ/LIST、namespace 或资源 ID。名称和声明元数据不授予权限。

策略条件提交在安全锁内重新授权与验证真实管理员可达性，不依赖先前 capabilities。
没有 READ 的合法写入不返回策略，后续读取仍独立校验。功能关闭返回 404，缺少配置返回
503，其他应用返回 404。旧全局 `/policy`、`/projects`、reconciliation、roles、bindings 与
role-mappings 路由不注册，不保留兼容别名。

## 标准账号与错误

`/users`、`/users/detail?subject=...` 提供分页、详情与创建；`/users/profile`、`/users/state`、
`/users/password` 修改标准账号。用户创建不自动授予 scope。标准账号由宿主 UserDirectoryV1
操作；停用和密码变更在宿主事务内调用插件 guard，保留最后管理员与撤权检查。

认证失败 401，权限拒绝 403，未知对象 404，修订/业务冲突 409 或 412，缺修订 428，
依赖不可用 503。最后管理员返回 409 `last_administrator`。错误响应不包含密码、token、
数据库语句或内部异常内容。真实 HTTP、CSRF、浏览器、撤权与无副作用门由部署仓验收。
