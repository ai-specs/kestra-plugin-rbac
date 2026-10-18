#!/usr/bin/env bash
# Consume the independently published runtime contract; no source checkout or host build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="$(sed -n 's/^overlay.image=//p' "$ROOT/host-contract.properties")"
[[ "$IMAGE" =~ ^ghcr.io/ai-specs/kestra-overlay@sha256:[0-9a-f]{64}$ ]] || { echo 'Invalid immutable host image contract' >&2; exit 1; }
AREA="$ROOT/.integration"
mkdir -p "$AREA"
docker pull "$IMAGE"
CONTAINER="$(docker create "$IMAGE")"
TEMP_DIR="$(mktemp -d "$AREA/host-extract.XXXXXX")"
cleanup() { docker rm "$CONTAINER" >/dev/null; rm -rf "$TEMP_DIR"; }
trap cleanup EXIT
docker cp "$CONTAINER:/app/kestra" "$TEMP_DIR/host.jar"
python3 - "$TEMP_DIR/host.jar" "$IMAGE" "$TEMP_DIR/provenance.json" "$TEMP_DIR/ui-sdk" <<'PY'
import hashlib,json,pathlib,sys,zipfile
path,image,output,sdk_output=sys.argv[1:]
sdk_hashes={}
with zipfile.ZipFile(path) as jar:
    names=set(jar.namelist())
    for name in ['RbacAuthorizer','RestAuthorizationResolver','McpAuthorizationResolver','TypedAuthorization','TypedAuthorizationResolver','AuthorizationEvaluationV1','AuthorizationEvaluationProviderV1','AuthorizationEvaluationProviderV2']:
        assert f'io/kestra/webserver/rbac/{name}.class' in names, f'Missing host SPI: {name}'
    assert 'io/kestra/core/models/executions/Execution.class' in names
    for name in ['io/kestra/webserver/oidc/UserDirectoryV1.class','io/kestra/webserver/oidc/ApplicationDirectoryV1.class','io/kestra/webserver/oidc/TrustedIdentityV1.class','io/kestra/webserver/ui/UiExtensionProviderV1.class','io/kestra/webserver/ui/UiExtensionDescriptorV1.class']:
        assert name in names, f'Missing management contract: {name}'
    prefix='META-INF/kestra/ui-sdk/v1/'
    required={'index.d.ts','contract.json','locales.mjs','check-translations.mjs','shadow-styles.mjs','README.md'}
    sdk_names={name.removeprefix(prefix) for name in names if name.startswith(prefix) and not name.endswith('/')}
    assert required.issubset(sdk_names), 'Incomplete published UI SDK'
    target=pathlib.Path(sdk_output)
    target.mkdir()
    for name in sorted(sdk_names):
        assert '/' not in name and name not in {'.','..'}, 'Unexpected UI SDK path'
        data=jar.read(prefix+name)
        (target/name).write_bytes(data)
        sdk_hashes[name]=hashlib.sha256(data).hexdigest()
with open(output,'w') as stream:
    json.dump({'image':image,'sha256':hashlib.sha256(open(path,'rb').read()).hexdigest(),'uiSdk':{'version':1,'files':sdk_hashes}},stream,indent=2)
    stream.write('\n')
PY
mv "$TEMP_DIR/host.jar" "$AREA/host-webserver.jar"
mv "$TEMP_DIR/provenance.json" "$AREA/host-provenance.json"
rm -rf "$AREA/ui-sdk"
mv "$TEMP_DIR/ui-sdk" "$AREA/ui-sdk"
printf '%s\n' 'kestraWebserverJar=.integration/host-webserver.jar' 'kestraHostModules=' > "$AREA/classpath.properties"
echo "Host contract extracted from $IMAGE"
