"""Require the actual application-project suites; missing/skipped reports fail release."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
REQUIRED = {'io.kestra.extensions.rbac.applicationgroup.ApplicationGroupAuthorizationTest': 26, 'io.kestra.extensions.rbac.store.ApplicationGroupPolicyManagementTest': 7, 'io.kestra.extensions.rbac.PolicyEngineTest': 8, 'io.kestra.extensions.rbac.applicationgroup.ApplicationGroupManagementSafetyTest': 3, 'io.kestra.extensions.rbac.store.ApplicationGroupPolicyStoreTest': 5, 'io.kestra.extensions.rbac.store.PolicyDocumentValidatorTest': 6, 'io.kestra.extensions.rbac.store.ProjectMigrationReviewTest': 10, 'io.kestra.extensions.rbac.management.PublishedUserDirectoryAccessTest': 6, 'io.kestra.extensions.rbac.BeanDiscoveryTest': 3, 'io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyBoundaryTest': 3, 'io.kestra.extensions.rbac.management.ManagementControllerTest': 10, 'io.kestra.extensions.rbac.management.ManagementInputTest': 4, 'io.kestra.extensions.rbac.management.PublishedApplicationDirectoryAccessTest': 6, 'io.kestra.extensions.rbac.ConfiguredRbacAuthorizerTest': 2, 'io.kestra.extensions.rbac.store.JdbcPolicyStoreTest': 9, 'io.kestra.extensions.rbac.RuntimePoliciesTest': 9, 'io.kestra.extensions.rbac.SafeFlowDependenciesTest': 2, 'io.kestra.extensions.rbac.ProjectRuntimeDirectoryTest': 9, 'io.kestra.extensions.rbac.TypedPolicyTest': 6, 'io.kestra.extensions.rbac.InstanceAdminTest': 3}
del REQUIRED['io.kestra.extensions.rbac.ProjectRuntimeDirectoryTest']
REQUIRED['io.kestra.extensions.rbac.ApplicationGroupRuntimeDirectoryTest'] = 12
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ScopeCatalogTest'] = 6
REQUIRED['io.kestra.extensions.rbac.management.ManagementControllerTest'] = 18
del REQUIRED['io.kestra.extensions.rbac.management.PublishedApplicationDirectoryAccessTest']
REQUIRED['io.kestra.extensions.rbac.management.ApplicationGroupDirectoryManagementTest'] = 8
REQUIRED['io.kestra.extensions.rbac.store.ApplicationGroupStorageTest'] = 1
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryModelTest'] = 4
REQUIRED['io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStoreTest'] = 16
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupInstallationPlanTest'] = 8
REQUIRED['io.kestra.extensions.rbac.store.ApplicationGroupDirectoryCommandTest'] = 5
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChangesTest'] = 7
REQUIRED['io.kestra.extensions.rbac.RuntimePoliciesTest'] = 10
REQUIRED['io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStoreTest'] = 3
REQUIRED['io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationControllerTest'] = 3
REQUIRED['io.kestra.extensions.rbac.store.ApplicationScopeDeclarationCommandTest'] = 2
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryServiceTest'] = 13
results=Path(sys.argv[1])
for name,count in REQUIRED.items():
    report=ET.parse(results/('TEST-'+name+'.xml')).getroot()
    assert int(report.get('tests','0'))==count, name+' test count differs'
    assert all(int(report.get(key,'0'))==0 for key in ('failures','errors','skipped')), name+' failed or skipped'
print('PASS application-project release suites:',len(REQUIRED),'tests:',sum(REQUIRED.values()),'zero failure/skip')
