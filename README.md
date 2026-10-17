# kestra-plugin-rbac

Independent, database-backed authorization extension for the Kestra OSS overlay. The business policy lives in this repository. Output: **`build/libs/kestra-rbac-authorizer.jar`**. The jar is thin, includes Micronaut generated bean indexes, and contains no Kestra, Micronaut, Jackson or other dependency classes.

The earlier runtime authorization implementation passed independent CI and real monorepo Compose
acceptance; that historical result does not validate the instance-admin change. See [validation evidence](docs/validation.md) for the tested scope. Build dependencies
are extracted from the immutable published image, independently of any overlay source checkout.

## 管理功能方案

用户、角色和授权管理功能的业务调查与阶段方案见
[RBAC 管理功能方案](docs/rbac-management-plan.md)。该方案属于本插件，相关宿主机制
需求向 overlay 独立提案；管理 API、页面和数据库策略正在实施，尚未完成发布与组合验收。

策略存储、运行时快照、管理控制器与页面已加入源码，新宿主契约与插件仍须分别通过
正式发布门，再执行组合验收。`examples/management-policy.json` 展示 schemaVersion 1 的输入，
不包含账号或管理员授权。初始化工具只允许在空策略库创建首个版本，重复相同输入幂等，
已有不同策略拒绝；管理身份与普通数据库策略分开配置。

工具入口为 `io.kestra.extensions.rbac.store.PolicyImportCommand`，运行 classpath 必须是
兼容的已发布宿主 executable 与独立插件 jar，不能使用相邻源码或开发树。`validate`
仅检查 JSON；`initialize` 使用 `RBAC_POLICY_JDBC_URL`、`RBAC_POLICY_JDBC_USER`、
`RBAC_POLICY_JDBC_PASSWORD` 和 `RBAC_POLICY_OPERATOR_SUBJECT`。正式版本完整使用命令
须在管理契约发布后核对，这里不把未发布入口作为部署要求。

独立存储测试默认使用 H2；设置 `RBAC_STORE_TEST_JDBC` 可对隔离 PostgreSQL 执行同一
套检查。该变量仅用于测试，账号为 postgres、密码为公开测试常量 policy-test-only，
每个用例使用独立 schema；不得指向生产数据库。CI 运行 H2 完整检查和 PostgreSQL
存储矩阵，要求存储测试无失败、无跳过，不执行其他仓库的构建或部署。

## Build independently

Requirements: Docker, JDK 25, Node.js 24 (including npm), Python 3 and read access to the private GHCR host image.
Authenticate to GHCR with a credential that has `read:packages` access, then run:

```bash
./scripts/prepare-host.sh
./gradlew clean check --console=plain
```

`prepare-host.sh` pulls the immutable image in `host-contract.properties`, extracts
`/app/kestra` without starting a server, checks the public legacy and typed SPI classes, and writes
ignored local compilation dependencies and provenance. It is safe to rerun. No overlay
checkout, upstream clone, patch replay or Kestra build is required.

The host jar is a compile/test dependency only; it is never included in the extension jar.
Unit tests remove host bean indexes from temporary test copies so extension bean discovery
can be tested without booting Kestra. Real deployment discovery is verified by monorepo
Compose acceptance. CI uses the same published-image extraction and build commands. The host package grants
this repository Read access under Manage Actions access, so CI uses its own GITHUB_TOKEN
with packages:read; no personal token or cross-repository source deploy key is required.

构建同时执行独立前端的 `npm ci --ignore-scripts`、翻译完整性检查、ESM 与 CSS 构建及
管理资源清单生成。Java jar 包含插件自有前端资源，不包含宿主源码或框架 Java 类。
前端框架来自固定 npm 依赖；UI SDK 从同一个已发布宿主镜像的 executable 提取，写入
被忽略的 `.integration/ui-sdk`。SDK 缺失时构建失败，不从相邻仓补文件。

## 数据库管理模式

