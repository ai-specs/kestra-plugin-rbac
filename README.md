# kestra-plugin-rbac

Independent, configuration-driven authorization extension for the Kestra OSS overlay. The business policy lives in this repository. Output: **`build/libs/kestra-rbac-authorizer.jar`**. The jar is thin, includes Micronaut generated bean indexes, and contains no Kestra, Micronaut, Jackson or other dependency classes.

The earlier runtime authorization implementation passed independent CI and real monorepo Compose
acceptance; that historical result does not validate the instance-admin change. See [validation evidence](docs/validation.md) for the tested scope. Build dependencies
are extracted from the immutable published image, independently of any overlay source checkout.

## Build independently

Requirements: Docker, JDK 25, Python 3 and read access to the private GHCR host image.
Authenticate to GHCR with a credential that has `read:packages` access, then run:

```bash
./scripts/prepare-host.sh
./gradlew clean check --console=plain
```

`prepare-host.sh` pulls the immutable image in `host-contract.properties`, extracts
`/app/kestra` without starting a server, checks the three public SPI classes, and writes
ignored local compilation dependencies and provenance. It is safe to rerun. No overlay
checkout, upstream clone, patch replay or Kestra build is required.

The host jar is a compile/test dependency only; it is never included in the extension jar.
Unit tests remove host bean indexes from temporary test copies so extension bean discovery
can be tested without booting Kestra. Real deployment discovery is verified by monorepo
Compose acceptance. CI uses the same published-image extraction and build commands. The host package grants
this repository Read access under Manage Actions access, so CI uses its own GITHUB_TOKEN
with packages:read; no personal token or cross-repository source deploy key is required.

## Configure and deploy

Consume an independently accepted overlay image whose published manifest classpath includes `libs/kestra-rbac-authorizer.jar` and whose unified SPI/execution gates match the contract pin. Mount the jar **read-only at `/app/libs/kestra-rbac-authorizer.jar`**. `/app/plugins` is for Kestra task/trigger plugins and does not discover this extension's web beans. Restart the container JVM after replacing the jar. Apply policy configuration changes with Compose to update the container.

Merge [examples/rbac.yml](examples/rbac.yml) into deployment `KESTRA_CONFIGURATION`. [examples/integration.yml](examples/integration.yml) is the controlled local acceptance policy for `rbac.allowed` and `rbac.denied`; infrastructure/authentication configuration is owned by the deployment. The accepted host authenticates requests, validates tokens and supplies an immutable principal. This jar never verifies JWTs, reads raw identity headers, or treats OAuth scopes/client IDs as roles.

Policy rules:

- `roles` maps local role names to grants. `subjects` binds exact trusted subjects to local roles. `role-mapping` maps exact **authenticated host role values** to local roles. Both REST and MCP call the same authorizer with the same subject/roles semantics. Unknown presented roles grant nothing; configured bindings to undefined local roles fail startup.
- `instance-admin-roles` is an explicit list of **local** roles, empty by default. Each listed role must exist in `roles` (an empty grant list is valid). Only `subjects` or `role-mapping` can assign these roles. A presented role named `deployment-admin` does not confer instance authority unless explicitly mapped. Invalid administrator-role configuration fails startup when RBAC is enabled.
- Every grant requires `scope`, `resource`, and `actions`. `INSTANCE` has no tenant/namespace; `TENANT` requires tenant and no namespace; `NAMESPACE` requires both. Scope matches exactly and does not promote a namespace grant to tenant/instance. Resource and execution authorization targets OSS `main`; other tenant resources deny. Instance administrators cover every matched REST route at the instance level, without interpreting its business path.
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

For non-administrators, other routes are denied, including bulk/import/export, source replacement, revisions/tasks/graphs, execution search/replay/kill/resume, namespace files, KV/secrets, logs/metrics, plugin management and unclassified UI API calls. UI pages may load but unsupported business actions receive 403. There is no implicit owner/admin privilege or menu/button hiding. Ordinary namespace-role UI support remains limited to the routes above and is a separate deployment requirement. Multiple ordinary local roles already contribute the union of their grants; no role-switch mechanism is added here.

## Explicit instance administrator

