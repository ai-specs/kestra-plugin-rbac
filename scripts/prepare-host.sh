#!/usr/bin/env bash
# Reconstruct the host contract in this repository's ignored build area. Never modify supplied checkouts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OVERLAY="${1:?Usage: scripts/prepare-host.sh <overlay-checkout> [local-upstream-object-cache]}"
OVERLAY="$(cd "$OVERLAY" && pwd)"
PIN="$(sed -n 's/^overlay.ref=//p' "$ROOT/host-contract.properties")"
BASE="$(sed -n 's/^upstream.ref=//p' "$ROOT/host-contract.properties")"
AREA="$ROOT/.integration"
mkdir -p "$AREA"
if [[ -e "$AREA/upstream" || -e "$AREA/overlay-source" ]]; then
    echo 'Use a fresh .integration directory; move the previous directory aside before rerunning.' >&2
    exit 1
fi
mkdir "$AREA/overlay-source"
git -C "$OVERLAY" archive "$PIN" | tar -x -C "$AREA/overlay-source"
if [[ "${ALLOW_DEVELOPMENT_OVERLAY:-0}" == 1 ]]; then
    # Explicit local development mode only; CI never sets this and always consumes git archive at PIN.
    cp -R "$OVERLAY/patches/." "$AREA/overlay-source/patches/"
fi
if [[ "$(sed -n '2p' "$AREA/overlay-source/patches/BASE")" != "$BASE" ]]; then
    echo 'Upstream contract pin mismatch' >&2; exit 1
fi
if [[ -n "${2:-}" ]]; then
    git clone --no-hardlinks "$(cd "$2" && pwd)" "$AREA/upstream"
else
    git clone --depth 1 --branch v2.0.5 https://github.com/kestra-io/kestra.git "$AREA/upstream"
fi
git -C "$AREA/upstream" checkout --detach "$BASE"
for patch in "$AREA"/overlay-source/patches/*/*.patch; do
    git -C "$AREA/upstream" apply --check "$patch"
    git -C "$AREA/upstream" apply "$patch"
done
(cd "$AREA/upstream" && ./gradlew :webserver:jar :core:jar :model:jar --console=plain -x test)
HOST="$AREA/upstream/webserver/build/libs/webserver-2.0.5.jar"
test -s "$HOST"
cp "$HOST" "$AREA/host-webserver.jar"
cp "$AREA/upstream/core/build/libs/core-2.0.5.jar" "$AREA/host-core.jar"
cp "$AREA/upstream/model/build/libs/model-2.0.5.jar" "$AREA/host-model.jar"
cat > "$AREA/classpath.properties" <<'CLASSPATH'
kestraWebserverJar=.integration/host-webserver.jar
kestraHostModules=.integration/host-core.jar,.integration/host-model.jar
CLASSPATH
python3 - "$AREA/host-webserver.jar" "$PIN" "$BASE" "${ALLOW_DEVELOPMENT_OVERLAY:-0}" > "$AREA/host-provenance.json" <<'PYHOST'
import hashlib, json, sys, zipfile
path, overlay, upstream, development = sys.argv[1:]
with zipfile.ZipFile(path) as jar:
    assert all('io/kestra/webserver/rbac/' + name + '.class' in jar.namelist() for name in ['RbacAuthorizer','RestAuthorizationResolver','McpAuthorizationResolver']), 'Unified SPI missing: final overlay ref required'
print(json.dumps({'overlay': overlay, 'upstream': upstream, 'development_worktree': development == '1', 'sha256': hashlib.sha256(open(path, 'rb').read()).hexdigest()}, indent=2))
PYHOST
printf 'Host built: %s\n' "$AREA/host-webserver.jar"
