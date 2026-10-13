# Validation evidence

The authorization implementation at commit `41aac28c60c9e9820982b6a5492fc3b7f4f11ce9`
passed [independent CI 38355208652](https://github.com/ai-specs/kestra-plugin-rbac/actions/runs/38355208652).
Real monorepo Compose acceptance on 2026-10-13 passed with published overlay image
`ghcr.io/ai-specs/kestra-overlay@sha256:10788da0c2e470561921177f183f8f3ea290f4a261603f69957c34fb97ff2a00`.
The original jar was 41,551 bytes with SHA-256
`3e364257b17f7a2ee6ded20554350d18b0b216fb5c1a4457f8ecd13998148856`.
These are historical test inputs, not a deployment lock.

## Independent build from the published image

The build now consumes the published image through `scripts/prepare-host.sh`; it no longer
checks out overlay sources, clones upstream, applies patches or builds host modules.
The script checks the real host SPI, extracts `/app/kestra`, and records image/hash provenance
in ignored `.integration/host-provenance.json`. It can be rerun without removing old directories.
CI and third-party users run the same commands:

```bash
./scripts/prepare-host.sh
./gradlew clean check --console=plain
```

Local published-image build passed: 22 tests, 21 passed, one controlled JDBC fixture skipped,
zero failures/errors. Extension configuration binding and all three SPI beans use a real
ApplicationContext; host bean indexes are stripped only from test copies. The published
extension jar includes no host or third-party classes.

The JDBC fixture runs against the isolated deployment database during monorepo acceptance,
where the harness requires that it does not skip. It uses production OidcUserService.

## Deployed behavior

Complete Compose acceptance covered real OIDC identities, REST/MCP role parity, independent
OAuth scope denials, namespace/query isolation, chunked request bodies, successful REST/MCP
execution, execution output download and cross-resource file refusal, dangerous declaration
refusal, missing-component fail-closed behavior and disabled regression. Denied operations
were checked for database/queue side effects. Deployment integration belongs to the monorepo;
it is not part of the plugin build or overlay build.

Supported routes and conservative refusals are listed in README. Unit tests do not imply
support for unclassified routes, complete bulk operations or task-level execution sandboxing.