The first user created by the host bootstrap has the authenticated presented role `admin`.
To retain access to all current host UI pages, menus and operations after enabling RBAC,
merge this explicit deployment policy with the ordinary namespace roles:

```yaml
kestra:
  rbac:
    enabled: true
    instance-admin-roles: [deployment-admin]
    roles:
      deployment-admin: []
    role-mapping:
      admin: [deployment-admin]
```

This maps **every trusted principal carrying the host role `admin`** to instance authority.
Use an exact trusted `subjects` binding instead if only one account should receive that authority;
use the host's immutable authenticated subject, not a display name or an assumed login name.
Never map roles from untrusted headers or client input. The default configuration grants no
instance administrator rights. An authenticated role with the same name as a local role is
not automatically mapped.

For a mapped administrator, the REST resolver returns a nonempty `SYSTEM` / `INSTANCE`
`EXECUTE` plan with `COMPLETE_BATCH` coverage for every matched route supplied by the host.
Instance authority covers the full query domain and all mutations; no business-route whitelist,
namespace filter or execution-repository lookup is required. This includes global configuration,
execution search, bulk operations, plugin management and all other current host UI operations.
Missing route metadata or invalid identity still denies. The authorizer permits valid resource
requirements for administrators, including the final runtime execution checks.

With `kestra.rbac.enabled: false`, the host disables the RBAC gate and the extension beans
are absent; existing host UI behavior is retained. Enabling instance authority changes resource
authorization only: host authentication, CSRF, MCP capability scopes and missing/throwing
component fail-closed checks still apply. This extension does not enable host-disabled features,
create missing plugins or implement unavailable host UI pages.

Administrators receive an **explicit empty extra-dependency list** for a valid runtime flow,
including dynamic input/default/output/variable expressions. Runtime YAML must still parse
without duplicate keys and match invocation namespace, flow ID and OSS tenant. Ordinary users
retain the conservative dependency policy below. Instance authority includes secret/KV/file
reads; this is an intentional full-instance privilege, not a task sandbox.

Independent tests and CI validate plugin behavior and bean injection. The monorepo owner must
still execute real authenticated UI/Compose acceptance before claiming production delivery,
including RBAC on/off, CSRF, MCP scope denials, missing components and refusal side effects.
Ordinary namespace users' filtered UI support is outside this administrator change.

## Input/output dependency policy

`SafeFlowDependencies` implements the host `McpAuthorizationResolver` consumed by both actual execution gates. It inspects the **runtime ProcessedFlow declaration**, not an uploaded/latest raw flow. Runtime `tenantId` may be absent/null or equal `main`; another tenant denies. Inputs, variables, checks and labels allow literals only; output expressions allow only an entire `{{ outputs.task.field }}` reference (plain dotted names), or literals. Secret/KV/namespace/file functions, filters, dynamic indexing, interpolation, unknown expression syntax and malformed YAML deny before execution. A safe declaration returns an explicit empty extra-read plan. This policy does not sandbox tasks or reinterpret their later engine privileges.

## Controlled fixture and acceptance

`OidcRbacFixtureTest` consumes production `OidcUserService` and `OIDC_FIXTURE_JDBC` against the isolated test database. `OIDC_FIXTURE_USER`/`OIDC_FIXTURE_PASSWORD` override local defaults `kestra`/`local-ci-only`. It creates `rbac-reader` / `ReaderPass123!` / `[reader]`, `rbac-writer` / `WriterPass123!` / `[writer]`, and `rbac-blocked` / `BlockedPass123!` / `[blocked]`. These passwords are local acceptance constants only. It validates credentials and verifies stored roles; existing conflicting users fail rather than silently supplying the wrong fixture. Without JDBC the test is skipped; the monorepo integration harness must assert it ran and did not skip.

The monorepo consumes fixed image digest, separately built jar/hash, and matching host artifact through Compose. Required remaining evidence includes real jar startup discovery, actual authenticated roles parity, scope/RBAC double denials with zero persistence/queue effects, namespace and query/count isolation, 403 rendering, CSRF order, missing jar, async/composite coverage and disabled deployment regression. Unit test green does not replace those gates. There is no IAM CRUD/UI, audit, EE token service or custom authentication in this extension.

Build/test results, artifact hash and image acceptance are recorded in [validation evidence](docs/validation.md).

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
