# RBAC 管理功能：机制调查与阶段方案

调查日期：2026-10-15。状态：调查与方案，不是实现或发布记录。
本文属于 `kestra-plugin-rbac`，定义本项目的业务调查与阶段方案，不是部署根仓的业务设计。
宿主机制需求由本项目向 overlay 提出，overlay 独立接收并发布；根仓只组织子项目、
选择部署组合和执行 Compose 集成验收。首项交付到此文档及宿主候选提案为止。

## 1. 裁决与范围

目标是在 `kestra-plugin-rbac` 提供用户、角色和授权管理页面，使管理保存的数据用于后续
REST、查询和 MCP 实际授权。业务模型、页面、管理控制器及策略存储归插件；overlay
只提供公开宿主机制、可信身份和账号目录能力；部署仓负责版本选择与真实组合验收。

本轮裁决如下。下文接口名和配置名均为候选名称，必须在对应项目实施前定稿并发布。

1. 先实现实例管理员管理。所有管理读取和写入都要求当前有效实例管理员身份。
   首期不提供独立的只读管理员或 namespace 管理员，不能把普通资源 READ 当作管理权限。
2. 保留现有配置模式；新增数据库模式，两者互斥。数据库模式不得按请求合并 YAML 策略。
3. 角色、grant、subject 绑定和可信角色映射均归插件；登录账号及密码归宿主 OIDC 目录。
   “用户管理”必须分别呈现账号状态、可信 subject、插件绑定及有效授权，不能混为角色字段。
4. 首期管理 API 可复用宿主现有 SYSTEM 管理门，再由插件按当前策略复核。无需先增加
   USER/ROLE/BINDING 到宿主资源枚举，也无需让插件识别宿主 controller 或 URL。
5. 管理页面需要新的通用装载契约。上游任务插槽不能直接注册管理菜单或页面。
   已发布 design-system 可以作为组件候选输入，但不能单凭包存在声明与宿主版本兼容。
6. 登录账号完整管理依赖新的宿主公开目录契约。不得调用内部 `OidcUserService` 或直接写
   `oidc_user`。在该契约发布前，可以交付角色与授权管理，不能称为完整用户管理。

不做：EE IAM 复刻、组、服务账号、邀请、SCIM、身份冒用、EE token、完整审计产品、
任意外部脚本 URL、运行时装载 bean、无重启替换 jar、多租户和跨 IdP 账号合并。
首期账号不提供改登录名或物理删除，避免当前 username 即 subject 的账号重用造成旧绑定继承。
新账号默认无可信管理角色、无插件绑定；创建账号不自动授权。

## 2. 证据基线与边界

以下为本次调查使用的部署环境历史证据，不是本项目构建输入或当前版本锁。
本节路径以当时部署根仓为基准，不要求独立克隆存在这些相邻目录。
启动时三仓工作树干净。HEAD 与当时 `git ls-remote origin refs/heads/main` 一致：根仓
`27954a0`、overlay `a7d4ea6`、插件 `58d38bd`。submodule 指针与这两个子仓一致。
交接文件完整读取后已删除，忽略规则保留。实际命令环境为 zsh；不采用交接中旧机器路径。

| 检查 | 本轮核实结果 | 能证明什么 |
|---|---|---|
| overlay 当前 HEAD CI | `38695537351` 失败；patch-verify 通过，build-push 依赖解析失败，后续发布门跳过 | 不能宣称当前 HEAD 镜像已发布 |
| 既有发布宿主 | `9a8dbdae8e13761e135f0e42ccd5ffe4a9635635`，CI `38686689513` 四个 job 成功 | 已发布宿主机制的基线 |
| 插件当前 HEAD CI | `38695540439` 成功，实际下载 `kestra-rbac-authorizer` artifact | 当前插件产物可获取，不含管理页面 |
| 既有组合 CI | `38688730525`，根提交 `66226c9` 成功，实际下载含隐藏目录的报告 | 只证明既有角色矩阵，不证明新管理功能 |
| 当前根 HEAD CI | 启动检查时 `38695562898` pending | 不能以既有组合报告替代当前 HEAD 结果 |

