# kestra-plugin-rbac

Independent, configuration-driven authorization extension for the Kestra OSS overlay. The business policy lives in this repository. Output: **`build/libs/kestra-rbac-authorizer.jar`**. The jar is thin, includes Micronaut generated bean indexes, and contains no Kestra, Micronaut, Jackson or other dependency classes.

Current status: implementation frozen; normal pinned local build/tests pass against overlay source contract `92745d02f05fac77ebb94ecdaf7cf7e998543cca`. **No release or complete image end-to-end acceptance is claimed.** Overlay release CI, independent plugin CI and deployed-image combination acceptance are separate gates. Successful source compilation does not establish image readiness or compatibility.

## Build independently

Requirements: JDK 25, Python 3, Git, Bash, network access to Maven Central/Gradle/upstream, and read access to `ai-specs/kestra-overlay`. No cloned monorepo paths are part of the release contract.

```bash
# Check out the host repository separately. The script reads the pinned commit without modifying it.
./scripts/prepare-host.sh /path/to/overlay-checkout
./gradlew clean check --console=plain
```

`prepare-host.sh` reconstructs upstream v2.0.5 at the fixed SHA, applies the fixed overlay patches, builds **thin webserver/core/model module jars**, and writes `.integration/classpath.properties` plus host provenance. Upstream is fetched with `--depth 1 --branch v2.0.5`, without partial/blob filtering. Existing `.integration` directories must be moved aside before rerunning. An optional second argument is a local upstream Git object cache. CI uses `actions/checkout` for the pinned private overlay ref with **`secrets.OVERLAY_DEPLOY_KEY`**, a dedicated read-only deploy key; no user PAT is required. Deployment and combination acceptance belong to the monorepo, not either project's independent CI.

To consume published matching host artifacts instead of replaying sources:

```bash
./gradlew -PkestraWebserverJar=/artifacts/webserver-2.0.5.jar \
  -PkestraHostModules=/artifacts/core-2.0.5.jar,/artifacts/model-2.0.5.jar clean check
```

A matching host fat jar may be supplied explicitly for the production-service fixture. All host artifacts remain compile/test dependencies only. Unit tests strip **host bean discovery indexes from test copies** to run a real ApplicationContext for extension beans without starting the entire Kestra application; host classes remain real. This is not the deployed-image bean discovery test.

For local uncommitted SPI development only, `ALLOW_DEVELOPMENT_OVERLAY=1 scripts/prepare-host.sh <overlay> <upstream-cache>` reads current patches and records `development_worktree: true`; CI never enables this mode. Release compilation must use the fixed ref without this option.

## Configure and deploy

Consume an independently accepted overlay image whose published manifest classpath includes `libs/kestra-rbac-authorizer.jar` and whose unified SPI/execution gates match the contract pin. Mount the jar **read-only at `/app/libs/kestra-rbac-authorizer.jar`**. `/app/plugins` is for Kestra task/trigger plugins and does not discover this extension's web beans. Restart after adding the jar or changing policy. The older overlay README explicitly states `/app/libs` carrier support was not yet delivered; do not infer that mounting works on those images.

Merge [examples/rbac.yml](examples/rbac.yml) into deployment `KESTRA_CONFIGURATION`. [examples/integration.yml](examples/integration.yml) is the controlled local acceptance policy for `rbac.allowed` and `rbac.denied`; infrastructure/authentication configuration is owned by the deployment. The accepted host authenticates requests, validates tokens and supplies an immutable principal. This jar never verifies JWTs, reads raw identity headers, or treats OAuth scopes/client IDs as roles.

Policy rules:

- `roles` maps local role names to grants. `subjects` binds exact trusted subjects to local roles. `role-mapping` maps exact **authenticated host role values** to local roles. Both REST and MCP call the same authorizer with the same subject/roles semantics. Unknown presented roles grant nothing; configured bindings to undefined local roles fail startup.
- Every grant requires `scope`, `resource`, and `actions`. `INSTANCE` has no tenant/namespace; `TENANT` requires tenant and no namespace; `NAMESPACE` requires both. Scope matches exactly and does not promote a namespace grant to tenant/instance. This project targets OSS `main` only; other tenant paths deny.
- Namespace names are dot-separated segments. `descendants: true` grants `rbac.allowed.child`, never `rbac.allowed2`. Without it, only the exact namespace matches. No wildcard syntax is supported.
- Concrete IDs require `ids: [id1, id2]` or explicit `all-ids: true`. Missing/empty `ids` grant **zero IDs**. Combining nonempty IDs with `all-ids` is invalid. Random execution creation requires `all-ids: true`. Collection/domain requirements with null ID also require `all-ids`.
- LIST requires LIST and READ for a complete query domain with unrestricted IDs. An ID-restricted role cannot list and infer counts. The resolver permits only a single exact namespace filter for flow search; it never filters an already paginated response.
- Default is deny. Invalid/unresolved routes, plans, principals or dependencies fail closed. The overlay gate owns absent/throwing bean behavior, authentication 401/redirect, 403 problem+json, and independent MCP scope AND checks.

