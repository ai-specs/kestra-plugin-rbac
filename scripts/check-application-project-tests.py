"""Require the actual application-project suites; missing/skipped reports fail release."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
REQUIRED = {'io.kestra.extensions.rbac.applicationgroup.ApplicationGroupAuthorizationTest': 26, 'io.kestra.extensions.rbac.store.ApplicationGroupPolicyManagementTest': 7, 'io.kestra.extensions.rbac.PolicyEngineTest': 8, 'io.kestra.extensions.rbac.applicationgroup.ApplicationGroupManagementSafetyTest': 3, 'io.kestra.extensions.rbac.store.ApplicationGroupPolicyStoreTest': 5, 'io.kestra.extensions.rbac.store.PolicyDocumentValidatorTest': 6, 'io.kestra.extensions.rbac.store.ProjectMigrationReviewTest': 10, 'io.kestra.extensions.rbac.management.PublishedUserDirectoryAccessTest': 6, 'io.kestra.extensions.rbac.BeanDiscoveryTest': 3, 'io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyBoundaryTest': 3, 'io.kestra.extensions.rbac.management.ManagementControllerTest': 10, 'io.kestra.extensions.rbac.management.ManagementInputTest': 4, 'io.kestra.extensions.rbac.management.PublishedApplicationDirectoryAccessTest': 6, 'io.kestra.extensions.rbac.ConfiguredRbacAuthorizerTest': 2, 'io.kestra.extensions.rbac.store.JdbcPolicyStoreTest': 9, 'io.kestra.extensions.rbac.RuntimePoliciesTest': 9, 'io.kestra.extensions.rbac.SafeFlowDependenciesTest': 2, 'io.kestra.extensions.rbac.ProjectRuntimeDirectoryTest': 9, 'io.kestra.extensions.rbac.TypedPolicyTest': 6, 'io.kestra.extensions.rbac.InstanceAdminTest': 3}
del REQUIRED['io.kestra.extensions.rbac.ProjectRuntimeDirectoryTest']
REQUIRED['io.kestra.extensions.rbac.ApplicationGroupRuntimeDirectoryTest'] = 12
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ScopeCatalogTest'] = 6
REQUIRED['io.kestra.extensions.rbac.management.ManagementControllerTest'] = 29
del REQUIRED['io.kestra.extensions.rbac.management.PublishedApplicationDirectoryAccessTest']
REQUIRED['io.kestra.extensions.rbac.management.ApplicationGroupDirectoryManagementTest'] = 8
REQUIRED['io.kestra.extensions.rbac.store.ApplicationGroupStorageTest'] = 2
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryModelTest'] = 4
REQUIRED['io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStoreTest'] = 17
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupInstallationPlanTest'] = 8
REQUIRED['io.kestra.extensions.rbac.store.ApplicationGroupDirectoryCommandTest'] = 5
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChangesTest'] = 7
REQUIRED['io.kestra.extensions.rbac.RuntimePoliciesTest'] = 13
REQUIRED['io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStoreTest'] = 3
REQUIRED['io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationControllerTest'] = 3
REQUIRED['io.kestra.extensions.rbac.store.ApplicationScopeDeclarationCommandTest'] = 2
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryServiceTest'] = 13
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationPermissionTest'] = 5
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationPermissionControllerTest'] = 12
REQUIRED['io.kestra.extensions.rbac.applicationgroup.NamespaceExpressionTest'] = 6
REQUIRED['io.kestra.extensions.rbac.management.ApplicationPermissionRouteTest'] = 1
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationPermissionUpgradeTest'] = 9
REQUIRED['io.kestra.extensions.rbac.management.AccountGrantCleanupTest'] = 6
REQUIRED['io.kestra.extensions.rbac.management.ClientCredentialManagementTest'] = 7
REQUIRED['io.kestra.extensions.rbac.management.TemporaryAccountCommandsTest'] = 4
REQUIRED['io.kestra.extensions.rbac.store.ManagementAuditStoreTest'] = 4
REQUIRED['io.kestra.extensions.rbac.store.ManagementAuditSchemaCommandTest'] = 2
REQUIRED['io.kestra.extensions.rbac.store.NewInstallMigrationTest'] = 3
REQUIRED['io.kestra.extensions.rbac.management.PublishedUserDirectoryAccessTest'] = 8
REQUIRED['io.kestra.extensions.rbac.AuthorizationDependencyTest'] = 5
REQUIRED['io.kestra.extensions.rbac.applicationgroup.InitialApplicationGroupPlanTest'] = 6
REQUIRED['io.kestra.extensions.rbac.InitialApplicationGroupParticipantTest'] = 6
REQUIRED['io.kestra.extensions.rbac.AiTypedPolicyTest'] = 7
REQUIRED['io.kestra.extensions.rbac.applicationgroup.AiTenantGrantTest'] = 5
REQUIRED['io.kestra.extensions.rbac.applicationgroup.NamespaceComplementTest'] = 7
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ApplicationGroupAuthorizationTest'] = 28
REQUIRED['io.kestra.extensions.rbac.ExtensionRouteTypedPolicyTest'] = 1
REQUIRED['io.kestra.extensions.rbac.applicationgroup.ExtensionRoutePolicyTest'] = 2
results=Path(sys.argv[1])
for name,count in REQUIRED.items():
    report=ET.parse(results/('TEST-'+name+'.xml')).getroot()
    assert int(report.get('tests','0'))==count, name+' test count differs'
    assert all(int(report.get(key,'0'))==0 for key in ('failures','errors','skipped')), name+' failed or skipped'
    assert len(report.findall('testcase')) == count, name+' testcase count differs'
    assert not any(case.find(tag) is not None for case in report.findall('testcase') for tag in ('failure','error','skipped')), name+' has failed or skipped testcase'
for suite, expected in {
 'io.kestra.extensions.rbac.ExtensionRouteTypedPolicyTest': {'typedOperationUsesExactGrantAndRejectsWrongResourceOrQuery'},
 'io.kestra.extensions.rbac.applicationgroup.ExtensionRoutePolicyTest': {'acceptsOnlyNamespaceExecuteAndExactId','doesNotEnterInitialAdministratorPlan'},
}.items():
    root=ET.parse(results/('TEST-'+suite+'.xml')).getroot()
    assert expected == {case.get('name','').removesuffix('()') for case in root.findall('testcase')}, suite+' named cases differ'
authorization=ET.parse(results/'TEST-io.kestra.extensions.rbac.applicationgroup.ApplicationGroupAuthorizationTest.xml').getroot()
assert {'extensionRouteUsesNamespaceAndExactCapabilityIntersection','extensionRoutePolicyRoundTripsCanonicalStorage'}.issubset(
    {case.get('name','').removesuffix('()') for case in authorization.findall('testcase')}), 'Extension route authorization cases missing'
named={
 'io.kestra.extensions.rbac.applicationgroup.NamespaceComplementTest': {
 'positiveAndComplementExactAndSubtreeMatchScalarAndList', 'complementAndPositiveTermsPreserveCorrelatedIdsAcrossScopes',
 'readListIntersectsInsideScopeAndNeverBorrowsAcrossScopes', 'complementWriteRequiresExplicitActionAndDoesNotGrantExcludedTree',
 'malformedExpressionsAndTenantExpressionsReject', 'validLargeStoredGrantSetLoadsButQueryOverLimitFailsClosed',
 'semanticCoverageAgreesWithFinitePartitionForAllSignedSelectorsAndIds'},
}
for suite, expected in named.items():
    root=ET.parse(results/('TEST-'+suite+'.xml')).getroot()
    assert {case.get('name','').removesuffix('()') for case in root.findall('testcase')}==expected, suite+' named cases differ'
for suite, expected in {
 'io.kestra.extensions.rbac.RuntimePoliciesTest': {'unknownMixedScopeAndWrongEpochDeny', 'initialAdministratorAndSecondAccountUseOnlyExplicitRuntimeDataGrants', 'legacyBusinessDomainNeverGrantsAnyOrdinaryResourceOrSystem', 'businessManagementUsesExplicitActionsAndNeverExtensionOrIdentity', 'typedTenantMetadataAndMcpListsHaveRealAllowPaths', 'explicitManagementTargetCanPrecedeItsLocalBinding', 'splitManagementOnlyTargetsActivateExtensionAndCapabilitiesWithoutCombiningAuthorization', 'correlatedListDoesNotProduceCartesianProductOrCrossScopeReadList', 'extensionActivationIsExactAndDoesNotTurnReadIntoManage', 'allAudiencesMustBeLocallyAccepted', 'pinnedEvaluationExpiresWithinAcceptedRevocationBound', 'applicationPolicyDoesNotStoreDirectoryRevision', 'v2PinsOneSnapshotAndClosesIt'},
 'io.kestra.extensions.rbac.applicationgroup.ApplicationPermissionControllerTest': {'namespaceExpressionsPersistAndManagementResponsesRoundTripWithoutLegacyFlags', 'createUpdateDeleteUseObjectRevisionAndReceiptWithoutPolicyDisclosure', 'strictInputsMissingRevisionAndUndefinedScopeHaveNoSideEffects', 'deniedAndInvalidAiMutationsLeaveAllPersistedRbacRowsUnchanged', 'permissionPagesSortByStableKeyBeforeSlicingUnorderedStorage', 'signedNamespaceGrantsRoundTripRejectInvalidAndObserveExplicitRevocation', 'identityOnlyCannotAccessKestraPolicyAndLastAdminMutationRollsBack', 'metadataChangesKeepTrustedIdentityUsableAndConsentUsesUnifiedEntities', 'legalMutationMetadataKeyKeepsReadManageSeparationThroughCreateUpdateAndDelete', 'aiTenantCreateFindUpdateDeletePersistAndNewEvaluationsObserveRevocation', 'policyOnlyWriterCannotChangeSupportButCanChangeGrantsAndCannotRead', 'legacyAiGrantSurvivesPublicControllerMetadataEditWithoutTenantPromotion'},
}.items():
    root=ET.parse(results/('TEST-'+suite+'.xml')).getroot()
    assert expected == {case.get('name','').removesuffix('()') for case in root.findall('testcase')}, suite+' named cases differ'
print('PASS application-project release suites:',len(REQUIRED),'tests:',sum(REQUIRED.values()),'zero failure/skip')
