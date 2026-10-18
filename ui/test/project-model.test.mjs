import {memberInput, managementAccess, requiresReauthentication, loginEntry, reconciliationInput, managementGrantInput, directoryNeedsReconciliation, bindingOptionsModel, newLocalBinding} from '../src/project-model.js'
import test from 'node:test'
import assert from 'node:assert/strict'
import {canonicalPath, collectPages, intentInput, scopeInput, selectGrantResource, splitLines} from '../src/project-model.js'

test('legacy routes redirect to projects without preserving a role authority path', () => {
  for (const path of ['', '/', 'roles', '/roles/old', 'bindings', 'role-mappings']) assert.equal(canonicalPath(path), 'projects')
  assert.equal(canonicalPath('/projects/p/scopes/~cmVhZA/'), 'projects/p/scopes/~cmVhZA')
})
test('directory filtering can receive every page rather than only the first page', async () => {
  const source = Array.from({length: 207}, (_, id) => ({id, subject: 's' + id}))
  const calls = []
  const items = await collectPages(async (page, size) => {
    calls.push(page)
    return {items: source.slice((page - 1) * size, page * size), total: source.length, page, size}
  })
  assert.equal(items.find(item => item.subject === 's206').id, 206)
  assert.deepEqual(calls, [1, 2, 3])
})
test('moving or incomplete directories fail explicitly instead of showing a partial success', async () => {
  await assert.rejects(collectPages(async (page, size) => ({items: page === 1 ? Array.from({length:100}, (_, id) => ({id})) : [], total: page === 1 ? 101 : 102, page, size})), error => error.code === 'directory_changed')
  await assert.rejects(collectPages(async (page, size) => ({items: [], total: 1, page, size})), error => error.status === 409)
  await assert.rejects(collectPages(async (page, size) => ({items: [{}], total: 0, page, size})), error => error.code === 'directory_changed')
})
test('cancelled navigation stops directory pagination before another request', async () => {
  const abort = new AbortController()
  let calls = 0
  await assert.rejects(collectPages(async (page, size) => {
    calls++
    abort.abort()
    return {items: Array(100).fill({}), total: 101, page, size}
  }, abort.signal), error => error.name === 'AbortError')
  assert.equal(calls, 1)
})
test('identity intent inputs preserve exact resource IDs and do not interpret scope names', () => {
  const result = intentInput({projectId: 'p', scope: 'opaque:/scope', description: 'Read', active: true, revision: 99, resourceIntents: [{resourceType: 'FLOW', namespaces: {kind: 'RESTRICTED', exact: 'team-a\nteam-b', subtrees: ['team-c']}, resourceIds: {kind: 'RESTRICTED', ids: 'flow,with,commas\nflow/二'}, actions: ['READ', 'LIST']}], managementIntents: [{domain: 'IDENTITY', actions: ['READ']}]})
  assert.equal(result.projectId, undefined)
  assert.equal(result.scope, 'opaque:/scope')
  assert.equal(result.revision, undefined)
  assert.deepEqual(result.resourceIntents[0].resourceIds.ids, ['flow,with,commas', 'flow/二'])
  assert.equal(result.resourceIntents[0].vocabularyVersion, 'kestra-resources-v1')
  assert.deepEqual(result.managementIntents, [{domain: 'IDENTITY', actions: ['READ'], projectIds: []}])
})
test('a local scope input retains each complete grant without combining ranges or actions', () => {
  const source = {name: 'opaque', description: '', enabled: true, revision: 44, grants: [{scope: 'NAMESPACE', tenant: 'main', namespace: 'A', descendants: false, resource: 'FLOW', actions: ['READ', 'LIST'], allIds: true, ids: ['stale']}, {scope: 'NAMESPACE', tenant: 'main', namespace: 'B', descendants: true, resource: 'FLOW', actions: ['UPDATE'], allIds: false, ids: 'flow/二\nflow,one'}]}
  const result = scopeInput(source)
  assert.deepEqual(result.grants[0].actions, ['READ', 'LIST'])
  assert.equal(result.grants[0].namespace, 'A')
  assert.deepEqual(result.grants[0].ids, [])
  assert.deepEqual(result.grants[1].actions, ['UPDATE'])
  assert.equal(result.grants[1].namespace, 'B')
  assert.deepEqual(result.grants[1].ids, ['flow/二', 'flow,one'])
  assert.equal(result.revision, undefined)
  assert.deepEqual(source.grants[0].ids, ['stale'])
})

