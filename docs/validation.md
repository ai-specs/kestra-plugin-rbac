# Validation evidence

## 管理存储与业务服务的本地检查（2026-10-15）

策略数据库、规范文档校验、独立初始化入口及角色／绑定业务服务已加入源码；本节不代表
管理控制器、页面、动态授权或新宿主契约已发布。编译仍消费 host-contract.properties
指定的既有正式宿主镜像，未读取相邻源码或候选 jar。

本地 `clean check` 共44项、43通过、零失败／错误，1项生产账号准备测试因没有部署JDBC
受控跳过；该项由部署验收实际运行，不能计为通过。存储与规范校验共15项没有跳过，
另在独立PostgreSQL 18逐项运行同一矩阵全部通过，包含并发schema初始化和并发写入。
独立工具在该数据库两次初始化相同JSON，核对头版本为1、快照数为1。薄jar检查通过。
本地记录保留于忽略的.integration及build日志；最终源码还须独立克隆构建、CI及下载
报告核对，不以当前工作树的本地结果替代正式交付。

CI保存完整单元报告和PostgreSQL报告到独立目录，并显式上传隐藏证据目录。上传成功
不能代替下载核对；同一artifact保留jar、provenance、JUnit XML与HTML报告。

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