本次直接检查运行容器的 image ID、RepoDigests 和从 `/app/kestra` 复制出的实际 jar：
镜像 digest 为 `sha256:878be36bcb1a2d15266f6c7c05921f77d836136604bd956a5318671c2f34186c`，
宿主 executable SHA-256 为 `bc4c8645de85a2fe2fa395af5d7b13067c236ef276a33476662f68da49732ec7`。
下载插件 artifact 与保留部署 jar 的 SHA-256 均为
`914e655b1c8e0d3e25db5ece7981391532711cd381f248e03407438f2cef2748`。
下载 provenance 指向同一宿主镜像和 executable hash。宿主含 533 个 `ui/` 条目，插件
没有 UI 资源。overlay 从上述发布提交到当前 HEAD 的 `patches/`、`scripts/`、README 和
能力文档无差异；文档规则变更不能自动视作新镜像通过发布门。

下载的既有组合报告中，普通角色浏览器 199/199 通过，管理员 seed/enabled/disabled
各 78/78 通过；生产账号准备 XML 为 1 项、零失败、零错误、零跳过。下载插件 jar 无
宿主／Micronaut／Jackson 类，含 10 个 Micronaut 索引条目。这些检查不包含新管理功能。

overlay 失败日志列出 Micronaut Security、Nimbus、jbcrypt 及测试依赖找不到。这里仅确认
失败发生在依赖解析，未判定为代码缺陷或可安全重跑的问题；未修改依赖、未重触发 workflow。
后续构建 overlay 镜像前必须重新调查并通过发布门，不能复用本次红色 HEAD 作为编译凭证。

本地 ego-browser 实际从 `/ui/` 进入生产 OIDC 登录。浏览器填充凭据登录被拒绝；当前
bootstrap 配置未建立有效登录，本轮未进入管理参考页。因此未宣称当前管理页面视觉验收
通过。浏览器任务空间 7 已结束。后续必须在隔离 Compose 中准备有效测试账号，再观察
MCP Servers 列表与编辑页，完成主题、语言和窄屏比较。不得为调查重置用户部署密码。

源码调查使用 `.work/rbac-management-source`：从本地已有 Git 对象独立检出固定 BASE
`172dd0f3760fb1255d3b22bd1fd07ce31b173fa3`，逐个 `git apply --check` 后应用当前补丁。
该树仅作源码调查，未编译宿主，不是插件构建依赖。原有 `.work` 树未 reset 或清理。
下载的发布产物和报告保留于 `.work/rbac-management-evidence/`，不提交。

## 3. 机制证据表

表中上游源码路径相对于上述 BASE 加补丁树；插件路径相对于插件仓。源码定位与公开
契约分别列出，不能把 Java 的 `public` 方法或 UI workspace 包当作正式扩展契约。

