# 应用项目授权模型

状态：当前目标规范。schema2、运行时、迁移工具与管理页面已实现；`4bfcb29` 的独立插件 CI 和产物已核对。新的宿主输入与页面修复正在验证，Compose 全矩阵尚未通过，不得据此宣称整体交付。

本文定义 `policy-source: database` 下的应用项目授权模型。本文是新增需求来源，
不修改也不覆盖 `docs/rbac-management-plan.md`、`docs/authorization-flow.md` 和
`docs/management-api.md` 中已经记录的历史调查。后续实现必须以本文为当前目标；
旧文档中与本文冲突的内容标记为历史背景，不得作为新实现依据。

本文记录用户已确定的概念和行为约束，不宣称当前代码已支持这些能力。JSON 字段、
schemaVersion 和新路由是目标草案，必须在实现前完成契约审查；不得仅因本文存在而
认为宿主契约已经接收或发布。首项工作是追踪实际 token 签发、校验与身份传播链，产出
“当前行为、目标差异、所属仓、公开契约缺口、验证方法”对照表，再冻结实现契约。

## 1. 目标

当前项目采用统一的 OAuth2/OIDC 身份模型。Kestra overlay 提供 OIDC Provider，UI、
MCP 和其他独立 client 都是该 Provider 的 client。每个 client 从经过验证的 token
取得 OAuth2 scope；插件把 scope 解释为当前应用项目角色，再把每个应用项目角色绑定
到完整的本地权限集合。

```text
OIDC Provider
  -> verified issuer, audience and scopes
  -> application project role (one scope)
  -> complete grants for that scope
  -> REST, UI and MCP authorization
```

当前项目使用新模式，不实现旧的“OAuth2 scope -> 兼容本地角色 -> 权限”运行路径。
本地角色不是当前项目的中间对象。应用项目角色直接绑定权限；历史本地角色只作为
调查资料，不得成为新策略的必需输入。

## 2. 应用项目

应用项目是多个 client 共享一套 scope 语义和授权边界的逻辑项目。UI client、MCP
client 和其他 client 只要属于同一个应用项目，就必须对同名 scope 使用完全相同的含义。

每个应用项目必须明确记录：

- `id`：项目唯一标识；
- `issuer`：唯一可信 OIDC issuer；
- `audience`：该项目接受的 token audience；
- scope 定义及其完整 grants；
- 启用状态和修订版本。

应用项目目录、项目角色目录及 client 归属是跨系统身份管理能力；其事实源必须由
OIDC 提供商所属仓调查并裁决，不能默认全部由 RBAC 插件维护。插件拥有本系统对项目
角色的权限绑定与数据库策略。共享目录与本地绑定必须通过已发布公开契约关联，
不得双方分别维护互相冲突的同名项目或 scope 定义。

scope 的匹配不能只按字符串进行。issuer、audience、租户和项目必须先通过验证，任一
项不匹配时失败关闭。

当前部署至少包含一个应用项目。UI、MCP 和其他 client 必须使用同一应用项目定义，
不得为某个 client 创建特殊的权限解释。

## 3. Scope 与应用项目角色

在当前项目中，一个 OAuth2 scope 就是一个应用项目角色。scope 名称在同一应用项目
内必须唯一、稳定并具有完整语义。推荐使用带项目和资源语义的名称，例如：

```text
kestra:team-a:flows-read
kestra:team-a:flows-operate
kestra:team-a:executions-read
```

每个 scope 必须独立解析完整授权。一个 scope 定义必须同时声明：

- 命名空间范围；
- 资源类型；
- 允许动作；
- 是否包含子命名空间；
- 资源 ID 限制；
- 输出或其他附加能力限制。

禁止把授权条件拆到不同 scope 后在运行时拼接。例如，禁止让一个 scope 只提供
`team-a` 命名空间，另一个 scope 只提供 `UPDATE` 动作，再把两者合成为写权限。
每个 scope 都必须能够单独说明“允许对哪些资源执行哪些动作”。

多个 scope 可以同时出现在 token 中，但只进行完整 grants 的并集：

```text
effective grants = union(grants(scope-1), grants(scope-2), ...)
```

不得执行以下组合：

```text
namespace(scope-1) + action(scope-2)
resource(scope-1) + id(scope-2)
```

未知 scope、禁用 scope、重复 scope 定义、无完整 grants 的 scope 和无法解析的 grant
都必须默认拒绝。策略导入或管理保存可以提前拒绝这些输入；运行时不得忽略未知项后
继续允许请求。

上述未知 scope 规则针对当前应用项目角色。`openid`、`profile`、`email` 等标准协议
scope 不构成资源授权，不得误判为未知项目角色。项目角色与协议 scope 的分类规则必须
显式定义并测试，不能靠冒号或前缀猜测。混合已知与未知项目角色时，本次受保护操作
拒绝；不得从已知子集取得部分允许结果。

## 4. 权限模型

应用项目角色直接绑定权限。权限至少包含：

```json
{
  "scope": "kestra:team-a:flows-read",
  "grants": [
    {
      "scope": "NAMESPACE",
      "tenant": "main",
      "namespace": "team-a",
      "descendants": true,
      "resource": "FLOW",
      "actions": ["READ", "LIST"],
      "allIds": true
    }
  ]
}
```

`READ`、`LIST`、`CREATE`、`UPDATE`、`DELETE`、`EXECUTE` 和 `ACCESS_OUTPUTS` 必须分别
验证。查询范围必须同时满足角色 grants 的范围和请求的查询域；不能因为 token 具有
scope 就跳过命名空间或资源 ID 检查。

一个 scope 可以包含多个资源的完整 grants。例如 `flows-read` 可以同时包含 Flow
和 Execution 的只读权限。它仍然是一个独立、可审计的授权单元。

