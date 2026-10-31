# 插件独立 Chromium 页面门

此门测试本仓实际生产构建 `entry.js` 的 `mount()`，由真实 `App.vue` 打开
`EditorDrawer.vue`，使用固定发布镜像提取的 SDK 和 npm lock 固定的原生组件。
测试参照只额外装载同一发布包的原生 `KsDrawer`。不替换组件、DOM 测量、事件、
样式或 clipboard；复制失败由 Chromium 原生权限拒绝触发。

测试页显式标识 in-memory management API / SDK context transport。它不实现生产
认证、授权或持久化。正常、空数据、加载、异常、拒绝和重新认证是页面呈现输入；
生产 CRUD、CSRF、权限、副作用和跨仓流程由部署仓验收。通用认证页面归宿主。
此门不启动宿主服务、PG 或 Compose，不读取相邻仓源码。

运行（需已通过 prepare-host 提取正式固定镜像，Node 24 / Python 3 / JDK 25）：

```sh
./gradlew check --console=plain
cd ui
npm ci --ignore-scripts
npx --no-install playwright install chromium
npm run test:browser
node browser/check-report.mjs
```

CI 安装 Chromium 时使用 `--with-deps`。Playwright 及浏览器 revision 由
`@playwright/test` 精确版本与 lock 固定。server 只监听 127.0.0.1:4179，归
Playwright webServer 管理并在执行结束关闭；不复用外部 server。

矩阵定义唯一入口为 `matrix.mjs`：4 主题（light/dark/dark-2/system）×
2 语言（en/zh_CN）× 3 宽度（1280/768/390）× 30 行为 = **720**。
每个 ID 为 `plugin-ui/{theme}/{locale}/{width}/{behavior}`，顺序为 theme、locale、
width、behavior 的嵌套展开。其他 11 语言沿用公开 SDK 的 key、非空与占位完整性门。
`system` 由 transport 按原生 matchMedia 解析为 SDK 接受的主题，不新增偏好存储。

四类抽屉覆盖实际计算样式、header SVG、body/footer 边距、保存按钮与图标、
表单关联、键盘焦点循环、Esc/关闭/重开、草稿拒绝导航、实际浏览器 history、
有暴露区域时的原生 backdrop、最大化、保存期间加载与重复提交锁。
窄屏抽屉占满屏幕时没有可点击 backdrop，使用其可见关闭按钮验证相同草稿保护。
其他行为覆盖卡片计数间隔与纵向标题、列表与权限摘要展开、原生筛选 popup 几何、
内部按钮/重开/滚动、正常/空/加载/错误重试/拒绝/重新认证、编辑保存、inline discard、
原生 clipboard 拒绝后重试，以及主题切换、系统偏好和刷新。

正式 artifact 名称仍为 `kestra-rbac-authorizer`，入口：

- `.evidence/ui-browser/report.json`：逐项状态、计数、输入、最终输入与 runner errors。
- `.evidence/ui-browser/manifest.json`：精确 720 行对象及 ID 顺序。
- `.evidence/ui-browser/inputs.json`：运行开始 provenance 及逐文件 hash。
- `.evidence/ui-browser/jar-bundle.json`：真实 jar 和受测生产 bundle 的逐文件一致检查。
- `.evidence/ui-browser/html/index.html`：人工审阅报告。
- `.evidence/ui-browser/results/`、`attachments/`：失败截图、trace、样式对照与呈现截图。

`report.json` schemaVersion=1、gate=`plugin-ui-chromium`。
`inputs` 和 `finalInputs` 的以下七字段均为字符串：
`image`（完整 GHCR digest 引用）、`sourceCommit`（40 位 git HEAD），以及
`hostExecutableSha256/sourceSha256/lockSha256/bundleSha256/sdkSha256`（64 位小写 hex）。
所有聚合使用相对路径升序，行内容为 `path + NUL + fileSha256`，用 LF 连接且无尾 LF，
再取 SHA-256；source 路径相对 `ui/src/`，SDK 相对 `.integration/ui-sdk/`，
bundle 相对 jar 中 `ui-extensions/rbac/`（排除 `manifest.json`）。
源码未提交时 HEAD 仍为原提交，必须同时消费 sourceSha256，不能把本地结果当已发布结果。

每行含 `id/theme/locale/width/behavior/status/durationMs/errors/attachments`。
status 仅 `passed/failed/notRun/skipped`，summary 含
`planned/executed/passed/failed/notRun/skipped`；executed=passed+failed。
仅全部 720 通过、无 runner errors、开始和结束输入相同才可 gatePassed=true。
筛选单项执行仅供排查，其余明确 notRun，不能通过正式门。
未知 console warning/error、page error、request failure、HTTP >=400 均使对应项失败；
内存 transport 的拒绝状态不会生成真实 HTTP 失败或被网络 allowlist 忽略。

root 应消费匹配正式源码提交的成功发布 artifact，核对 manifest 完整顺序、全部
720 行 passed、summary 精确计数、errors=[]、七个输入与 finalInputs 全等，
并自行从正式 jar 重新计算 bundleSha256。HTML、SDK 自测或 Happy DOM 不替代此报告。
本地报告不证明发布、宿主装载或生产授权通过。

## 0384：精确 popup 实例回归

旧正式 SDK 存在已接收的 popup reference 归属缺陷：完成原生进入后，
再次点击桌面筛选 trigger 会在 SDK mouseup outside 关闭后被原生 click 重开。
旧输入下 `1280/filter-popup-buttons-reopen-scroll` 必须保持失败，不得标为 skip、
使用 expected-failure、换关闭按钮、放宽断言或靠过渡尚未完成取得绿灯。
该失败报告不能作为发布成功或 root 的 720 成功输入。

桌面筛选通过 trigger 的 `aria-describedby` 定位唯一 popup id，检查它处于
插件自己的 portal 且包含原生 `.filters-panel`；同一 id 贯穿关闭和重开，
不再用泛 `.kel-popover:visible` 的最后一个匹配项代表它。
下一步动作前，轮询该实例原生 enter/leave class 消失、原生动画结束，
不使用固定 sleep、不提高 timeout。滚轮后验证真实文档 scrollY 改变，
关闭后仍断言此精确实例隐藏和 trigger 解除关联，然后才允许再次打开。
每项附加 `filter-owned-instance-states`，包含 id、关联、过渡、动画、
滚动及几何快照，可区分误选、关闭后重开和真正关闭。

旧正式 SDK 的 16/16 稳定红例及失败 CI 证据保留在本仓
`.work/layer-plugin-publish-20261031-223634-dioctspf/`，不覆盖或改写为成功。
当前消费已完成发布的宿主 CI `41224044004` 对应固定镜像（见本仓
`host-contract.properties`），通过 `prepare-host.sh` 提取正式 SDK 后执行完整 720 项。
本轮本地证据使用 `.work/plugin-final-ui-20261101/`；本地通过仍不表示插件 CI 或发布通过。