| 用户行为 | 源码与公开文档定位 | 已发布产物与实际行为／可复现缺口 | 负责仓与判定 |
|---|---|---|---|
| 装载插件后端管理控制器 | overlay README 的 classpath 契约；`TypedRestRbacFilter.doFilter`；`OssRestOperationAdapter.resolve/administrative` | 发布 jar 有 typed SPI；匹配路由带 `tenant=main` 时未知控制器落 SYSTEM/EXECUTE；已有 CI 管理员门通过。尚未运行真实外部管理控制器探针 | 插件实现控制器；已有公开机制可用，须补宿主独立探针确认边界 |
| 从菜单进入角色页面，直接访问并刷新详情 | `ui/src/main.ts`、`utils/init.ts`、`routes/routes.ts`、`override/components/useLeftMenu.ts` | 路由和菜单为构建期表；`admin/iam` 指向 `components/demo/IAM.vue` 且菜单 locked。jar 无 RBAC 页面；无 classpath 管理页面注册机制 | overlay；需宿主扩展点，候选见 R-EE-RBAC 的管理页面提案 |
| 使用上游插件 UI | `ui/src/remoteComponents/useFederatedModule.ts`、`packages/slot-contracts/src/index.ts`；`PluginController.getPluginUiManifest/getPluginUi` | 现有联邦模块按 task type 和 plugin group 装载，仅 topology details/task drawer/task modal 三插槽；不是管理路由。公开 npm 的 slot-contracts 查询 404，源码标 private | overlay 复用装载思想；不得假装任务插槽已支持管理页 |
| 从独立 jar 提供静态资源 | `UiController.serve` 的 classloader `getResource("ui/" + path)`；overlay README 静态放行范围 | classpath 资源有读取路径，但未知路径回退 SPA HTML，HTTP 200 不能证明 JS 存在；没有名称隔离、资源清单和外部管理页发布契约 | overlay；需最小资源注册契约，不直接将内部 UiController 当发布 API |
| 使用宿主表单、表格和按钮 | `ui/packages/design-system/package.json`；公开 npm `@kestra-io/design-system@0.5.0` | 实际下载 tarball，含 dist、KsListingPage/KsDataTable/KsForm/KsButton 与 styleBase 等 exports。宿主 workspace 版本是 `0.0.0-dev`，不能从版本名证明二者一致 | 公开组件包已有机制可用；overlay 只需发布精确兼容清单及验证文档，缺少兼容项才补适配 |
| 跟随主题和切换语言 | `utils/init.ts` 的 i18n 初始化／additionalTranslations，`override/stores/misc.ts` 的 theme；REQ-0320 机制文档 | 内部初始化可合并翻译；design-system 有自身语言资源。独立登录翻译入口仅服务认证表单，无外部页面主题、应用翻译和偏好上下文契约 | overlay；需窄化的宿主 UI 上下文，不复制内部翻译源码 |
| 管理请求使用登录身份和 CSRF | `TypedRestRbacFilter` 读取 `SecurityFilter.AUTHENTICATION`；`filter/CsrfTokenFilter`；`UiIndexService`；README cookie/CSRF 契约 | `/api/.*` 的 cookie 写请求由宿主 CSRF 门处理，页面 meta 提供 token；已有组合检查。OAuth scope 不能作为管理角色；插件不能信任请求体 subject 作为调用人 | 插件复用已认证 Authentication 及现有 API 门；已有公开机制可用，部署不得放宽 `/api/**` |
| 列出、创建、停用登录账号或重置密码 | `oidc/services/OidcUserService`；`oidc/controllers/`；`r-oidc-server.md` 的 REQ-0140 历史待项 | 发布 jar 含内部服务，没有公开 UserDirectory SPI 或用户管理控制器；`create` 冲突后仍调用 setPassword，不能直接提升为公开创建语义。首装 API 完成后关闭，不可复用 | overlay；需宿主公开目录契约，候选在 OIDC 能力文档 |
| 修改角色后实际授权改变 | 插件 `RbacConfiguration`；`PolicyEngine` 构造器、effectiveRoles、authorizeTyped；两个 Configured authorizer 与 SafeFlowDependencies | 策略编译为 final Map/Set，三个消费入口持有同一引擎；没有仓储、管理控制器、写入或刷新入口。改配置需 Compose 更新容器，不能即时保存 | 插件业务缺失；已有 DI/JDBC 可用，不需宿主策略存储扩展点 |
| 重启后保留策略和升级表结构 | `core/migration/MigrationRunner` 注入 `Collection<MigrationScript>`；MigrationScript 文档 | 发布 executable 有 MigrationScript；F2 已有收集机制，插件尚无自身迁移。SQL 必须配 bean；独立构建与真实迁移仍待实施验证 | 插件；已有公开机制可用，宿主 README 应补稳定消费与唯一脚本 ID 约束 |

