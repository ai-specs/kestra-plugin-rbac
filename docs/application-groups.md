# 应用组与 Kestra 权限实施设计

状态：目标设计与部分候选实现。根仓 `docs/application-group-target-model.md` 与维护者 2026-10-20 授权裁决是目标。宿主 `036145814a0abf9eed6c43953bc75e213b879585` 的 CI `39453574911` 通过并发布镜像 digest `sha256:f42f4bc98b419e59f165287ea460f0f9900a33ae76562784e9bbe2d5d6e11881`，但它只提供部分新契约。本插件工作树已加入声明存储、提供者、API 和部分卡片 UI；独立插件 CI、产物发布及根仓新模型组合验收尚待核对。旧项目路径、意图上界和完整恢复仍在源码中，不能声称目标模型已交付。

## 职责和事实来源

应用组是用户、应用和 scope 标识的组织范围。宿主 OIDC 目录维护内置应用组、scope `key/name/description`、应用成员及其已登记的支持 scope key、用户授予；宿主签发经验证的应用组 claim。插件通过已发布的公开目录 SPI 读写，不访问宿主目录表。

插件维护两份明确分离的数据。`application_scope_declaration` 是应用主动登记的 `应用 × scope key × 权限 key/name` 元数据副本；它驱动授权页展示，并参与该应用可申请 scope 的范围检查，但不驱动 Kestra 资源授权。`kestra_application_policy` 是 Kestra 应用对 scope 的完整资源与动作策略，属于 Kestra 内部运行时权限事实来源。其他应用的代码自行执行其资源授权，不读取 Kestra 策略。

宿主成员支持范围与插件声明元数据必须同时存在，才算应用有效支持该 scope。创建支持关系时，先登记宿主成员支持范围，再保存插件声明；任何中间失败使签发端拒绝。撤销时，先停用插件声明，宿主签发和可信身份复查立即拒绝，再撤销宿主成员支持范围。插件必须记录并显示部分成功以便精确重试；不得把双存储写入称作原子事务。宿主删除 scope 在自身事务中检查所有成员均已撤销支持且用户授予均已撤销。并发登记与删除由宿主目录同一组写锁串行化。

宿主提供版本化、只读的应用声明投影 SPI，用于授权页显示和签发／refresh／已签发凭据在线复查。SPI 按应用组 ID 与 client ID 返回声明版本、支持的 scope key、每项权限的 key/name；组件缺失、多个实例、版本不符、读取失败或与宿主成员登记不一致均拒绝。宿主不能根据权限 `name` 推导角色或 Kestra 资源动作。只有 RBAC 应用组功能开启时才要求该 SPI；关闭功能的标准 OIDC 登录保持原有路径。

## 管理与安全

管理 API 路径统一为 `/api/v1/{tenant}/extensions/rbac/application-groups`。集合只读；详情读取需要对应 READ，写入需要 MANAGE；scope、应用登记、声明元数据、用户授予分别核对实际目标。所有写入需要宿主 CSRF、强 `If-Match` 与当前可信身份。内置应用组 ID 由安装状态固定，不提供可成功删除或改 ID 的路径。scope key 使用 `[a-z][a-z0-9-]{0,63}`，创建后不可改；name 必填且同组唯一，可独立编辑；description 可空且不参与授权。权限 key 在应用内唯一且稳定，name 必填、可编辑。

应用声明 API 按应用和 scope 列出权限 key/name，提供新增、详情、修改名称、删除。删除最后一项声明后，该应用停止支持该 scope；删除声明不自动改应用代码或 Kestra 实际策略。Kestra 实际权限另设明确页面和 API；页面不能把声明元数据的保存解释为实际策略保存。用户 scope 授予只改宿主目录，不自动增加应用支持或资源权限。

运行时同一请求固定当前可信身份和一份 Kestra 策略快照。同一资源动作必须由同一 scope 的一条完整 grant 覆盖；列表按 scope 内 READ/LIST 求交，再对 scope 求并，不跨 scope 拼接范围和动作。未知、已停用、已撤销、应用不再支持的 scope 混入 token 时整体拒绝。声明元数据改变本身不改变 Kestra 实际 grant；应用支持范围改变和用户撤权必须阻止旧 cookie、access token、refresh token 和 MCP session 继续获得权限。

## 页面和发布门

应用组集合使用卡片；卡片进入只读详情，点击显式“编辑”才可修改。概览不显示全组授权配置状态。scope 页管理 key/name/description；应用页管理支持范围和权限声明 key/name；用户页管理 scope 授予；Kestra 实际权限页只展示 Kestra 策略。旧“身份目录”“本地绑定”“完整应用组恢复”不出现在用户文案和路径。主题、语言、窄屏和键盘焦点按已发布宿主 UI 契约复验。

必须先取得宿主正式镜像与公开 SPI，随后独立编译插件 jar、通过插件 CI 并发布。根仓使用正式镜像与 jar 运行 `new-install` REST、MCP、安全、撤权、refresh、无副作用、UI、缺插件与关闭功能矩阵；最终根仓 CI 报告必须对应最终提交。schema1 与物理回滚只进入显式 `legacy-migration` profile，未运行不计通过。
