# Validation evidence

Recorded 2026-10-13. This document distinguishes local development validation from fixed-contract CI and deployed-image acceptance.

## Local evidence

Real CI-equivalent local sequence, using uncommitted overlay patches explicitly:

```bash
ALLOW_DEVELOPMENT_OVERLAY=1 scripts/prepare-host.sh ../kestra-overlay ../kestra-overlay/.work/rbac-dev
./gradlew clean check --console=plain
```

The first command rebuilt a fresh upstream replay inside this repository from BASE `172dd0f3760fb1255d3b22bd1fd07ce31b173fa3`; it produced thin webserver/core/model jars and `.integration/classpath.properties`. The second command used exactly the workflow's Gradle invocation (no fat jar and no manual host classpath arguments). Results: **22 tests, 21 passed, 1 fixture skipped, zero failures/errors**. Breakdown: policy 7, real ApplicationContext 2, unified authorizer 2, REST resolver 8, runtime dependencies 2, fixture 1 skipped because OIDC_FIXTURE_JDBC was absent.

The real ApplicationContext tests cover flat roles/role-mapping map configuration, list Grant conversion, all three SPI beans and enabled/disabled conditions. Host indexes are stripped from temporary test copies so this context verifies the extension indexes without booting unrelated Kestra beans. REST tests call the actual QueryFilterFormatBinder. Thin-jar verification rejects any class outside `io/kestra/extensions/rbac/` and requires the generated Micronaut index.

## Remaining release gates

1. Wait for overlay release CI acceptance and record its matching immutable image digest. The source compilation contract is pinned to `92745d02f05fac77ebb94ecdaf7cf7e998543cca`; local compilation does not assert image readiness.
2. Run independent plugin CI using OVERLAY_DEPLOY_KEY and the pinned contract. Normal local preparation and network shallow upstream checkout passed; hosted CI and deploy-key permissions remain unverified.
3. In monorepo Compose, consume the accepted image and independent jar/hash. Run OidcRbacFixtureTest with JDBC and assert not skipped; it uses production OidcUserService and verifies stored roles/credentials. The fixture's local credentials/users exactly match blackbox-rbac.sh/mjs.
4. Complete actual image bean discovery, REST/MCP authenticated roles parity, scope AND RBAC denial with zero queue/database side effects, positive writer execution through actual runtime gate, namespace/count isolation, file boundaries, 403 problem+json, CSRF order, missing jar, unsupported/batch denial and disabled regression. Plugin unit tests are not these image gates.

## Independent CI dependencies

JDK 25, Git/Bash/Python, Gradle distribution and Maven Central access, fixed upstream v2.0.5 checkout, private overlay checkout through OVERLAY_DEPLOY_KEY only. No PAT, GHCR pull, Docker, plugin reverse checkout or cross-repository deployment is needed for the independent build job. Actual integration stays in the monorepo.

## Validated artifact

Regression coverage confirms: flow IDs use BASE `[a-zA-Z0-9][a-zA-Z0-9._-]*`, HTTP wait=true adds ACCESS_OUTPUTS and reaches the mandatory actual execution gate, and URI validation accepts both kestra:/ and kestra:/// canonical execution-file forms while rejecting authorities/unsafe paths. Dotted ID REST/MCP decisions and both file/metas positive forms are covered by tests.

Final CI-equivalent `./gradlew clean check --console=plain` passed with thin host modules from generated classpath.properties. Frozen jar: 41,551 bytes; SHA-256 `3e364257b17f7a2ee6ded20554350d18b0b216fb5c1a4457f8ecd13998148856`. No foreign dependency/host classes. Hosted CI and image combination acceptance remain pending.

## Normal pinned source validation

The old `.integration` directory was moved to an ignored backup. With `ALLOW_DEVELOPMENT_OVERLAY` unset, these commands passed:

```bash
scripts/prepare-host.sh ../kestra-overlay
./gradlew clean check --console=plain
```

Preparation archived overlay `92745d02f05fac77ebb94ecdaf7cf7e998543cca`, fetched upstream over the network using `git clone --depth 1 --branch v2.0.5`, checked out BASE `172dd0f3760fb1255d3b22bd1fd07ce31b173fa3`, applied the pinned patches and built thin webserver/core/model jars. Provenance records `development_worktree: false` and webserver SHA-256 `ff9067fe792b8f8b2f19dd8fa38cf84ba3345fec3f95ef455d2e3e36a9aee77a`. Host Java compilation reused Gradle cache entries; plugin clean check executed all seven tasks.

The normal pinned check reports 22 tests, zero failures/errors and one JDBC fixture skip. Jar size/hash matches the frozen artifact above, and thin-jar/index verification passes. This validates independent local compilation only; no deployment or integration was performed.