公开组件 tarball 的 npm integrity 为
`sha512-5KJ229xVkJs8tMSWDDxJbv1eB70oKlMP1GQ9KkeZhjyWyJKnPuxyRROwSuD4nxK1dvQFGIabjJldsgXdicl0mQ==`。
该证据只证明包可获取和包含公开入口，尚未进行独立安装、挂载与兼容验证。

## 4. 管理对象、页面与 API

| 对象 | 插件页面与拟定 API（均未实现） | 首期语义 |
|---|---|---|
| 登录账号 | 用户列表／详情；`/api/v1/{tenant}/extensions/rbac/users` | 插件控制器鉴权后消费宿主目录 SPI；分页、创建、资料更新、启停、密码重置，不读内部表 |
| 本地角色 | 角色列表／详情；同前缀 `/roles` | 名称、说明、grant 列表；角色 ID 稳定，名称与显示说明分开；被绑定角色不得直接删除 |
| 主体授权绑定 | 用户详情的授权页；同前缀 `/bindings` | 精确可信 subject → 本地角色；展示账号不存在或已停用状态，不推断为新身份 |
| 可信角色映射 | 授权管理页；同前缀 `/role-mappings` | 精确宿主角色值 → 本地角色，不把前端输入当可信身份声明 |
| 当前策略 | 同前缀 `/policy`、`/policy/validate`、`/policy/import` | 模式、revision、校验／迁移预览和显式导入；不提供任意 SQL 或脚本 |

页面路由建议放在 `/ui/main/extensions/rbac/` 下，使用宿主外壳中的用户、角色、授权三个
入口；不替换 EE `admin/iam`，不改变 edition，不把三个页面实现写入 overlay。
用户详情分别读取账号资料与插件授权，并明确两者保存边界。账号创建成功但绑定保存失败
时账号保持零授权，页面报告两步结果；不得伪造跨仓数据库事务。

首期只允许实例管理员。管理 API 仍在关闭管理功能或关闭 RBAC 时拒绝装载／调用，不能因
宿主业务 RBAC 门关闭而变成“所有已登录用户可管理”。管理可见性从插件 capability 查询
获得，服务端每次重新检查。匿名 401，已认证非管理员 403；不存在资源 404；名称／绑定
冲突 409；缺少 If-Match 428、版本不符 412；非法 grant 400；数据库故障 503，无写入。
正式实现必须为错误返回稳定 code，并将界面文案放入翻译目录，不回显密码或存储异常。

不得通过管理 API 新增实例管理员标志或编辑应急管理员。首期保留一个部署侧精确 subject
到受保护管理员角色的绑定，作为数据库模式不可编辑的授权锚点；该角色无普通 grant，
不参与普通角色并集。必须先核对并显式配置该 subject，不能默认相信客户端 `admin` 字符串。
数据库不可用时锚点也不绕过数据库故障门；恢复通过数据库备份和部署操作进行。
后续如需委派或可编辑实例管理员，必须另行设计权限升级边界和最后管理员防锁死事务。

## 5. 数据事实来源、并发与更新

候选配置 `kestra.rbac.policy-source: configuration|database`，默认 configuration。
候选管理开关默认关闭；开启时必须 database、统一 RBAC 和 typed 门均开启。

### 配置模式

沿用现有 roles、subjects、role-mapping、instance-admin-roles 校验和启动时不可变策略。
管理页面可以显示模式及只读配置来源，不提供保存按钮；写 API 拒绝。不能将 API 写入
仅存内存，不能通过管理页面修改 Compose 的 `KESTRA_CONFIGURATION`。

### 数据库模式

插件拥有 PostgreSQL 表：策略头（schemaVersion/revision）、版本化完整策略数据和最小
变更元数据（操作者 subject、时间、旧／新 revision）。最小元数据用于诊断和并发裁决，
不是完整审计产品。首次可使用完整规范化策略 JSON 加 revision，避免同时引入多套复杂
实体关系与同步协议；角色、绑定 API 都在同一完整策略事务内校验并提交。