权限并集不得产生原 scope 不具备的范围。READ/LIST 或复合操作所需的多个权限必须
在各自完整 grant 的实际资源范围内判断；不得把 A 范围 READ 与 B 范围 LIST 合成 B
范围读取，更不能合成为 A、B 的笛卡尔积。每一步允许结果应可追溯到提供它的完整 scope
grant。复合操作可以由多个完整 scope 分别授权其实际子操作，但每个子操作都必须满足
自身全部资源、范围和动作条件。

Namespace 之外确有宿主全局或租户元数据操作时，必须逐项列明其真实资源范围与例外，
在契约审查中裁决。不得为了适配旧 grant 无条件开放 TENANT、SYSTEM 或 INSTANCE。

## 5. Token 与运行时

运行时按以下顺序处理请求：

1. 验证 token 签名、issuer、audience、有效期和身份 subject。
2. 读取经过验证的 OAuth2 scopes；不得信任请求体、URL 或未经验证的 header。
3. 找到当前应用项目并检查 scope 定义。
4. 对每个 scope 独立取得完整 grants。
5. 将完整 grants 合并为当前请求的有效权限集合。
6. 对实际资源、动作、租户、命名空间和资源 ID 执行授权。
7. 任意未知 scope、身份缺失、数据库错误、策略版本错误或授权异常都失败关闭。

必须核对实际 ID token、access token 和认证后 cookie session 的声明；OAuth2 协议
不保证 ID token 包含 `scope`。UI 登录必须通过宿主可信机制保留经过验证的授权 scope，
再经公开身份契约传播；不得解码未校验 JWT、从请求参数补 scope 或将 `roles` claim
静默改名为 scope。同时核对授权码、refresh token、用户停用、scope 分配与用户同意，
确保 client 自行请求 scope 不等于获得 scope。签发端只授予该 subject 和 client 被允许
获得的 scope，资源端只消费经校验且 audience 正确的结果。

同一应用项目不要求 UI 与 MCP 使用相同 audience 字符串。它们必须消费一致的项目角色
语义，但资源服务器必须分别验证适用的 audience；不得为了复用角色而接受错误 audience
的 token。具体 client、资源 audience 与应用项目的关联由身份契约审查确定。

每个完整授权评估使用一个不可变数据库修订快照。策略变更只影响新的评估；不得在同一
操作中混用不同版本。列表、总数、统计、分页和聚合均须在授权范围内计算。

UI、REST 和 MCP 必须消费同一份应用项目策略。MCP 仍有协议能力 scope，例如
`mcp:flows:read` 和 `mcp:executions:write`；这些 scope 是 MCP 能力门，不替代应用
项目 scope 的资源授权。MCP 请求必须同时通过：

```text
MCP capability scope gate
  AND
application project scope grants
```

完整工具列表也必须按当前 token 的应用项目 scope 动态计算。隐藏工具不能只依赖
`tools/call` 时拒绝；撤销 scope 后，同一 session 的下一次列表和调用都必须收回权限。

## 6. 数据库策略

数据库是唯一运行时策略来源。Compose 只声明：

```yaml
policy-source: database
```

Compose 不得提供 YAML `roles`、`subjects`、`role-mapping` 或 `instance-admin-roles`
作为运行策略。应用项目、scope 和 grants 通过数据库策略初始化工具及管理页面维护。

新模式不得继续通过旧 `subjects` 绑定、`principal.roles` 或旧 `roleMapping` 为普通
请求补授权。账号到应用项目角色的授予属于签发端身份管理；本地绑定只决定已授予的
项目角色在本系统可以执行什么。旧数据必须显式迁移、校验并由操作者确认，不得静默
推断等价 scope，也不得在运行期双读两套模型。迁移失败时保留原数据库并拒绝启用新模式。

首次安装管理员引导必须单独设计并测试。现有 `management-admin-subjects` 是否保留为
受限的首次引导凭据、如何转为项目管理 scope、如何防止最后一个管理员被删除，均为
实施前必须裁决的问题；不得静默保留永久 subject 超级权限来绕过新模型。没有项目角色
的正常账号必须零授权，同时能从拒绝页退出换号。

目标策略结构如下：

```json
{
  "schemaVersion": 2,
  "applicationProjects": {
    "kestra": {
      "issuer": "https://identity.example.invalid",
      "audience": "kestra",
      "scopes": {
        "kestra:team-a:flows-read": {
          "enabled": true,
          "grants": []
        }
      }
    }
  }
}
```

实现可以使用等价的规范化表结构，但必须保留上述语义。schema 升级必须提供明确的
验证、初始化、并发修订和失败关闭行为。不得把未知字段静默转换为旧本地角色，也不得
用旧配置策略作为数据库策略的回退来源。

## 7. 管理 UI

管理页面必须让管理员完成应用项目授权维护，不需要修改 Compose 或 YAML。页面至少
需要提供以下路径和行为：

```text
/ui/main/extensions/rbac/projects
/ui/main/extensions/rbac/projects/{project}/scopes
/ui/main/extensions/rbac/projects/{project}/scopes/{scope}
```

页面必须显示并维护：

- 应用项目 ID、issuer、audience 和启用状态；
- scope 名称及说明；
- scope 的完整命名空间范围；
- 资源、动作、子命名空间和资源 ID 限制；
- 当前修订、保存冲突和数据库错误；
- 未知或无效 scope 的明确拒绝原因。

页面不得把 scope 拆成一个“命名空间选择器”和另一个独立的“动作角色”，再让用户
误以为二者可以跨 scope 组合。每个 scope 编辑器必须在同一详情中展示其完整 grants。

UI 必须消费已发布的宿主 UI SDK，使用宿主同构的 tabs、按钮、搜索、筛选、表格、分页
和主题变量。必须验证 light、dark、跟随系统、窄屏、空数据、加载、错误、保存冲突、
键盘焦点和实际翻译。页面可见、静态资源可下载或菜单可见不能证明管理 API 有权限。

## 8. 管理 API

API 必须按应用项目和 scope 提供数据库 CRUD，并使用 `If-Match` 修订保护并发更新。
建议接口：

