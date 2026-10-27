from pathlib import Path
import sys
import xml.etree.ElementTree as ET

directory = Path(sys.argv[1])
command=ET.parse(directory/'TEST-io.kestra.extensions.rbac.store.ApplicationGroupDirectoryCommandTest.xml').getroot()
assert int(command.get('tests','0'))==5 and all(int(command.get(key,'0'))==0 for key in ['failures','errors','skipped']), 'Application group installation command must execute all 5 cases'
print('PASS: store.ApplicationGroupDirectoryCommandTest 5 cases executed')
service=ET.parse(directory/'TEST-io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryServiceTest.xml').getroot()
assert int(service.get('tests','0'))==13 and all(int(service.get(key,'0'))==0 for key in ['failures','errors','skipped']), 'Application group service must execute all 13 cases'
service_output='\n'.join(service.itertext())
assert 'Application group service backend: PostgreSQL' in service_output and 'Application group service backend: H2' not in service_output, 'Application group service requires PostgreSQL'
print('PASS: applicationgroup.ApplicationGroupDirectoryServiceTest 13 PostgreSQL cases executed')
for name, expected in [('store.PolicyDocumentValidatorTest', 6), ('store.JdbcPolicyStoreTest', 9), ('store.ApplicationGroupPolicyStoreTest', 5), ('store.ProjectMigrationReviewTest', 10), ('store.ApplicationGroupPolicyManagementTest', 7), ('store.ApplicationGroupStorageTest', 1), ('store.ApplicationGroupDirectoryStoreTest', 17), ('store.ApplicationScopeDeclarationCommandTest', 2), ('declaration.ApplicationScopeDeclarationStoreTest', 3)]:
    path = directory / ('TEST-io.kestra.extensions.rbac.' + name + '.xml')
    if not path.is_file():
        raise SystemExit('FAIL: missing required report ' + name)
    suite = ET.parse(path).getroot()
    if int(suite.attrib['tests']) != expected or any(int(suite.attrib.get(key, '0')) for key in ['failures', 'errors', 'skipped']):
        raise SystemExit(name + ' must execute ' + str(expected) + ' cases without failures or skips')
    if name == 'store.ApplicationGroupDirectoryStoreTest':
        output = '\n'.join(suite.itertext())
        if 'Application group directory store backend: PostgreSQL' not in output or 'Application group directory store backend: H2' in output:
            raise SystemExit(name + ' must execute against PostgreSQL, not H2')
    print('PASS: ' + name + ' ' + str(expected) + ' cases executed')
for name, expected in [('applicationgroup.ApplicationPermissionUpgradeTest', 8), ('applicationgroup.ApplicationPermissionControllerTest', 7)]:
    suite = ET.parse(directory / ('TEST-io.kestra.extensions.rbac.' + name + '.xml')).getroot()
    assert int(suite.get('tests', '0')) == expected and all(int(suite.get(key, '0')) == 0 for key in ('failures', 'errors', 'skipped')), name + ' must execute without failure or skip'
    output = '\n'.join(suite.itertext())
    assert 'Application group service backend: PostgreSQL' in output and 'Application group service backend: H2' not in output, name + ' must use PostgreSQL'
    print('PASS: ' + name + ' ' + str(expected) + ' PostgreSQL cases executed')
cleanup=ET.parse(directory/'TEST-io.kestra.extensions.rbac.management.AccountGrantCleanupTest.xml').getroot()
assert int(cleanup.get('tests','0'))==6 and all(int(cleanup.get(key,'0'))==0 for key in ('failures','errors','skipped')), 'Account cleanup must execute all 6 cases'
cleanup_output='\n'.join(cleanup.itertext())
assert 'Account grant cleanup backend: PostgreSQL' in cleanup_output and 'Account grant cleanup backend: H2' not in cleanup_output, 'Account cleanup requires PostgreSQL'
print('PASS: management.AccountGrantCleanupTest 6 PostgreSQL cases executed')
audit=ET.parse(directory/'TEST-io.kestra.extensions.rbac.store.ManagementAuditStoreTest.xml').getroot()
audit_names={'appendOnlyWriterPreservesOriginalSubjectsAndFiltersItemsAndTotalsBeforePaging','participantAndHostFailureRollbackLeaveNoAuditRows','credentialAuditHasNoSecretFieldsAndMissingTableFailsClosed','targetSnapshotUsesAllMembershipsAndGrouplessAccountsUseOnlyActorGroup'}
assert int(audit.get('tests','0'))==4 and all(int(audit.get(key,'0'))==0 for key in ('failures','errors','skipped')), 'Audit must execute all 4 cases'
assert {case.get('name','').removesuffix('()') for case in audit.findall('testcase')}==audit_names, 'Audit named cases differ'
audit_output='\n'.join(audit.itertext())
assert 'Management audit backend: PostgreSQL' in audit_output and 'Management audit backend: H2' not in audit_output, 'Audit requires PostgreSQL'
print('PASS: store.ManagementAuditStoreTest 4 PostgreSQL named cases executed')
