"""Require the actual application-project suites; missing/skipped reports fail release."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
REQUIRED = {'io.kestra.extensions.rbac.project.ProjectAuthorizationTest': 26, 'io.kestra.extensions.rbac.store.ProjectPolicyManagementTest': 7, 'io.kestra.extensions.rbac.PolicyEngineTest': 8, 'io.kestra.extensions.rbac.project.ProjectManagementSafetyTest': 3, 'io.kestra.extensions.rbac.store.ProjectPolicyStoreTest': 5, 'io.kestra.extensions.rbac.store.PolicyDocumentValidatorTest': 6, 'io.kestra.extensions.rbac.store.ProjectMigrationReviewTest': 10, 'io.kestra.extensions.rbac.management.PublishedUserDirectoryAccessTest': 6, 'io.kestra.extensions.rbac.BeanDiscoveryTest': 3, 'io.kestra.extensions.rbac.project.ProjectPolicyBoundaryTest': 3, 'io.kestra.extensions.rbac.management.ManagementControllerTest': 10, 'io.kestra.extensions.rbac.management.ManagementInputTest': 4, 'io.kestra.extensions.rbac.management.PublishedApplicationDirectoryAccessTest': 6, 'io.kestra.extensions.rbac.ConfiguredRbacAuthorizerTest': 2, 'io.kestra.extensions.rbac.store.JdbcPolicyStoreTest': 9, 'io.kestra.extensions.rbac.RuntimePoliciesTest': 9, 'io.kestra.extensions.rbac.SafeFlowDependenciesTest': 2, 'io.kestra.extensions.rbac.ProjectRuntimeDirectoryTest': 8, 'io.kestra.extensions.rbac.TypedPolicyTest': 6, 'io.kestra.extensions.rbac.InstanceAdminTest': 3}
results=Path(sys.argv[1])
for name,count in REQUIRED.items():
    report=ET.parse(results/('TEST-'+name+'.xml')).getroot()
    assert int(report.get('tests','0'))==count, name+' test count differs'
    assert all(int(report.get(key,'0'))==0 for key in ('failures','errors','skipped')), name+' failed or skipped'
print('PASS application-project release suites:',len(REQUIRED),'tests:',sum(REQUIRED.values()),'zero failure/skip')