```text
GET    /api/v1/{tenant}/extensions/rbac/projects
POST   /api/v1/{tenant}/extensions/rbac/projects
PUT    /api/v1/{tenant}/extensions/rbac/projects/{id}
GET    /api/v1/{tenant}/extensions/rbac/projects/{id}/scopes
POST   /api/v1/{tenant}/extensions/rbac/projects/{id}/scopes
PUT    /api/v1/{tenant}/extensions/rbac/projects/{id}/scopes/{scope}
DELETE /api/v1/{tenant}/extensions/rbac/projects/{id}/scopes/{scope}
```

所有写入必须检查管理员身份、CSRF、issuer、audience、scope 唯一性、grant 完整性和
修订版本。删除或禁用 scope 前必须检查当前 token 和 session 的撤权行为；删除不能把
未知 scope 转换为允许。

## 9. 测试要求

### 单元和策略测试

- 每个 scope 独立包含 namespace、resource 和 actions；不允许拆分拼接。
- 多个完整 scope 的 grants 并集正确。
- 未知、禁用、重复和空 grants 的 scope 失败关闭。
- issuer、audience、tenant 不匹配失败关闭。
- READ、LIST、CREATE、UPDATE、DELETE、EXECUTE 和 ACCESS_OUTPUTS 分别测试。
- 数据库修订冲突、数据库不可用和损坏策略均拒绝请求。

### API 测试

- 管理员可以创建、编辑、禁用和删除应用项目及 scope。
- 非管理员的读取和写入均按契约拒绝，并核对数据库、存储和队列没有副作用。
- 保存后重新读取策略，确认完整 grants 未被截断或跨 scope 移动。
- 并发 `If-Match` 冲突返回明确错误，不能覆盖较新策略。

### 真实浏览器测试

- 从生产登录流程进入 `Access management`，打开应用项目和 scope 页面。
- 验证表格显示 scope、命名空间、资源和动作列；搜索、筛选、分页和刷新保持一致。
- 在明暗主题和窄屏下验证页面样式与宿主 MCP Servers、Namespaces、Flows 页面同构。
- 保存一个 scope 的完整 grants，刷新页面，确认命名空间和动作仍属于同一个 scope。
- 使用未知 scope 或空 grants 保存，确认页面显示拒绝原因且没有策略变化。

### REST、UI 和 MCP 组合验收

- token 只有读取 scope：读取允许，写入和执行拒绝。
- token 只有写入 scope：按该 scope 的完整 grants 判断，不能借用其他 scope 的命名空间。
- 两个完整 scope：权限为两个 grants 的并集。
- 未知 scope：UI、REST、MCP 列表和 MCP 调用都失败关闭。
- 同一 token 的 scope 被数据库禁用后，新请求和同一 MCP session 的下一次列表均拒绝。
- 没有应用项目 scope 的账号保持零授权，即使 OAuth client 登录成功。
- MCP 能力 scope 缺失时拒绝；应用项目 scope 缺失时也拒绝；两者都满足才允许。
- 拒绝写操作前后比较数据库、Flow、Execution、队列和存储状态。

## 10. 仓库职责与实施顺序

| 仓库 | 责任 | 禁止事项 |
|---|---|---|
| `kestra-overlay` | 内置 OIDC 协议、可信 scope 传播、通用公开 SPI 和必要宿主激活点 | 不承载本地 grants 或扩展业务页面 |
| `kestra-plugin-rbac` | 本地项目角色到权限的数据库绑定、授权编译、管理 API、UI 和迁移工具 | 不读取相邻源码、不修改宿主用户或 client 表 |
| `kestra-monorepo` | 固定产物、Compose 配置、CI 角色矩阵和真实浏览器组合验收 | 不实现项目目录或授权业务 |

应用项目共享目录的管理界面和 API 归属，必须先按协议基础设施与业务功能边界裁决。
本文中的插件页面与 API 草案表示用户操作目标，不授权把共享身份目录直接移入插件。
若现有公开契约不足，先在 overlay 所属能力文档提出最小提案，注明未发布状态；
宿主接收、测试和正式发布后，插件才能消费。不得为实现便利复制内部 OIDC 服务或表结构。

1. 在插件仓定义应用项目、scope 和完整 grants 的公开策略模型与验证器。
2. 在数据库初始化工具中写入和读取应用项目策略，固定未知 scope 的拒绝语义。
3. 增加管理 API 和并发修订保护。
4. 使用已发布 UI SDK 实现应用项目和 scope 管理页面。
5. 让 UI、REST 和 MCP 授权统一消费应用项目 scope 解析结果。
6. 宿主有缺口时，先独立修复公开契约、通过宿主 CI 并发布正式镜像；不得把业务模型移入 overlay。
7. 插件从已发布宿主取得编译输入，独立编译插件 jar、通过自身 CI 并发布产物，再更新 Compose。
8. 使用正式 overlay 镜像、插件 jar 和真实浏览器执行组合验收。
9. 下载最终提交的 CI 报告，核对 scope、资源范围、拒绝、撤权和无副作用证据。

每个阶段必须保留可核对的结果，不得把源码完成记为 CI 通过。现有代码中的
`PolicyEngine`、`RuntimePolicies`、`PolicyDocumentValidator`、`ManagementController`、
`PolicyImportCommand` 和 `ui/src/App.vue` 是改造调查入口，不是要求保留的旧模型。
实施前读取本仓 README、AGENTS.md 和公开 `host-contract.properties`，核对当前 Git
状态与已发布契约；部署矩阵调查根仓 `scripts/ci-policy.py` 和各黑盒脚本。调查源码不得
形成独立仓构建依赖。

测试配置必须迁移为完整 scope：reader、writer、多范围和精确 namespace 账号由签发端
授予实际 scope，通过真实登录取得；不得注入伪 principal 或手工修改 token。增加
“A 范围只读 + B 范围可写”组合，断言 A 写入拒绝、B 写入允许、范围外拒绝，专门证明
没有跨 scope 拼接。保留 REST/MCP 同操作对照、缺组件、关闭功能回归和拒绝无副作用。

