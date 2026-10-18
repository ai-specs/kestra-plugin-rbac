from pathlib import Path
import sys
import xml.etree.ElementTree as ET

directory = Path(sys.argv[1])
for name, expected in [('PolicyDocumentValidatorTest', 6), ('JdbcPolicyStoreTest', 9), ('ProjectPolicyStoreTest', 5), ('ProjectMigrationReviewTest', 10), ('ProjectPolicyManagementTest', 7)]:
    suite = ET.parse(directory / ('TEST-io.kestra.extensions.rbac.store.' + name + '.xml')).getroot()
    if int(suite.attrib['tests']) != expected or any(int(suite.attrib.get(key, '0')) for key in ['failures', 'errors', 'skipped']):
        raise SystemExit(name + ' must execute ' + str(expected) + ' cases without failures or skips')
    print('PASS: ' + name + ' ' + str(expected) + ' cases executed')
