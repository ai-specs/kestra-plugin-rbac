from pathlib import Path
import sys
import xml.etree.ElementTree as ET

directory = Path(sys.argv[1])
for name, expected in [('store.PolicyDocumentValidatorTest', 6), ('store.JdbcPolicyStoreTest', 9), ('store.ProjectPolicyStoreTest', 5), ('store.ProjectMigrationReviewTest', 10), ('store.ProjectPolicyManagementTest', 7), ('declaration.ApplicationScopeDeclarationStoreTest', 2)]:
    path = directory / ('TEST-io.kestra.extensions.rbac.' + name + '.xml')
    if not path.is_file():
        raise SystemExit('FAIL: missing required report ' + name)
    suite = ET.parse(path).getroot()
    if int(suite.attrib['tests']) != expected or any(int(suite.attrib.get(key, '0')) for key in ['failures', 'errors', 'skipped']):
        raise SystemExit(name + ' must execute ' + str(expected) + ' cases without failures or skips')
    print('PASS: ' + name + ' ' + str(expected) + ' cases executed')