如果当前 CI 因额度或外部服务无法执行，记录为阻塞，不得写成通过。只修改本文不改变
旧部署；实际启用新模式时必须提供经过验证的数据迁移和回退步骤，回退使用迁移前备份与
匹配的旧产物，不得把两种模型同时开启。

## 11. 迁移前架构与新模型的对应

本节既是迁移设计输入，也是旧实现的清理依据。下述现状依据编写本文时的源码核对，
不代表当前部署或镜像已经运行该版本。实施者必须重新核对 Git 指针、公开产物和实际
数据库；不得把目标 scope 模型误写成现有能力。

### 11.1 当前实际授权链与目标链

迁移前，插件数据库文档是 `schemaVersion: 1`，包含 `roles`、`subjects` 和
`roleMapping`。`PolicyEngine.effectiveRoles` 从 subject 直接绑定与可信身份角色映射
取得本地角色，再查找角色 grants。它没有把 OAuth2 scopes 作为普通授权角色来源。
`RuntimePolicies` 还按精确 subject 选择部署管理员策略。

```text
迁移前普通请求：
  authenticated subject -> subjects -> local roles -> grants
  authenticated roles claim -> roleMapping -> local roles -> grants

迁移前管理员：
  subject in management-admin-subjects -> administrative policy

迁移后普通请求：
  validated project scopes -> recognized project roles -> complete local grants
```

迁移前宿主已经提供 OIDC Server、UI client、MCP scope 能力门和可信 principal。
“已有 OIDC Server”不等于“所有 UI/API 已消费项目 scope”。必须追踪签发端、cookie
session、公开 principal 和授权入口，证明 scope 真正贯通，不得仅给字段改名。

### 11.2 概念与数据处置表

| 迁移前对象 | 新模型对应 | 必须执行的处置 |
|---|---|---|
| 多个独立 OAuth client | 同一应用项目的成员 client | 建立显式项目归属；保留各自 client ID、凭据、回调与 audience 校验，不合并认证身份 |
| 没有应用项目目录 | 共享项目、成员 client、项目角色目录 | 由身份提供商能力调查确定事实源与公开管理契约；插件只绑定本地权限 |
| 本地 `roles[].id/name/description` | 项目 scope 标识及本地权限说明 | 旧名称只作候选建议；必须明确选定项目 scope，不按同名自动授予 |
| 本地 `roles[].grants` | 某个项目 scope 的完整本地 grants | 可以复制候选权限内容，但必须检查范围完整、未知资源、租户和跨 scope 拼接后再批准 |
| `subjects[subject] -> local role IDs` | 签发端 subject 的项目角色授予 | 转为显式授予计划，通过身份提供商公开 API 执行；插件停止用 subject 直接补权限 |
| `roleMapping[trustedRole] -> local role IDs` | 项目 scope 到本地完整权限的绑定 | 不能只把键改名；先确认旧 claim 的主体和来源，再人工确认新 scope 及 grants，移除普通请求的旧映射路径 |
| `principal.roles` | 旧可信角色声明 | 身份协议可保留该声明，但新授权器不得消费它授予权限，也不得把它静默当成 scopes |
| `principal.scopes` 或等价可信上下文 | 已校验项目 scope 与协议 scope | 调查 REST/UI 是否完整传播；缺失公开机制时先修宿主，不在插件解析 token |
| `openid/profile/email` | 标准协议 scope | 保留协议语义，不生成本地权限，不作为未知项目角色拒绝依据 |
| `mcp:flows:read`、`mcp:executions:write` | 现有 MCP 能力限制 | 保留并显式分类；不能提供缺失的本地 namespace 或动作授权，不能为 UI 建立另一套项目角色解释 |
| `management-admin-subjects` | 待裁决的首次引导与项目管理权限 | 列出当前受保护 subject 和替代授予计划；切换前验证新管理路径与防锁死规则，不能自动永久豁免 |
| 旧 grant 的 `scope: NAMESPACE/TENANT/INSTANCE` | 资源范围类型 | 与 OAuth2 scope 是不同概念；新代码和 UI 必须使用清楚名称，不能当作项目角色键 |
| `revision`、ETag、不可变策略快照 | 新策略版本与请求评估边界 | 复用机制和并发语义，按新 schema 重新验证；不得把旧修订直接当作新策略已批准 |
| 用户目录与密码、账号启停 | 现有身份账号生命周期 | 保留公开目录能力；账号管理不等于授予项目角色，新增授予操作必须单独授权与验证 |
| Compose 五个历史 `deployment-*` 角色 | 不再是运行配置 | 不恢复；调查仅用于历史权限解释，测试账号改用真实签发的项目 scopes |

新模式不要求保留 `roleMapping` 这个字段名。用户要求保留的是可维护的绑定能力；旧字段
承载可信角色到本地角色，新绑定承载项目 scope 到本地完整权限，语义不同。新字段和
接口名须在契约冻结时明确，避免“字段还在所以迁移完成”的误判。

### 11.3 页面和 API 的替换关系

| 迁移前入口 | 目标处置 | 验证要求 |
|---|---|---|
| `Roles`、`/roles` | 替换为项目 scope 的本地权限列表与详情 | 列表显示项目、scope、namespace、资源、动作；不存在隐含本地角色跳转 |
| `Users`、`/users` | 保留账号资料和生命周期管理；关联项目角色授予入口 | 账号创建后默认无项目角色，资料保存不能附带授权；授予页显示身份提供商实际结果 |
| `Assignments` 中 subject 表单、`/bindings` | 替换为身份端项目角色授予流程，或明确导航到其公开管理页 | 不再写旧 subjects 权限；不得展示保存成功而 token 实际未获得 scope |
| `Assignments` 中 trusted role 表单、`/role-mappings` | 替换为选定项目 scope 的完整本地权限绑定 | 禁止仍要求填写旧角色名；保存、刷新与实际授权结果一致 |
| `/policy` 与初始化工具 | 新 schema 的校验、预览和事务切换 | 旧 schema 不能被当成新模式运行；迁移报告单独保留，不伪造零损失转换 |