以下是管理版本的配置与实施契约。正式使用前必须选择已通过完整发布门的兼容宿主与
插件，并完成部署仓组合验收。不得仅根据源码或单元测试启用生产管理功能。

```yaml
kestra:
  rbac:
    enabled: true
    typed:
      enabled: true
    evaluation:
      enabled: true
    management:
      enabled: true
    policy-source: database
    management-admin-subjects: [your-exact-authenticated-subject]
```

数据库模式必须完整移除原配置中的 `roles`、`subjects`、`role-mapping` 和
`instance-admin-roles`。本插件拒绝把配置策略与数据库策略混合。部署管理员由精确的
已认证 subject 列表定义；普通数据库角色、可信角色映射和 OAuth scope 均不能赋予管理权。
部署管理员具有完整实例权限，名单必须由部署负责人维护。管理账号不能通过页面停用或
重置密码；资料修改不改变其身份。

首次启用前，使用兼容已发布宿主和插件的 classpath 初始化策略。连接变量必须由操作者
提供，并指向部署 PostgreSQL；不要在命令历史写入密码：

```bash
export RBAC_POLICY_JDBC_URL RBAC_POLICY_JDBC_USER RBAC_POLICY_JDBC_PASSWORD
export RBAC_POLICY_OPERATOR_SUBJECT
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.PolicyImportCommand validate examples/management-policy.json
java -cp '.integration/host-webserver.jar:build/libs/kestra-rbac-authorizer.jar' \
  io.kestra.extensions.rbac.store.PolicyImportCommand initialize examples/management-policy.json
```

初始化只允许空库首建，重复相同内容幂等，已有不同内容拒绝。它不创建登录账号，不
授予管理身份，不覆盖已有策略。策略表使用宿主配置的同一 PostgreSQL 数据源，保留当前
版本及最多 100 个历史版本。运行时每个授权评估读取当前数据库版本，并固定不可变快照；
评估不持有连接或事务。新请求读取新版本，数据库不可用时拒绝，不复用旧允许结果。
这不是任务 worker 的权限沙箱，已经提交到队列的任务不承诺因后续改权而撤回。

管理页面入口为宿主 Extensions（扩展）分组中的 RBAC 管理页；Resources 下的 Plugins
是任务插件目录，不列出 RBAC 管理页面。根路径为
`/ui/main/extensions/rbac`。角色使用稳定 ID；授权通过逐条权限表单配置。用户创建后
没有普通授权，必须另行绑定角色。启停与密码重置会使旧认证会话和 token 失效；重新启用
账号不会恢复旧会话。资料修改、角色绑定与密码修改分别保存。

管理 API 要求宿主认证、CSRF 与当前管理权限。策略写入使用全局策略 revision，账号
更新使用该账号 revision；过时版本返回 412，页面要求重新加载，不覆盖并发修改。
删除已被引用的角色返回冲突。完整接口和错误语义见
[管理接口实施契约](docs/management-api.md)。策略来源固定为 database，管理开关
默认关闭；不再提供 configuration 模式。配置为 configuration 时启动失败，数据库不可用时拒绝请求。

## Configure and deploy

Consume an independently accepted overlay image whose published manifest classpath includes `libs/kestra-rbac-authorizer.jar` and whose unified SPI/execution gates match the contract pin. Mount the jar **read-only at `/app/libs/kestra-rbac-authorizer.jar`**. `/app/plugins` is for Kestra task/trigger plugins and does not discover this extension's web beans. Restart the container JVM after replacing the jar. Apply policy configuration changes with Compose to update the container.

部署只接受 `policy-source: database`。普通角色、subject 绑定与可信身份角色映射均保存在
数据库策略 JSON 中，schemaVersion 为 1，使用 `roles`、`subjects` 和 `roleMapping` 字段。
使用 [examples/management-policy.json](examples/management-policy.json) 初始化空库，之后通过
管理页面修改。历史 YAML 角色配置不再作为运行配置接受；遗留 roles、subjects、role-mapping
或 instance-admin-roles 会导致启用 RBAC 时启动失败。不得静默忽略或合并两套策略。

