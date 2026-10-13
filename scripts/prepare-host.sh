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
python3 - "$TEMP_DIR/host.jar" "$IMAGE" "$TEMP_DIR/provenance.json" <<'PY'
import hashlib,json,sys,zipfile
path,image,output=sys.argv[1:]
with zipfile.ZipFile(path) as jar:
    names=set(jar.namelist())
    for name in ['RbacAuthorizer','RestAuthorizationResolver','McpAuthorizationResolver','TypedAuthorization','TypedAuthorizationResolver']:
        assert f'io/kestra/webserver/rbac/{name}.class' in names, f'Missing host SPI: {name}'
    assert 'io/kestra/core/models/executions/Execution.class' in names
with open(output,'w') as stream:
    json.dump({'image':image,'sha256':hashlib.sha256(open(path,'rb').read()).hexdigest()},stream,indent=2)
    stream.write('\n')
PY
mv "$TEMP_DIR/host.jar" "$AREA/host-webserver.jar"
mv "$TEMP_DIR/provenance.json" "$AREA/host-provenance.json"
printf '%s\n' 'kestraWebserverJar=.integration/host-webserver.jar' 'kestraHostModules=' > "$AREA/classpath.properties"
echo "Host contract extracted from $IMAGE"