旧 URL、旧 API 和旧 SDK 消费方必须列出清单。切换后停止旧写入端点；需要导航兼容时，
只允许明确跳转到新页面，不保留旧模型的授权旁路。旧 API 应返回已公布的明确拒绝结果，
不得静默转换或返回成功。URL 和错误码的最终选择属于接口冻结内容。

### 11.4 迁移算法与不等价项

迁移工具必须先生成报告，再由操作者显式批准执行。报告至少包含：

1. 输入 schema、当前修订、规范化策略 hash 与应用项目目标。
2. 每个旧角色、subject 和 trusted role 的引用关系及完整 grants。
3. 每项候选新 scope、本地 grants 与签发端授予计划，注明手工确认项。
4. 无法映射、无法表达、权限增加、权限减少和被删除的规则。
5. 管理员转换、防止最后一个管理员失效、重新登录及撤权计划。
6. 数据库备份、切换前置、失败恢复和验证步骤。

例如，旧角色 `reader` 在 A 范围只读，不等于 token 已拥有 `kestra:a:read`。工具可以
建议把完整 grants 绑定到经确认的新 scope；只有签发端授予完成并真实签发验证后，
对应账号才能获得新授权。旧 `reader`、`writer` 两个 claim 也不能只靠字符串相同而
转换为两个项目 scopes。

若同一旧角色混合多个范围，工具必须逐项显示；可以绑定到一个完整描述这些权限的
scope，或经操作者批准拆成多个各自完整的 scopes。不得先拆出 namespace scope 和动作
scope 再组合。若旧模型依赖跨角色拼接才能得到允许，必须标为不等价：按新约束重新定义
完整 grants，而不是偷偷保留旧组合算法。没有获批对应项的账号保持零授权。

同一权限集合可以绑定给不同项目 scopes，但修改一个绑定是否影响另一个必须明确：
首版不得用隐藏的共享本地角色产生联动。实现可以内部去重不可变内容，管理语义必须仍
是每个 scope 独立拥有完整 grants。

### 11.5 切换、撤权与清理

先备份并锁定输入修订，再校验目标策略、公开身份契约和管理员路径。维护窗口内停止旧
模型的写入，完成批准的签发端配置和本地策略切换。跨仓数据无法原子提交时，必须记录
每步状态并失败关闭；不能把两个系统的局部成功描述为整体成功。

切换时必须让旧 cookie session、access token、refresh token、授权码和 MCP session
按已裁决的失效机制退出旧授权路径。旧 token 缺项目角色不得自动补 scope；用户重新
登录后核对实际签发结果。仅重启 JVM 不能证明旧 token 或旧会话失效。

验证通过后删除旧普通授权入口、无消费者字段、旧表单、翻译、测试配置和文档中的当前
使用命令。历史需求文档继续保留；本文件及最终迁移记录说明哪些实现已被替代。旧数据
备份按恢复策略保留，不因“代码清理”删除用户部署数据。清理必须列出文件、字段和
消费者证据，不能只按类名或目录名整体删除。

回退必须恢复匹配的数据库备份、身份授予配置和旧发布产物，并重新校验会话失效；
不能只换 jar。已经签发的新 token 是否仍可访问必须实测。无法安全恢复的步骤必须在
切换前标明，不得在生产失败后再临时决定。

### 11.6 实现入口与迁移验收

| 入口 | 调查或改造重点 |
|---|---|
| `src/main/java/io/kestra/extensions/rbac/PolicyEngine.java` | 旧有效角色计算、grant 匹配、查询域与新 scope 解析算法的替换 |
| `src/main/java/io/kestra/extensions/rbac/RuntimePolicies.java` | subject 管理员旁路、数据库快照和新管理权限边界 |
| `src/main/java/io/kestra/extensions/rbac/store/` | schema 识别、严格验证、迁移预览、事务和回退证据 |
| `src/main/java/io/kestra/extensions/rbac/management/` | 旧 roles/bindings/mappings API 替换、公开目录接入与并发保护 |
| `ui/src/App.vue`、`ui/src/client.js`、翻译目录 | 旧三个 tab 的替换关系、真实 scope 与完整 grants 的显示和保存 |
| 根仓 `scripts/ci-policy.py` 及黑盒脚本 | 删除普通账号 subject 补绑定，使用身份提供商真实授予的 scopes |
| overlay OIDC 与公开 RBAC SPI | scope 签发、校验、UI session 和 MCP 上下文传播；仅通过正式发布产物消费 |

迁移验收必须同时提供旧输入、批准的对应表、新策略和行为差异。对每个测试账号比较
切换前后允许与拒绝范围；预期变化必须有需求依据。必须覆盖旧 subject 有权限但新 token
无 scope、旧 roles claim 有权限但无新 scope、已知与未知项目 scope 混合、旧会话残留、
旧 API 写入、身份端授予成功而本地绑定失败，以及本地绑定成功而身份端授予失败。
上述部分成功都不得获得未批准权限。切换前后的策略 hash、修订与产物作为报告证据，
不写死为长期版本锁。

## 12. 实施前必须冻结的规则与决策门

本文足以启动调查和方案实施，但以下问题不能由实现者自行猜测。它们不改变已确定的
新模式，而是将目标变成可执行契约。每项必须记录调查证据、所属仓、裁决和验证用例；
没有裁决时可以继续独立调查，不得启用依赖该结论的生产路径。

### 12.1 角色语义、范围与命名

scope 名称是稳定标识，不是未经约定的权限表达式。示例中的 `team-a` 不授权实现者
按冒号切分后直接生成 namespace 或动作；实际权限必须来自经过验证的项目目录和本地
绑定。若选择结构化名称，必须定义正式语法、规范化和转义规则，并提供兼容测试。

共享项目角色目录必须描述角色的完整授权意图和命名空间边界。本地系统可以在这个
边界内配置其资源权限，但不得把共享只读角色解释为本地写入或管理角色，也不得扩大
其共享范围。角色改名、删除、停用和语义变化必须与本地绑定同步验证；权限含义扩大
不能静默复用旧 scope 名称或旧用户同意。无本地绑定的项目角色在本系统拒绝。