test('local management grants remain independent of identity intents and do not expand domains', () => {
  const result = scopeInput({name: 'opaque-admin-text', description: '', enabled: true, grants: [], managementGrants: [{domain: 'LOCAL_POLICY', actions: ['READ']}]})
  assert.deepEqual(result.grants, [])
  assert.deepEqual(result.managementGrants, [{domain: 'LOCAL_POLICY', actions: ['READ'], projectIds: []}])
  assert.equal(result.managementIntents, undefined)
})

test('METADATA has a fixed read-only tenant range and cannot carry business or management actions', () => {
  const grant = {scope: 'NAMESPACE', tenant: 'main', namespace: 'A', descendants: true, resource: 'METADATA', actions: ['READ', 'LIST', 'UPDATE', 'MANAGE'], ids: ['x'], allIds: false}
  selectGrantResource(grant)
  assert.deepEqual(grant, {scope: 'TENANT', tenant: 'main', namespace: null, descendants: false, resource: 'METADATA', actions: ['READ', 'LIST'], ids: [], allIds: true})
  const input = scopeInput({name: 's', description: '', enabled: true, grants: [grant], managementGrants: []})
  assert.deepEqual(input.grants, [grant])
  grant.resource = 'FLOW'
  selectGrantResource(grant)
  assert.equal(grant.scope, 'NAMESPACE')
  assert.equal(grant.namespace, '')
})

test('actual LF and CRLF separate IDs, audiences and scope sets without splitting literal escapes', () => {
  const lf = String.fromCharCode(10)
  const crlf = String.fromCharCode(13, 10)
  for (const values of [['id/一', 'id,two'], ['https://ui.example', 'mcp-resource'], ['scope:a', 'scope:b']]) {
    for (const separator of [lf, crlf]) assert.deepEqual(splitLines(values.join(separator)), values)
  }
  assert.deepEqual(splitLines('a' + String.fromCharCode(92) + 'n' + 'b'), ['a' + String.fromCharCode(92) + 'n' + 'b'])
})

test('unsupported tenant business and instance grants cannot be silently turned into normal permissions', () => {
  for (const scope of ['TENANT', 'INSTANCE', 'SYSTEM']) assert.throws(() => scopeInput({name: 's', description: '', enabled: true, grants: [{scope, tenant: 'main', resource: 'FLOW', actions: ['READ'], ids: [], allIds: true}]}), error => error.code === 'invalid_grant')
})


test('MCP server configuration is an explicit tenant resource with independent ID and action limits', () => {
  const grant={scope:'NAMESPACE',tenant:'main',namespace:'a',descendants:true,resource:'MCP_SERVER',actions:['READ','LIST','UPDATE','EXECUTE'],allIds:false,ids:'default'}
  selectGrantResource(grant)
  assert.equal(grant.scope,'TENANT')
  assert.equal(grant.namespace,null)
  assert.deepEqual(grant.actions,['READ','LIST','UPDATE'])
  const input=scopeInput({name:'server-reader',description:'',enabled:true,grants:[grant]})
  assert.deepEqual(input.grants[0].ids,['default'])
  assert.equal(input.grants[0].allIds,false)
  assert.throws(()=>scopeInput({name:'bad',description:'',enabled:true,grants:[{...grant,actions:['EXECUTE']}]}),error=>error.code==='invalid_grant')
})