1. 初始化必须显式导入配置快照；预览校验所有角色引用、scope、ID 约束、exact/subtree
   与管理员锚点，管理员确认后原子建立首个 revision。不在每次启动自动导入。
   数据库模式启用但无有效策略必须拒绝运行，不回退到 YAML。
2. 导入是迁移操作，由独立受控命令执行，消费同一策略校验器、连接配置和发布产物；在
   数据库模式启动前完成。禁止通过匿名 setup 或业务 API 绕过管理员校验进行首建。
3. 数据库模式出现旧 YAML 普通策略时启动报错并指明迁移方法。部署锚点使用独立配置，
   不与 YAML 普通 roles 混用。数据库已初始化后，重启不得覆盖数据或恢复已撤销绑定。
4. 每个授权决策先取得数据库已提交 revision；在一致读事务中读取对应完整策略，编译为
   不可变快照。可按 revision 缓存编译结果，但不得缓存“当前 revision”并靠通知保证撤权。
   新决策在提交后必须读到新 revision。数据库读取失败则拒绝，不能用旧缓存继续允许。
5. REST、typed、MCP、查询 scope 和 SafeFlowDependencies 的管理员判断统一使用策略
   提供器，禁止其中一个仍持有启动期 PolicyEngine。runtime execution gate 再取当前策略。
   一次决策只使用一个完整 revision；已经通过检查的在途副作用不宣称可追溯取消。
6. 管理写操作在事务中锁定策略头，按锁内当前 revision 重新验证调用人管理权，再比较
   If-Match 并编译候选策略，最后写入完整新 revision。不能用请求入口处的旧管理员检查
   覆盖撤权后的并发写。候选校验／数据库失败全部回滚；成功响应只在 commit 后返回。
7. 账户目录状态和策略库是两个事实来源。停用账号由宿主使会话／token 失效；撤销绑定由
   插件使后续授权拒绝。账号停用不删绑定，恢复账号的授权效果必须在确认页明确展示。
8. 多实例共用同一 PostgreSQL，依赖数据库锁和 revision，不依赖单 JVM 事件。首个交付
   只声明 PostgreSQL，必须验证两个 JVM 撤权一致性；通知只能优化界面刷新。

读取 revision 增加数据库负载，但省去广播丢失、失效窗口和双事实来源；首期优先保证撤权
正确性。性能门测量真实授权频率与延迟；有证据后再设计优化，不能先引入容许旧授权的 TTL。

## 6. 阶段与发布依赖

| 阶段 | 实施范围与负责仓 | 进入下一阶段的门 |
|---|---|---|
| A：契约定稿 | overlay 收窄页面装载和账号目录候选提案，确认 design-system 精确版本及主题／语言桥接；插件确定策略模式与迁移 schema | 文档接收、失败探针和兼容矩阵明确；现有 CI 依赖解析失败已归因；候选仍不可作为编译输入 |
| B：宿主机制发布 | overlay 实现通用 UI 注册／上下文和目录 SPI；补外部控制器管理员／CSRF 独立探针，公开迁移使用说明 | 宿主独立 CI 全通过，发布 digest 与兼容元数据；探针不得读取真实 RBAC 源码 |
| C：策略与角色授权管理 | 插件从发布镜像取得依赖，先实现数据源、迁移、revision、管理守卫、角色／绑定 API 和页面 | 独立克隆 README 构建与 CI 通过；jar 含 bean、SQL、静态资源且无宿主类；发布 artifact |
| D：账号管理 | 插件消费已发布目录 SPI，提供用户页面与账号 API；复用阶段 C 的授权绑定 | 创建、重复账号、资料更新、停用、密码重置及会话撤销独立验证；无内部服务／表依赖 |
| E：组合验收 | 根仓选择发布 digest 与 jar hash，隔离 Compose + 真实浏览器；如 C 单独部署，明确标为角色授权管理阶段 | 下载最终提交成功报告并核对矩阵、零跳过、hash、权限及副作用；才可声明完整管理交付 |

