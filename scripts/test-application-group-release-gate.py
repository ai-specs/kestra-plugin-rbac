import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
source = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)
checker = ROOT / 'scripts/check-application-project-tests.py'
reports = sorted(source.glob('TEST-*.xml'))
assert reports, 'Real generated JUnit reports are required'
before = {report.name: hashlib.sha256(report.read_bytes()).hexdigest() for report in reports}


def execute(directory, name, passing):
    result = subprocess.run([sys.executable, str(checker), str(directory)], text=True, capture_output=True)
    (output / (name + '.log')).write_text(result.stdout + result.stderr)
    assert (result.returncode == 0) == passing, 'Release checker regression failed: ' + name


execute(source, 'actual-complete-matrix', True)
for mutation in ['old-authorization-count', 'missing-typed-suite', 'missing-policy-case', 'skipped-child', 'failed-header']:
    with tempfile.TemporaryDirectory(prefix='rbac-release-checker-') as temporary:
        directory = Path(temporary)
        for report in reports:
            shutil.copy2(report, directory / report.name)
        if mutation == 'missing-typed-suite':
            (directory / 'TEST-io.kestra.extensions.rbac.ExtensionRouteTypedPolicyTest.xml').unlink()
        else:
            name = 'io.kestra.extensions.rbac.applicationgroup.' + (
                'ExtensionRoutePolicyTest' if mutation == 'missing-policy-case' else 'ApplicationGroupAuthorizationTest')
            path = directory / ('TEST-' + name + '.xml')
            document = ET.parse(path)
            suite = document.getroot()
            if mutation == 'old-authorization-count':
                suite.set('tests', '26')
                for testcase in suite.findall('testcase'):
                    if testcase.get('name', '').startswith('extensionRoute'):
                        suite.remove(testcase)
            elif mutation == 'missing-policy-case':
                suite.remove(suite.find('testcase'))
            elif mutation == 'skipped-child':
                ET.SubElement(suite.find('testcase'), 'skipped')
            else:
                suite.set('failures', '1')
            document.write(path)
        execute(directory, mutation, False)
assert before == {report.name: hashlib.sha256(report.read_bytes()).hexdigest() for report in reports}
(output / 'summary.json').write_text(json.dumps({'positive': 1, 'negative': 5, 'failures': 0,
    'originalReportsUnchanged': True, 'scope': 'release checker regression, not extra business tests'}, indent=2) + '\n')
print('PASS release checker: actual complete matrix and five rejected mutations; original JUnit unchanged')
