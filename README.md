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
`/app/kestra` without starting a server, checks the public legacy and typed SPI classes, and writes
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
- LIST requires LIST and READ for a complete query domain with unrestricted IDs. An ID-restricted role cannot list and infer counts. The host applies the returned namespace scope before pagination and totals.
- Default is deny. Invalid/unresolved routes, plans, principals or dependencies fail closed. The overlay gate owns absent/throwing bean behavior, authentication 401/redirect, 403 problem+json, and independent MCP scope AND checks.

## Typed resource policy

Set both `kestra.rbac.enabled: true` and `kestra.rbac.typed.enabled: true`. This jar implements
`TypedAuthorizationResolver`; it does not implement the legacy REST route resolver. The host
owns route matching, YAML identity, execution ownership, query filters, dashboard datasource
validation, query ACL and the final runtime execution gate. The plugin never parses URLs,
controller names, handler names, frontend routes or chart definitions.

Single operations require the configured resource/action grant at the actual tenant and namespace.
Complete queries require both READ and LIST with unrestricted IDs. All matching roles contribute
the union of exact namespaces and namespace subtrees; the returned scope intersects the request
domain. READ and LIST can come from different roles; only namespaces authorized for both actions
enter the query scope. Empty restricted scope means no rows. A namespace grant does not promote to tenant or
instance authority. Prefix collisions and exact namespace children remain excluded.
ID-restricted roles cannot query collections, counts or aggregates.

The host declares shared metadata as METADATA/READ. A subject with a configured READ grant can
read those declarations. Metadata permission does not allow plugin installation or other writes.
Unclassified operations require SYSTEM instance authority and deny for ordinary users.
Mapped instance administrators may authorize all valid host operations; missing identity,
unknown contract version and policy exceptions deny.

FLOW, EXECUTION, NAMESPACE, LOG, METRIC, TRIGGER, KVSTORE and NAMESPACE_FILE grants are
independent. Configure READ/LIST for every resource a reader must view, and explicit
CREATE/UPDATE/DELETE/EXECUTE/ACCESS_OUTPUTS for required writer operations. The host supplies
the operation and enforces the scope before pagination, totals and aggregates. Supported host
operations and unavailable OSS pages are described by the host release; this policy does not
invent EE features. UI menu/button behavior is owned by the host.

Real authenticated UI and Compose acceptance remain a deployment gate. Unit tests prove policy
semantics and bean discovery, and do not prove every rendered page or deployed operation.

## Explicit instance administrator

The first user created by the host bootstrap has the authenticated presented role `admin`.
To retain access to all current host UI pages, menus and operations after enabling RBAC,
merge this explicit deployment policy with the ordinary namespace roles:

```yaml
kestra:
  rbac:
    enabled: true
    typed:
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

For a mapped administrator, typed policy permits the host's valid `SYSTEM` / `EXECUTE`
operation with `COMPLETE_BATCH` coverage for unclassified matched business routes.
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
Ordinary namespace users require explicit resource grants and deployment UI acceptance.

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