test('first-party UI membership is never inferred from client ID and rejects non-boolean approval', () => {
  for (const clientId of ['ui','kestra-ui','admin','mcp']) {
    assert.deepEqual(memberInput({clientId,approved:false,firstPartyUi:false,audiences:'ui\nmcp',allowedProjectScopes:'s:a\ns:b'}),{clientId,approved:false,firstPartyUi:false,audiences:['ui','mcp'],allowedProjectScopes:['s:a','s:b']})
  }
  for (const firstPartyUi of [undefined,'true',1,null]) assert.throws(() => memberInput({clientId:'ui',approved:true,firstPartyUi}),error => error.code === 'invalid_client_flags')
  assert.throws(() => memberInput({clientId:'ui',approved:'true',firstPartyUi:true}),error => error.code === 'invalid_client_flags')
})
test('reconciliation authority cannot revive stale identity or grant identity CRUD', () => {
  const localOnly={allowed:true,needsReconciliation:true,localPolicyManagement:true,identityManagement:false}
  assert.deepEqual(managementAccess(localOnly,false),{local:true,identity:false})
  assert.deepEqual(managementAccess(localOnly,true),{local:false,identity:false})
  assert.deepEqual(managementAccess({...localOnly,identityManagement:true},true),{local:false,identity:false})
  assert.deepEqual(managementAccess({allowed:false,localPolicyManagement:true,identityManagement:true},false),{local:false,identity:false})
  assert.deepEqual(managementAccess({allowed:true,roles:['admin'],scopes:['admin'],needsReconciliation:true},false),{local:false,identity:false})
})
test('directory revision errors require production sign-in, ordinary forbidden does not create recovery authority', () => {
  assert.equal(requiresReauthentication({code:'directory_revision_changed',status:403}),true)
  assert.equal(requiresReauthentication({status:401}),true)
  assert.equal(requiresReauthentication({code:'identity_epoch_changed',status:403}),true)
  assert.equal(requiresReauthentication({code:'forbidden',status:403}),false)
  assert.equal(loginEntry('/kestra/api/v1/main'),'/kestra/ui/')
  assert.equal(loginEntry('/api/v1/main'),'/ui/')
  for (const path of ['//evil.invalid/api/v1/main','https://evil.invalid/api/v1/main','/unknown']) assert.throws(() => loginEntry(path))
})

test('management project targets are exact, explicit and limited to LOCAL_POLICY', () => {
  assert.deepEqual(managementGrantInput({domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds:'a\nb'}),{domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds:['a','b']})
  assert.deepEqual(managementGrantInput({domain:'LOCAL_POLICY',actions:['READ']}),{domain:'LOCAL_POLICY',actions:['READ'],projectIds:[]})
  for (const projectIds of [['*'],['a/*'],['a','a'],['../a'],['a b']]) assert.throws(() => managementGrantInput({domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds}),error => error.code === 'invalid_management_project_ids')
  for (const domain of ['IDENTITY','BUSINESS_INSTANCE']) assert.throws(() => managementGrantInput({domain,actions:['MANAGE'],projectIds:['other']}),error => error.code === 'invalid_management_project_ids')
})
test('atomic recovery includes every scope and never automatically adopts a newer directory revision', () => {
  const project={id:'p',issuer:'https://issuer',audiences:['ui','mcp'],enabled:true,directoryRevision:4,scopes:{a:{description:'A',enabled:true,grants:[],managementGrants:[{domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds:['target']}]},b:{description:'B',enabled:true,grants:[{scope:'NAMESPACE',tenant:'main',namespace:'a',descendants:false,resource:'FLOW',actions:['READ','LIST'],ids:[],allIds:true}],managementGrants:[]}}}
  const value=reconciliationInput(project)
  assert.deepEqual(Object.keys(value.scopes),['a','b'])
  assert.equal(value.directoryRevision,4)
  assert.deepEqual(value.scopes.a.managementGrants[0].projectIds,['target'])
  assert.equal(value.scopes.b.grants[0].namespace,'a')
  assert.equal(value.scopes.a.name,undefined)
  assert.equal(project.directoryRevision,4)
  assert.throws(() => reconciliationInput({...project,scopes:{empty:{description:'',enabled:true,grants:[],managementGrants:[]}}}),error => error.code === 'invalid_reconciliation')
})

test('shared and local management targets are serialized independently with no implicit own project', () => {
  const shared=intentInput({scope:'s',description:'',active:true,resourceIntents:[],managementIntents:[{domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds:['shared-target']}]})
  const local=scopeInput({name:'s',description:'',enabled:true,grants:[],managementGrants:[{domain:'LOCAL_POLICY',actions:['READ'],projectIds:['local-target']}]})
  assert.deepEqual(shared.managementIntents[0].projectIds,['shared-target'])
  assert.deepEqual(local.managementGrants[0].projectIds,['local-target'])
  assert.equal(shared.managementGrants,undefined)
  assert.equal(local.managementIntents,undefined)
})

test('management targets and recovery project IDs enforce the same frozen 128-character grammar', () => {
  const valid='A' + 'x'.repeat(127)
  const invalid=['a.b','A'+'x'.repeat(128),'_a','-a','', 'a/b']
  assert.deepEqual(managementGrantInput({domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds:[valid]}).projectIds,[valid])
  for (const id of invalid) {
    if (id) assert.throws(() => managementGrantInput({domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds:[id]}),error => error.code === 'invalid_management_project_ids')
    assert.throws(() => reconciliationInput({id,issuer:'https://issuer',audiences:['ui'],enabled:true,directoryRevision:1,scopes:{}}),error => error.code === 'invalid_reconciliation')
  }
  assert.equal(reconciliationInput({id:valid,issuer:'https://issuer',audiences:['ui'],enabled:true,directoryRevision:1,scopes:{}}).id,valid)
})