需要冻结项目 ID、scope 标识大小写、namespace 精确/子树规则、资源 ID 编码和跨项目
同名处理。client ID 不得代替项目 ID；动态 client 注册不得自动加入应用项目，也不得
自行创建角色或获得项目 scope。必须定义加入项目、分配角色和批准成员 client 的管理权。

### 12.2 完整 scope 与多 scope 运算

对单一资源动作，只要一个完整且启用的 scope grant 同时覆盖资源、动作、namespace
和 ID，才允许该动作。不能从其他 scope 补齐该 grant 缺失的限定条件。

对要求 READ 和 LIST 的列表操作，首版目标为：每个 scope 独立计算其 READ 与 LIST
授权范围的交集，再合并各 scope 的有效列表范围。不得先跨 scope 合并 READ 与 LIST
再求交。该规则刻意收窄旧的“不同本地角色分别提供 READ/LIST”行为，迁移报告必须
明确列出因此失去权限的旧组合，要求重新批准一个完整 scope。

```text
listDomain = union(intersect(readDomain(scope), listDomain(scope)) for each scope)
```

复合业务操作可以分别对实际子操作授权，但每个子操作仍须由完整 scope 支持，且
全部子操作通过后才能产生副作用。必须列出执行、输出、关联查询及管理保存的子操作，
不能用入口允许代替内部操作允许。资源范围例外与管理权限须有显式类型，不使用通配
scope、名称包含 admin 或 client 特例推断。

### 12.3 身份授予与撤权时限

必须分别定义并验证三个变更：本地 grants 改变、项目角色停用、某个 subject 的项目
角色被撤销。第一类由新评估读取新数据库快照；后两类涉及身份端与仍有效的 token，
仅检查本地绑定或等待 token 自然过期不能声称即时撤权。

实施前冻结撤权生效时限及机制，包括 token/session 认证版本、在线检查或等价公开
机制，并覆盖 UI cookie、access token、refresh token 和 MCP session。报告记录撤销
时刻、下一请求结果和实际生效时间；不得用固定等待后看到一次拒绝代替时限验证。
角色授予、client 授权与用户同意的交集必须由签发端验证，刷新不能恢复已撤销的 scope。

### 12.4 管理权限和页面责任

管理必须区分共享身份目录管理与本地权限绑定管理。取得本地业务 scope 不授予创建
应用项目、加入 client、给用户授予 scope 或扩大 grants 的权力。管理 scope 及其完整
管理资源范围、最后一个管理员保护和首次引导退出条件必须在实现前冻结；正常管理
请求与普通请求使用相同 token 校验和项目角色解释。

除本地权限编辑外，完整交付必须提供可操作的项目成员 client 管理、subject 项目角色
授予/撤销和实际授权结果查看入口。页面可以通过公开契约调用正确所属项目，或明确
导航到该项目管理页，但不得把这些关键操作留成手工 SQL、未公开接口或未说明的外部
步骤。必须实测“创建账号 -> 授予项目角色 -> 配置本地权限 -> 登录 -> 执行 -> 撤权”
全过程；项目角色授予与本地 grants 编辑必须明确区分，防止管理员误以为保存一项就
自动完成另一项。

### 12.5 最小可裁决验收矩阵

下表是本地与 CI 共用的必需业务断言。每个请求都记录实际身份、项目、scope 集合、
目标资源范围、预期结果和持久化副作用；报告不得记录完整 token 或凭据。

| token / 变更 | 预期 |
|---|---|
| A 完整只读 scope + B 完整写入 scope | A 读取允许、A 写入拒绝、B 写入允许、范围外拒绝 |
| A READ-only scope + A LIST-only scope | 列表拒绝，不能跨 scope 拼出完整列表权限 |
| A READ scope + B LIST scope | A、B 范围均不能由这两个 scope 拼出列表读取 |
| 只有标准协议 scope | 登录可以成功，业务零授权，拒绝页可退出换号 |
| 已知项目 scope + 未知项目 scope | 本次受保护操作拒绝，已知子集不得绕过未知项 |
| 已知角色但无本地绑定，或绑定为空 | 拒绝，无默认权限 |
| 相同 scope 名称但 issuer、audience 或项目错误 | 拒绝，不能跨信任边界复用 |
| client 请求未获授予的项目 scope | 签发端拒绝或按冻结契约明确收窄，资源端不能收到伪授予 |
| 本地撤权、项目停用、subject 角色撤销 | 分别按冻结时限检查 UI/API/MCP，旧刷新凭据不能恢复授权 |
| 身份端或本地数据库故障、部分迁移成功 | 失败关闭，不复用旧允许，不产生写入副作用 |

最终组合测试必须覆盖至少两个属于同一项目的真实 client：UI 与 MCP。断言同一项目
角色在两者上具有一致的本地资源语义；MCP 额外协议能力限制必须单独验证。需要额外
HTTP client 时使用正式授权流程，不能用测试 header 伪造 scope。

### 12.6 阶段产物与停止条件

1. 调查产物：签发到授权的实际调用链、旧新对应表、上述未决项清单与仓库归属。
2. 契约产物：项目目录、成员 client、subject 授予、本地 grants、管理员引导、scope
   分类、schema/API、撤权时限的冻结方案；有宿主缺口时先提交最小提案。
3. 宿主产物：必要公开机制通过独立测试、CI 与发布门，并取得正式可消费产物。
4. 插件产物：按已发布契约实现新策略、迁移、API、UI；独立 CI 与发布通过。
5. 部署产物：批准的迁移报告、备份和切换步骤，最终产物组合及真实浏览器/安全报告。

新 AI 必须按依赖顺序推进。遇到会改变用户已确定语义的选择时明确提请裁决；常规实现
细节可以自主决定。不得为赶进度省略身份端授予管理、管理员切换、旧 session 处理或
双 client 验收。完成每阶段后更新本文件的目标/已实现状态与证据入口，保留历史需求
来源，不将候选方案写成已发布能力。