可以先交付阶段 C，但不能将“为已知 subject 编辑绑定”描述为登录账号管理。
本轮没有实现 API、创建业务表、编译插件 jar、构建镜像或变更用户部署。

## 7. 独立验证与组合验收条件

宿主独立探针必须验证：classpath 测试扩展注册页面／菜单／资源、普通用户不能访问管理
API、管理员允许、缺 bean／重复 ID／未知版本拒绝、cookie 写入 CSRF、资源 MIME／缓存、
直接访问与刷新、扩展故障不破坏原页面。目录探针必须覆盖原子创建与冲突不改密码、
乐观锁、停用／重置后既有 UI、IdP、access/refresh token 与 MCP session 的失效边界。

插件必须验证：配置模式回归、导入幂等与重启不覆盖、非法引用回滚、并发更新一胜一冲突、
事务内再授权、数据库故障拒绝、两 JVM 撤权、所有策略消费入口版本一致、缺／多 bean、
旧 schema 拒绝与备份恢复。不得通过插件 CI checkout overlay 源码完成上述检查。

根仓实际用户矩阵至少包含：实例管理员、单角色 reader/writer、多角色 A 写/B 读、exact
namespace、无角色映射且无 subject 绑定的零授权账号。管理员通过真实 UI 修改明确字段，
核对保存记录与 revision；普通用户直接请求同一 API 拒绝，比较账号、策略和队列副作用。
创建账号后实际登录，先验证零授权，再绑定并验证 A 写/B 读/B 写拒绝/范围外拒绝；撤销
绑定后用既有会话同时验证 REST、列表 total/aggregate 和 MCP 后续调用已拒绝。

允许保存后无需重启即影响新请求；重启 JVM 后仍保留。并发保存、失联数据库、无 jar、
管理关闭、RBAC 关闭、坏 CSRF、跨源和未知路径均有确定拒绝行为。目录停用或重置后
旧会话不得仍能进入业务；绑定撤销不得靠退出登录才生效。拒绝必须核对延迟副作用。

UI 必须对照实际发布宿主 MCP Servers 列表／编辑页面，检查正常、空数据、加载、拒绝、
错误、未保存离开、保存冲突及成功状态。覆盖全部宿主语言、light/dark/dark-2/跟随系统、
390 像素窄屏、键盘焦点、翻译实际内容、直接访问、刷新和退出换号。本地与 CI 复用同一
矩阵；静态资源可读不授予数据权限。本轮没有执行这些新功能验收，不将调查计为通过。

## 8. 复核入口

```bash
git status --short
git submodule status
git ls-remote origin refs/heads/main
gh run view 38695537351 -R ai-specs/kestra-overlay --log-failed
gh run view 38686689513 -R ai-specs/kestra-overlay
gh run view 38695540439 -R ai-specs/kestra-plugin-rbac
gh run download 38695540439 -R ai-specs/kestra-plugin-rbac --name kestra-rbac-authorizer --dir <空目录>
gh run download 38688730525 -R ai-specs/kestra-monorepo --name compose-acceptance-38688730525-1 --dir <空目录>
npm view @kestra-io/design-system@0.5.0 --registry=https://registry.npmjs.org version exports dist.integrity --json
npm pack @kestra-io/design-system@0.5.0 --registry=https://registry.npmjs.org --pack-destination <证据目录>
```

本项目提出的宿主候选需求分别由 overlay 仓 `docs/r-ee-rbac.md` 的管理页面提案和
`docs/r-oidc-server.md` 的账号目录提案承载。两者自包含，不要求宿主构建读取
本插件文档；差异表和机制文档同步标记候选、未接收、未发布。