test('malformed complete grants fail before writing rather than silently changing input', () => {
  const base={scope:'NAMESPACE',tenant:'main',namespace:'a',descendants:false,resource:'FLOW',actions:['READ'],allIds:true,ids:[]}
  const encode=grant=>scopeInput({name:'s',enabled:true,grants:[grant]})
  for (const override of [{actions:[]},{actions:['READ','READ']},{actions:['MANAGE']},{resource:'UNKNOWN'},{namespace:''},{allIds:false,ids:[]},{descendants:'false'},{allIds:'true'}]) assert.throws(()=>encode({...base,...override}),error=>error.code==='invalid_grant')
  const metadata={...base,scope:'TENANT',namespace:null,resource:'METADATA'}
  assert.equal(encode(metadata).grants[0].resource,'METADATA')
  for (const override of [{scope:'NAMESPACE',namespace:'a'},{tenant:'other'},{actions:['UPDATE']},{allIds:false,ids:['x']},{ids:['x']},{descendants:true}]) assert.throws(()=>encode({...metadata,...override}),error=>error.code==='invalid_grant')
  assert.throws(()=>managementGrantInput({domain:'LOCAL_POLICY',actions:[]}),error=>error.code==='invalid_management_actions')
})


test('directory binding compares Project.revision and never the independent revocation epoch', () => {
  const project={revision:7,authorizationVersion:42}
  assert.equal(directoryNeedsReconciliation(project,{directoryRevision:7}),false)
  assert.equal(directoryNeedsReconciliation(project,{directoryRevision:42}),true)
  assert.equal(directoryNeedsReconciliation({...project,authorizationVersion:100},{directoryRevision:7}),false)
  assert.equal(directoryNeedsReconciliation({...project,revision:8},{directoryRevision:7}),true)
  const input=reconciliationInput({id:'p',issuer:'https://issuer',audiences:['ui'],enabled:true,directoryRevision:7,authorizationVersion:42,scopes:{}})
  assert.equal(input.directoryRevision,7)
  assert.equal(input.authorizationVersion,undefined)
})


const bindingProjection=()=>({policyRevision:37,directoryRevision:7,upperBound:{project:{id:'target',revision:7,authorizationVersion:99},scopes:[{scope:'opaque:/scope',description:'Read',active:true,resourceIntents:[{resourceType:'FLOW',actions:['READ','LIST']}],managementIntents:[{domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds:['target']}]},{scope:'disabled',active:false}]}})
test('binding editor starts disabled with empty independent grants instead of copying shared upper bounds', () => {
  const source=bindingProjection()
  const options=bindingOptionsModel(source,'target')
  const binding=newLocalBinding(options,'opaque:/scope')
  assert.deepEqual(binding,{name:'opaque:/scope',description:'Read',enabled:false,grants:[],managementGrants:[]})
  binding.grants.push({resource:'FLOW'})
  assert.equal(source.upperBound.scopes[0].resourceIntents.length,1)
  assert.equal(options.upperBound.scopes[0].resourceIntents.length,1)
  assert.equal(binding.resourceIntents,undefined)
  assert.equal(options.directoryRevision,7)
  assert.equal(options.policyRevision,37)
  for(const name of ['unknown','disabled']) assert.throws(()=>newLocalBinding(options,name),error=>error.code==='invalid_scope')
})
test('binding projection rejects other targets, inconsistent snapshot revision, duplicate scope and incomplete revisions', () => {
  const source=bindingProjection()
  assert.throws(()=>bindingOptionsModel(source,'other'),error=>error.code==='invalid_binding_options')
  for(const override of [{policyRevision:0},{policyRevision:'37'},{directoryRevision:99},{directoryRevision:0},{upperBound:{...source.upperBound,scopes:[...source.upperBound.scopes,source.upperBound.scopes[0]]}},{upperBound:{...source.upperBound,scopes:[{scope:'s',active:'true'}]}}]) assert.throws(()=>bindingOptionsModel({...source,...override},'target'),error=>error.code==='invalid_binding_options')
  assert.equal(bindingOptionsModel({...source,upperBound:{...source.upperBound,project:{...source.upperBound.project,authorizationVersion:1000}}},'target').directoryRevision,7)
})