## 13. 完成标准

只有以下条件全部满足，才可称为应用项目授权交付：

- 数据库是唯一运行时策略来源；
- 当前项目不依赖旧本地角色兼容路径；
- 每个 OAuth2 scope 独立包含完整命名空间和动作授权；
- 未知 scope 默认拒绝；
- UI、REST 和 MCP 使用同一策略解释；
- 管理员可以通过页面维护应用项目和 scope 的完整 grants；
- 明暗主题、筛选、显示列、保存冲突和错误状态通过浏览器验收；
- 独立插件 CI 通过；
- overlay patch-verify、构建、MCP 黑盒和镜像发布通过；
- Compose 真实验收通过，并核对最终镜像 digest、插件 jar hash 和最终提交。


## 实施契约与发布边界（已冻结，组合验收进行中）

候选 schemaVersion 2 使用 `applicationProjects` 映射。每个项目包含 `issuer`、精确 `audiences` 集合、`enabled`、正整数 `directoryRevision` 和 `scopes` 映射；每个 scope 包含 `description`、`enabled` 和非空完整 `grants`。UI 与 MCP 可使用不同 audience，但必须通过显式 client 目录绑定到项目，不接受通配或按名称推断。IdP 提供项目目录与角色授权意图上界；插件只保存本地收窄和只读投影，不构造第二事实源。目录版本不匹配时必须拒绝，直到本地重新确认；策略全局 `revision` 与 `directoryRevision` 独立。

普通请求只接受宿主公开契约验证的 subject、issuer、audience、project、client binding、scopes。`roles`、`subjects`、`roleMapping` 不参与。单动作必须由一个完整 grant 覆盖全部 tenant、namespace、resource、action 和 ID 条件。列表使用 `union(intersect(READ(scope), LIST(scope)))`，先 scope 内相交再跨 scope 求并集；纯模型的 `authorizeList` 保留每项命名空间与 ID 的关联，先在同一 scope 内求 READ/LIST 的完整交集，再合并关联项；不得分别合并 namespace 与 ID。旧的仅命名空间 `authorize` 列表入口仍明确拒绝受限 ID。新的宿主查询消费适配尚未发布、尚未接入，不能把纯模型测试记为生产受限 ID 列表已经通过。未知、disabled、空 grants、身份错误和目录版本不匹配均失败关闭。

候选管理 API：`GET/POST /api/v1/{tenant}/extensions/rbac/projects`、`GET/POST /.../projects/{id}/scopes`、`PUT/DELETE /.../projects/{id}/scopes/{scope}`。写入需要显式项目管理 scope、CSRF 和强 `If-Match`；项目成员、client、subject 授予和撤权属于 IdP API。管理员不得使用永久 subject 旁路。


管理模型冻结补充：scope 可以包含 `managementGrants:[{domain:IDENTITY|LOCAL_POLICY|BUSINESS_INSTANCE,actions:[READ|MANAGE]}]`。LOCAL_POLICY 的空 projectIds 仅覆盖所属 project，非空集合只覆盖精确列出的项目；BUSINESS_INSTANCE 仅覆盖所属项目，IDENTITY 覆盖精确 issuer；租户固定 main，三个域互不包含，READ 不授予 MANAGE。JSON 省略 managementGrants 等于空；business grants 与 managementGrants 不得同时为空。管理授权必须同时满足可信目录同 scope 的管理意图上界与本地管理 grants。`authorizeManagement(identity,directory,domain,action)` 不形成业务全实例旁路，BUSINESS_INSTANCE 后续只经显式 typed 资源表集成。当前目录 DTO 用 ScopeDefinition.managementGrants 表达可信 managementIntents 投影，不消费未发布宿主 SPI。

共享元数据例外：仅接受 `scope:TENANT,tenant:main,resource:METADATA,namespace:null,descendants:false,actions:[READ|LIST],ids:[],allIds:true` 的完整 grant。必须有可信目录同 scope 的显式上界；任意 namespace READ 不授予此权限。该资源仅用于宿主公开 METADATA 非业务共享元数据操作，不能用于其他 TENANT 业务、SYSTEM、INSTANCE 或管理授权。纯模型已定义本地 Resource.METADATA，宿主 adapter 尚未集成。

目录版本重新确认例外：`authorizeReconciliation(identity,directory)` 仅用于修正/重新确认本地绑定，不授业务、身份目录或账号/client/assignment CRUD。身份必须为可信当前目录版本，issuer、audience、project 全匹配；仅允许忽略本地 directoryRevision 过期。逐 token scope 校验已知、启用和完整本地绑定，并要求实际目标上本地与当前共享同 scope 同时具有 LOCAL_POLICY.MANAGE；不检查未出现于 token 的无关 local scope。普通业务与普通管理仍严格校验本地版本，保存候选仍必须完整校验当前目录与最后管理员保护。Identity DTO 显式保存 tenant，仅 main 允许；七参数纯 DTO 构造明确默认 main，宿主 adapter 必须传入已验证 tenant。


### 已接收的非命名空间资源范围（2026-10-17，实施中、未发布）

本节按实际宿主类型化操作裁决范围，不表示已通过组合验收。普通业务 grant 必须使用
明确 `NAMESPACE` 范围。以下例外仍须由同一个项目 scope 独立、显式声明，并受身份
目录同 scope 的完整资源意图上界约束；任意普通 READ 不隐式获得这些权限。

- `METADATA`：`TENANT/main`，无 namespace，仅 READ/LIST，必须 allIds。只消费宿主
  类型化 METADATA 的配置、插件目录及其他非业务共享元数据。图表实际业务查询仍单独授权。
- `MCP_SERVER`：`TENANT/main`，无 namespace，配置资源的 READ/LIST/CREATE/UPDATE/DELETE。
  宿主 `McpServerController` 的 listMcps/getMcp/createMcp/updateMcp/toggleMcp/deleteMcp
  实际操作不属于某个 flow namespace，必须独立授权。工具关联 Flow 列表继续使用 FLOW
  命名空间权限；查看配置不授予执行工具或修改配置。资源 ID 限制保持完整，未适配 ID
  查询域的入口必须拒绝，不得返回全量列表或虚假的 total。