## Explicitly supported REST surface

| Operation | Route/handler | Policy/limits |
|---|---|---|
| Flow create | POST `/api/v1/main/flows`, `createFlow` | YAML namespace/id required; FLOW CREATE; only `draft` query supported |
| Flow update | PUT `/flows/{namespace}/{id}`, `updateFlow` | YAML identity must equal path; FLOW UPDATE; only `draft` query supported |
| Flow read/delete | GET/DELETE `/flows/{namespace}/{id}`, `getFlow`/`deleteFlow` | FLOW READ/DELETE; read `source=true` supported |
| Flow search | GET `/flows/search`, `searchFlows` | Exactly `filters[namespace][EQUALS]=rbac.allowed`; only page/size/sort alongside; complete-domain LIST+READ |
| Namespace flow list | GET `/flows/{namespace}`, `listFlowsByNamespace` | Complete-domain FLOW LIST+READ |
| Execute | POST `/executions/{namespace}/{id}`, `createExecution` | FLOW READ+EXECUTE, EXECUTION CREATE domain; absent wait or strict `wait=false`/`wait=true` (true also requires EXECUTION ACCESS_OUTPUTS); final overlay gate **must** recheck actual runtime flow, allocated ID and dependency plan before input parsing/queue |
| Execution detail | GET `/executions/{id}`, `getExecution` | Resolve real owning namespace using production repository; EXECUTION READ+ACCESS_OUTPUTS |
| Execution file/metas | GET `/executions/{id}/file` or `/file/metas` | READ+ACCESS_OUTPUTS; one canonical `kestra:` URI strictly inside that execution storage prefix; no authority/query/fragment/encoding/traversal, global file, namespace file, state or other execution |
| MCP server management | `/mcp-servers`, `listMcps`/`getMcp`/`createMcp`/`updateMcp` | Explicit MCP_SERVER TENANT grant; list needs full-domain LIST+READ; no controller-wide allowance |

Other routes are denied, including bulk/import/export, source replacement, revisions/tasks/graphs, execution search/replay/kill/resume, namespace files, KV/secrets, logs/metrics, plugin management and unclassified UI API calls. UI pages may load but unsupported business actions receive 403. There is no automatic owner/admin bypass, SYSTEM-wide UI recovery rule, or menu/button hiding. The fixture administrator receives only the explicitly configured preparation permissions.

## Input/output dependency policy

`SafeFlowDependencies` implements the host `McpAuthorizationResolver` consumed by both actual execution gates. It inspects the **runtime ProcessedFlow declaration**, not an uploaded/latest raw flow. Runtime `tenantId` may be absent/null or equal `main`; another tenant denies. Inputs, variables, checks and labels allow literals only; output expressions allow only an entire `{{ outputs.task.field }}` reference (plain dotted names), or literals. Secret/KV/namespace/file functions, filters, dynamic indexing, interpolation, unknown expression syntax and malformed YAML deny before execution. A safe declaration returns an explicit empty extra-read plan. This policy does not sandbox tasks or reinterpret their later engine privileges.

## Controlled fixture and acceptance

`OidcRbacFixtureTest` consumes production `OidcUserService` and `OIDC_FIXTURE_JDBC` against the isolated test database. `OIDC_FIXTURE_USER`/`OIDC_FIXTURE_PASSWORD` override local defaults `kestra`/`local-ci-only`. It creates `rbac-reader` / `ReaderPass123!` / `[reader]`, `rbac-writer` / `WriterPass123!` / `[writer]`, and `rbac-blocked` / `BlockedPass123!` / `[blocked]`. These passwords are local acceptance constants only. It validates credentials and verifies stored roles; existing conflicting users fail rather than silently supplying the wrong fixture. Without JDBC the test is skipped; the monorepo integration harness must assert it ran and did not skip.

The monorepo consumes fixed image digest, separately built jar/hash, and matching host artifact through Compose. Required remaining evidence includes real jar startup discovery, actual authenticated roles parity, scope/RBAC double denials with zero persistence/queue effects, namespace and query/count isolation, 403 rendering, CSRF order, missing jar, async/composite coverage and disabled deployment regression. Unit test green does not replace those gates. There is no IAM CRUD/UI, audit, EE token service or custom authentication in this extension.

Build/test results, artifact hash and pending fixed-contract/image acceptance are recorded in [validation evidence](docs/validation.md).
