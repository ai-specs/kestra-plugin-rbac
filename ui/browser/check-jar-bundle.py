#!/usr/bin/env python3
"""Compare the browser's production bundle with every UI byte in the thin jar."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

repo = Path(__file__).resolve().parents[2]
bundle = repo / '.work/plugin-browser/bundle'
jar = repo / 'build/libs/kestra-rbac-authorizer.jar'
sha = lambda data: hashlib.sha256(data).hexdigest()
with zipfile.ZipFile(jar) as archive:
    prefix = 'ui-extensions/rbac/'
    packaged = {name[len(prefix):]: sha(archive.read(name)) for name in archive.namelist()
                if name.startswith(prefix) and not name.endswith('/') and name != prefix + 'manifest.json'}
actual = {str(path.relative_to(bundle)): sha(path.read_bytes()) for path in bundle.rglob('*')
          if path.is_file() and path.name != 'manifest.json'}
aggregate = lambda values: sha('\n'.join(path + '\0' + values[path] for path in sorted(values)).encode())
result = {'jarSha256': sha(jar.read_bytes()), 'jarBundleSha256': aggregate(packaged),
          'browserBundleSha256': aggregate(actual), 'fileCount': len(actual),
          'equal': bool(actual) and actual == packaged,
          'differences': [key for key in sorted(actual.keys() | packaged.keys()) if actual.get(key) != packaged.get(key)]}
print(json.dumps(result, indent=2))
sys.exit(0 if result['equal'] else 1)