- 管理域 `IDENTITY` 是精确 issuer 下的共享身份目录；LOCAL_POLICY、BUSINESS_INSTANCE
  属于应用项目和 main tenant。三个域互不包含。管理 scope 使用显式 managementGrants，
  不从名称、旧 roles 或 subject 推断。BUSINESS_INSTANCE 不成为普通业务资源的通行旁路。

目录修订不匹配时，普通业务仍拒绝。唯一恢复例外是重新确认本地绑定：当前真实身份
必须持有仍被最新目录同 scope 上界允许的 LOCAL_POLICY.MANAGE，本地管理绑定必须仍
启用，未知/禁用项目 scope 混合仍拒绝。此入口只能修正、校验并显式确认本地绑定，不
开放身份目录 CRUD、账号管理或业务操作。保存前完整验证最新目录边界及最后管理员保护。


### 本地管理目标和完整绑定恢复（主控冻结，实施中、未发布）

共享 `managementIntents` 与本地 `managementGrants` 均使用 `projectIds` 精确项目 ID 集合。
只有 `LOCAL_POLICY` 可以使用非空集合；空集合只覆盖 scope 所属项目，非空集合只覆盖
列出的项目，不额外包含所属项目。不支持通配。`IDENTITY` 仍覆盖精确 issuer 的身份目录，
`BUSINESS_INSTANCE` 仍只覆盖所属项目/main；这两个域的 projectIds 必须为空。
本地动作和目标项目必须由同一 scope 的完整 management grant 提供，并被该 scope
的共享管理意图同时覆盖。不得从一个 scope 借管理动作，再从另一个 scope 借目标项目。
管理控制器必须传入请求的实际目标项目，不能只检查登录项目。

仅被 `LOCAL_POLICY.projectIds` 引用的目标可以尚无客户端成员和 audience。插件必须从
同一次可信目录读取中核对该项目存在、issuer 相同、tenant 为 main 且修订有效。
该引用不创建登录或业务绑定权限。登录项目和实际本地绑定项目仍必须具有非空的精确
audience；不存在或跨 issuer/tenant 的管理目标必须拒绝。停用项目仍是合法的精确管理目标，
已获准管理者可以确认其 disabled 本地绑定或撤回引用；该管理权限不允许停用项目登录、
执行业务或保存 enabled 本地绑定。

`GET /projects/{id}/reconciliation` 返回：
`{project,policyRevision,directoryRevision,upperBound:{project,scopes}}`。
外层 project 是包含 id 的完整 schema2 本地项目；policyRevision 是全局本地策略修订；
directoryRevision 是目标共享目录当前修订。upperBound 只含公开共享项目与 scope 定义，
不包含 client、assignment 或用户目录。此读取仅服务当前获准目标的本地绑定恢复，
不开放一般身份目录读取或 CRUD。

`PUT /projects/{id}/reconciliation` 输入包含 id、issuer、audiences、enabled、
directoryRevision 和全部 scopes 的完整本地项目，If-Match 使用 policyRevision，
返回现有完整 policy view。操作者必须显式审查全部 scope，必要时同时修正多个越界绑定，
再一次原子保存。服务端对全部候选重新验证当前共享上界和最后本地管理员保护；拒绝时
不得先更新 directoryRevision 或保存部分 scopes。普通 scope 保存和项目元数据保存
不得静默确认目录修订。恢复权限仍须由当前可信 LOCAL_POLICY.MANAGE 完整授权。

当前仅纯模型、存储服务和页面在实施；公开宿主、HTTP adapter 与实际浏览器尚未通过，
此节不得用作当前已部署能力或正式产物证据。

恢复授权只判断实际目标上仍同时存在的同 scope LOCAL_POLICY.MANAGE。若共享上界撤回
同一管理 grant 的其他目标，普通授权继续拒绝，但保留目标的管理者可以用完整恢复入口
删除已撤回目标。不得因此允许管理被撤回的目标；候选保存仍完整验证全部上界。


本地保存的完整上界检查由纯模型 `ProjectPolicyBoundary.validate` 提供：逐项目核对
issuer、精确 audiences、目录修订与启用状态，逐 scope 检查全部业务 grants 和管理
目标，未知共享目标拒绝。不允许跨 scope 借上界，也不因为未出现在当前 token 的绑定
而跳过保存校验。运行时请求仅解析 token 中的 scopes，完整保存校验则检查候选所有绑定。
该纯模型不认证身份，不读取 IdP 表，也不能单独证明最后管理员保护；正式 SPI adapter
必须提供可信一致目录快照和真实管理员可达性检查。当前尚未接入公开产物。


### 独立发布与后续组合门（2026-10-18）

本轮已实现可信项目 scope 直接授权、同 scope READ/LIST 交集、关联 namespace/ID 查询域、
管理 API、身份目录代理、完整绑定恢复和应用项目管理页面。运行时不注册旧 schema1 store，
不使用 subject/roles/roleMapping 或永久 subject 管理员；旧数据只作为离线审批来源。
`4bfcb29` 已通过独立 CI `39104160398`。实际下载的 artifact 包含 137 项 Java 检查、
37 项 PostgreSQL 存储检查，均无失败或跳过，并含 jar 与宿主输入 provenance。
该提交编译输入为正式宿主 `8279e82`；这项历史发布证据不替代后续版本的检查。
当前 host-contract.properties 已选择通过正式 CI `39110903137` 并推广发布的宿主
`5cac500`，包括 MCP 异步可信来源和 OAuth 错误回调修复。新的插件 CI、产物及
完整组合验收尚待通过。身份授予、本地保存、撤权、迁移与实际浏览器继续按本文
完成标准验证；前文的阶段候选记录只描述其当时状态，不作为当前部署结论。
