from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

name = 'io.kestra.extensions.rbac.applicationgroup.AccountLifecyclePostgresTest'
required = {
    'participantWriteThenThrowRollsBackAllGroups',
    'hostDeleteFailureRollsBackParticipantWrites',
    'allGroupCleanupAndLastAdministratorUseBorrowedConnection',
    'lastAdministratorRejectionRollsBackGrantRemoval',
    'concurrentMutualDeletionRechecksActorAfterSecurityLock',
    'concurrentMutualDeactivationRechecksActorAfterSecurityLock',
}
report = ET.parse(Path(sys.argv[1]) / ('TEST-' + name + '.xml')).getroot()
assert int(report.get('tests', '0')) == len(required), 'Lifecycle suite count differs'
assert all(int(report.get(key, '0')) == 0 for key in ('failures', 'errors', 'skipped')), 'Lifecycle suite failed or skipped'
actual = {case.get('name', '').removesuffix('()') for case in report.findall('testcase')}
assert actual == required, 'Lifecycle named cases differ'
output = '\n'.join(report.itertext())
assert 'Account lifecycle backend: PostgreSQL; business: plugin-directory; host: published-adapter-participant;' in output and 'backend: H2' not in output and 'internal-test-adapter' not in output, 'Published host PostgreSQL lifecycle evidence required'
provenance_path = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parents[1] / '.integration/host-provenance.json'
provenance = json.loads(provenance_path.read_text())
assert provenance.get('image', '').startswith('ghcr.io/ai-specs/kestra-overlay@sha256:'), 'Immutable host image required'
assert 'executable: ' + provenance['sha256'] + '; image: ' + provenance['image'] in output, 'Lifecycle host provenance mismatch'
additional_name='io.kestra.extensions.rbac.applicationgroup.PublishedManagementPostgresTest'
additional=ET.parse(Path(sys.argv[1])/('TEST-'+additional_name+'.xml')).getroot()
additional_required={
    'passwordResetAndActorDeletionRetainOriginalCommittedAuditSubjects',
    'missingParticipantRefusesMutationAndLeavesAuditAndGrantsUnchanged',
    'issuerAuditReaderSupportsCrossGroupAndRetiredHistoryButManageDoesNotImplyRead',
    'emptyResourceAudiencesUseV2WithoutInventingClientAudience',
    'credentialControllerReturnsExactStatusAndRejectsLoginRotationMissingRevisionAndUnexpectedBody',
    'ordinaryRotationUsesPublishedAdapterAndAuditsNoSecretOrHint',
    'temporaryUserUsesPublishedV2AndDoesNotFabricateCreateAudit',
    'readOnlyAuditAndCredentialStatusNeverGrantManageAndForeignIssuerCannotRead',
    'manageOnlyOrdinaryRotationReturnsOneTimeReceiptWithoutReadAccess',
}
assert int(additional.get('tests','0'))==9 and all(int(additional.get(key,'0'))==0 for key in ('failures','errors','skipped')), 'Published management integration cases missing or failed'
assert {case.get('name','').removesuffix('()') for case in additional.findall('testcase')}==additional_required, 'Published management named cases differ'
additional_output='\n'.join(additional.itertext())
assert 'host: published-adapter-participant;' in additional_output and 'backend: H2' not in additional_output, 'Published management must run on PostgreSQL'
print(json.dumps({'suite': name, 'backend': 'PostgreSQL', 'business': 'plugin-directory', 'hostAdapter': 'published-adapter-participant', 'hostImage': provenance['image'], 'hostExecutableSha256': provenance['sha256'], 'tests': len(required), 'additionalTests': len(additional_required), 'failures': 0, 'errors': 0, 'skipped': 0, 'productionHostIntegrated': True, 'gatePassed': True}, sort_keys=True))
