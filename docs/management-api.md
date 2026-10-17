# 管理接口实施契约

状态：实施输入，尚未发布。本文由本项目持有，供管理控制器、前端和独立测试共同消费；
宿主机制仍须先通过自己的正式发布门。管理功能默认关闭，所有业务接口都要求当前管理
身份。数据库策略不会授予管理身份。首期配置模式只读，数据库模式才允许管理写入。

根路径为 `/api/v1/main/extensions/rbac`。前端必须从宿主context.apiBase拼接后缀，不能
写死部署前缀。JSON请求严格校验字段、枚举和引用，不支持通过header或请求体指定调用人。
API错误形如`{"code":"revision_conflict"}`，宿主认证／CSRF错误仍使用宿主响应格式。
前端不得展示SQL、密码或未处理异常内容。

## 发现与策略

- GET `/capabilities`：非管理人仅返回`{"allowed":false}`；管理人返回allowed、subject、
  mode（configuration/database）、readOnly与userDirectory。资源可读和菜单可见不代替授权。
- GET `/policy`：返回`{mode,revision,readOnly,policy,protectedSubjects}`。policy为schemaVersion1
  规范策略，roles元素为`{id,name,description,grants}`；subjects与roleMapping是精确身份到
  角色ID数组的映射。配置模式revision为0，只读。数据库模式revision为正数。
- policy响应含ETag；策略写入必须使用`If-Match: "<revision>"`。缺失428，过时412；配置
  模式写入409 policy_read_only。接口不提供HTTP首次导入或任意策略SQL。

## 角色与授权

- POST `/roles`：完整`{id,name,description,grants}`，新增角色ID稳定。
- PUT `/roles/{id}`：完整替换角色，body.id必须与路径一致，不改ID。
- DELETE `/roles/{id}`：被subject或roleMapping引用时409，不能静默删除绑定。
- PUT `/bindings`：`{subject,roles:[id,...]}`，roles为空则移除该精确subject绑定。
  首期本地账号绑定必须引用目录中已有身份；不存在时404。创建账号时若发现同名预存绑定，
  返回409而不创建账号，避免未知身份绑定被新账号继承。离线初始化不会自动创建账号。
- PUT `/role-mappings`：`{trustedRole,roles:[id,...]}`，roles为空则移除映射。
- 上述写入均返回新的完整policy响应，前端更新revision后再允许后续保存。

grant字段为scope（NAMESPACE/TENANT）、tenant（main）、namespace、descendants、resource、
actions、ids、allIds。scope=NAMESPACE时必须提供namespace；TENANT不含namespace和子域。
资源与动作使用已发布枚举的原始标识符，界面说明及字段标签翻译。普通数据库策略拒绝
SYSTEM、INSTANCE和管理员配置；ids与allIds互斥，不支持通配符。UI提供逐条权限表单，
不得用整段JSON编辑器作为用户的主要授权操作流程。

## 登录账号

- GET `/users?page=1&size=20`：`{items:[{subject,name,email,active,revision}],total,page,size}`。
- GET `/users/detail?subject=<精确subject>`：返回单个账号，供详情直接访问和刷新，不依赖
  当前分页列表已包含该账号。
- POST `/users`：`{username,name,email,password}`，返回创建后的账号。新账号无角色与绑定。
  不得创建部署管理员名单中的 subject；部署管理员账号由部署方单独准备。
- PUT `/users/profile`：`{subject,name,email}`，保存明确资料字段。
- PUT `/users/state`：`{subject,active}`。
- PUT `/users/password`：`{subject,password}`。
- 账号更新使用该账号的`If-Match: "<revision>"`，初始revision可以为0，不能使用策略版本。
  这些写入返回更新后的账号；不存在404，冲突412，非法字段400，目录不可用503。
- 受保护部署管理员不得停用或重置，返回403 protected_subject；subject不可改名、没有物理
  删除接口。显示名／邮箱更新不改变身份。资料保存与授权绑定分别提交，分别报告结果。

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
翻转；搜索确实改变角色、用户和绑定列表，清空恢复。账号筛选必须覆盖全部分页，而非只
过滤当前页；筛选后的总数和分页必须对应可见结果。刷新、切换页面、空结果及窄屏同时检查。
条件编辑、关闭按钮、清空、Contains／Equals、组合 AND／OR 和页大小切换必须实际操作。
计算样式之外，必须保留同视口截图并检查布局、工具栏、留白和 tab 相对宿主导航的位置。

页面根内路径为roles、roles/new、roles/{id}、users、users/new、users/{encodedSubject}、
bindings。subject的路径片段使用UTF-8的base64url，避免路径穿越与百分号歧义；实际API
操作的subject在JSON体中或列表结果中使用原值，不把角色显示名当身份。

页面采用发布SDK和公开组件包的兼容入口；基础输入、按钮、表格优先复用。原生选择框、
复选框与页内确认采用同一主题语义；详情走路由，不使用未经验证的vendor弹出层。
宿主负责页面外壳和菜单，插件只挂载自己的ShadowRoot内容，所有样式由SDK工具准备后
放入context.styles，不向document写CSS。全部文案来自ui/src/translations，支持发布
SDK的全部语言、主题与窄屏。请求使用context.request，处理401/403、过时版本、网络
异常和加载状态；保存期间禁止重复提交，保存冲突提示重新加载，不覆盖他人修改。