管理员只能通过 `management-admin-subjects` 指定精确已认证 subject。可信角色名 admin、
OAuth scope、client ID 或同名普通角色不能升级为管理员。数据库角色禁止 SYSTEM／INSTANCE
管理授权。多个普通角色权限按原有资源范围求并集；READ 与 LIST 可以来自不同角色，查询域
为两者范围的交集。缺少组件、无身份、未知操作、数据库错误都失败关闭。

CI 的角色矩阵通过公开初始化工具写入隔离数据库，不编译或读取相邻宿主源码，不修改生产
账号。配置关闭 RBAC 时保留宿主默认行为；这不是另一种策略来源。

## Input/output dependency policy

`SafeFlowDependencies` implements the host `McpAuthorizationResolver` consumed by both actual execution gates. It inspects the **runtime ProcessedFlow declaration**, not an uploaded/latest raw flow. Runtime `tenantId` may be absent/null or equal `main`; another tenant denies. Inputs, variables, checks and labels allow literals only; output expressions allow only an entire `{{ outputs.task.field }}` reference (plain dotted names), or literals. Secret/KV/namespace/file functions, filters, dynamic indexing, interpolation, unknown expression syntax and malformed YAML deny before execution. A safe declaration returns an explicit empty extra-read plan. This policy does not sandbox tasks or reinterpret their later engine privileges.

## Controlled fixture and acceptance

`OidcRbacFixtureTest` consumes production `OidcUserService` and `OIDC_FIXTURE_JDBC` against the isolated test database. `OIDC_FIXTURE_USER`/`OIDC_FIXTURE_PASSWORD` override local defaults `kestra`/`local-ci-only`. It creates `rbac-reader` / `ReaderPass123!` / `[reader]`, `rbac-writer` / `WriterPass123!` / `[writer]`, `rbac-multi` / `MultiPass123!` / `[multi-reader, multi-writer]`, and `rbac-blocked` / `BlockedPass123!` / `[blocked]`. These passwords are local acceptance constants only. The multi-role fixture deliberately combines roles from different namespace domains so read and write scope merging is observable. It validates credentials and verifies stored roles; existing conflicting users fail rather than silently supplying the wrong fixture. Without JDBC the test is skipped; the monorepo integration harness must assert it ran and did not skip.

The monorepo consumes fixed image digest, separately built jar/hash, and matching host artifact through Compose. Required remaining evidence includes real jar startup discovery, actual authenticated roles parity, scope/RBAC double denials with zero persistence/queue effects, namespace and query/count isolation, 403 rendering, CSRF order, missing jar, async/composite coverage and disabled deployment regression. Unit test green does not replace those gates. There is no IAM CRUD/UI, audit, EE token service or custom authentication in this extension.

Build/test results, artifact hash and image acceptance are recorded in [validation evidence](docs/validation.md).

For a runtime explanation of the complete authentication and authorization path, see
[OAuth2/OIDC 到 RBAC 的运行机制](docs/authorization-flow.md). That document explains the
execution model and boundaries; deployment and configuration instructions remain in this README.

## Obtain the built jar

Successful [build runs](https://github.com/ai-specs/kestra-plugin-rbac/actions/workflows/build.yml)
publish a `kestra-rbac-authorizer` artifact containing the jar, host provenance and test reports.
Choose a successful run matching the desired host image contract, then download:

```bash
gh run download -R ai-specs/kestra-plugin-rbac <run-id> \
  --name kestra-rbac-authorizer --dir /tmp/kestra-rbac-artifact
```

When only replacing a jar in the deployment bind mount, run `docker compose restart kestra`
to restart the container JVM and load it. This does not compile Kestra or rebuild the host image.
Use `docker compose up -d` for the initial deployment or configuration changes.
