import {memberInput, managementAccess, requiresReauthentication, loginEntry, managementGrantInput, isRuntimeApplication} from '../src/application-group-model.js'
import {permissionDraft, availableScopeOptions, collectPermissions, permissionInput} from '../src/access-control-model.js'
import test from 'node:test'
import assert from 'node:assert/strict'
import {canonicalPath, collectPages, groupScopeInput, typedGrantsInput, selectGrantResource, splitLines} from '../src/application-group-model.js'

test('empty paths enter application groups and legacy paths are not redirected into new authority', () => {
  for (const path of ['', '/']) assert.equal(canonicalPath(path), 'application-groups')
  for (const path of ['roles','roles/old','bindings','role-mappings','projects','projects/p']) assert.equal(canonicalPath('/'+path+'/'),path)
  assert.equal(canonicalPath('/application-groups/p/scopes/reader/'), 'application-groups/p/scopes/reader')
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
test('group scope input contains only the stable key and human readable metadata', () => {
  const result = groupScopeInput({projectId:'p',key:'reader',name:'读取者',description:'Read workflows',revision:99,active:true,resourceIntents:[{actions:['UPDATE']}],managementIntents:[{domain:'IDENTITY',actions:['MANAGE']}]})
  assert.deepEqual(result,{key:'reader',name:'读取者',description:'Read workflows'})
  for(const scope of ['Bad','has space','a:b','x'.repeat(65),'']) assert.throws(()=>groupScopeInput({key:scope,name:'Reader',description:''}),error=>error.code==='invalid_scope')
  for(const name of ['', ' ']) assert.throws(()=>groupScopeInput({key:'reader',name,description:''}),error=>error.code==='invalid_scope')
})
test('N14/T3: typed permission content retains complete grants without combining ranges or actions', () => {
  const source = {key: 'opaque', scopeKeys:['reader'], description: '', enabled: true, revision: 44, grants: [{scope: 'NAMESPACE', tenant: 'main', namespace: 'A', descendants: false, resource: 'FLOW', actions: ['READ', 'LIST'], allIds: true, ids: ['stale']}, {scope: 'NAMESPACE', tenant: 'main', namespace: 'B', descendants: true, resource: 'FLOW', actions: ['UPDATE'], allIds: false, ids: 'flow/二\nflow,one'}]}
  const result = typedGrantsInput(source)
  assert.deepEqual(result.grants[0].actions, ['READ', 'LIST'])
  assert.equal(result.grants[0].namespace, 'A')
  assert.deepEqual(result.grants[0].ids, [])
  assert.deepEqual(result.grants[1].actions, ['UPDATE'])
  assert.equal(result.grants[1].namespace, 'B')
  assert.deepEqual(result.grants[1].ids, ['flow/二', 'flow,one'])
  assert.equal(result.revision, undefined)
  assert.deepEqual(source.grants[0].ids, ['stale'])
})

test('Kestra management grants remain independent of group metadata and do not expand domains', () => {
  const result = typedGrantsInput({description: '', enabled: true, grants: [], managementGrants: [{domain: 'LOCAL_POLICY', actions: ['READ']}]})
  assert.deepEqual(result.grants, [])
  assert.deepEqual(result.managementGrants, [{domain: 'LOCAL_POLICY', actions: ['READ'], applicationGroupIds: []}])
  assert.equal(result.managementIntents, undefined)
})

test('METADATA has a fixed read-only tenant range and cannot carry business or management actions', () => {
  const grant = {scope: 'NAMESPACE', tenant: 'main', namespace: 'A', descendants: true, resource: 'METADATA', actions: ['READ', 'LIST', 'UPDATE', 'MANAGE'], ids: ['x'], allIds: false}
  selectGrantResource(grant)
  assert.deepEqual(grant, {scope: 'TENANT', tenant: 'main', namespace: null, descendants: false, resource: 'METADATA', actions: ['READ', 'LIST'], ids: [], allIds: true})
  const input = typedGrantsInput({description: '', enabled: true, grants: [grant], managementGrants: []})
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
  for (const scope of ['TENANT', 'INSTANCE', 'SYSTEM']) assert.throws(() => typedGrantsInput({description: '', enabled: true, grants: [{scope, tenant: 'main', resource: 'FLOW', actions: ['READ'], ids: [], allIds: true}]}), error => error.code === 'invalid_grant')
})


test('MCP server configuration is an explicit tenant resource with independent ID and action limits', () => {
  const grant={scope:'NAMESPACE',tenant:'main',namespace:'a',descendants:true,resource:'MCP_SERVER',actions:['READ','LIST','UPDATE','EXECUTE'],allIds:false,ids:'default'}
  selectGrantResource(grant)
  assert.equal(grant.scope,'TENANT')
  assert.equal(grant.namespace,null)
  assert.deepEqual(grant.actions,['READ','LIST','UPDATE'])
  const input=typedGrantsInput({description:'',enabled:true,grants:[grant]})
  assert.deepEqual(input.grants[0].ids,['default'])
  assert.equal(input.grants[0].allIds,false)
  assert.throws(()=>typedGrantsInput({description:'',enabled:true,grants:[{...grant,actions:['EXECUTE']}]}),error=>error.code==='invalid_grant')
})

test('N11/T3: application metadata cannot write derived support, protocol approval or host UI flag', () => {
  for (const clientId of ['ui','kestra-ui','admin','mcp']) {
    assert.deepEqual(memberInput({clientId,description:'Application',approved:false,firstPartyUi:true,audiences:'ui\nmcp',supportedScopeKeys:'scope-a\nscope-b'}),{clientId,description:'Application',audiences:['ui','mcp']})
  }
  for (const supportedScopeKeys of [['BAD'],['a:a'],['reader','reader']]) assert.equal(Object.hasOwn(memberInput({clientId:'ui',description:'',approved:true,supportedScopeKeys}), 'supportedScopeKeys'), false)
  for (const description of [undefined, 42, 'x'.repeat(4097)]) assert.throws(() => memberInput({clientId:'ui',description,approved:'true',firstPartyUi:true}),error => error.code === 'invalid_client_metadata')
})
test('application policy management cannot revive stale identity or grant group directory management', () => {
  const localOnly={allowed:true,localPolicyManagement:true,identityManagement:false}
  assert.deepEqual(managementAccess(localOnly,false),{local:true,identity:false})
  assert.deepEqual(managementAccess(localOnly,true),{local:false,identity:false})
  assert.deepEqual(managementAccess({...localOnly,identityManagement:true},true),{local:false,identity:false})
  assert.deepEqual(managementAccess({allowed:false,localPolicyManagement:true,identityManagement:true},false),{local:false,identity:false})
  assert.deepEqual(managementAccess({allowed:true,roles:['admin'],scopes:['admin']},false),{local:false,identity:false})
})
test('stale identity errors require production sign-in while ordinary forbidden does not', () => {
  assert.equal(requiresReauthentication({code:'directory_revision_changed',status:403}),true)
  assert.equal(requiresReauthentication({status:401}),true)
  assert.equal(requiresReauthentication({code:'identity_epoch_changed',status:403}),true)
  assert.equal(requiresReauthentication({code:'forbidden',status:403}),false)
  assert.equal(loginEntry('/kestra/api/v1/main'),'/kestra/oidc/logout')
  assert.equal(loginEntry('/api/v1/main'),'/oidc/logout')
  for (const path of ['//evil.invalid/api/v1/main','https://evil.invalid/api/v1/main','/unknown']) assert.throws(() => loginEntry(path))
})

test('management project targets are exact, explicit and limited to LOCAL_POLICY', () => {
  assert.deepEqual(managementGrantInput({domain:'LOCAL_POLICY',actions:['MANAGE'],applicationGroupIds:'a\nb'}),{domain:'LOCAL_POLICY',actions:['MANAGE'],applicationGroupIds:['a','b']})
  assert.deepEqual(managementGrantInput({domain:'LOCAL_POLICY',actions:['READ']}),{domain:'LOCAL_POLICY',actions:['READ'],applicationGroupIds:[]})
  for (const applicationGroupIds of [['*'],['a/*'],['a','a'],['../a'],['a b']]) assert.throws(() => managementGrantInput({domain:'LOCAL_POLICY',actions:['MANAGE'],applicationGroupIds}),error => error.code === 'invalid_management_project_ids')
  for (const domain of ['IDENTITY','BUSINESS_INSTANCE']) assert.throws(() => managementGrantInput({domain,actions:['MANAGE'],applicationGroupIds:['other']}),error => error.code === 'invalid_management_project_ids')
})
test('group metadata cannot supply the application runtime management targets', () => {
  const shared=groupScopeInput({key:'s',name:'Reader',description:'',managementIntents:[{domain:'LOCAL_POLICY',actions:['MANAGE'],applicationGroupIds:['shared-target']}]})
  const local=typedGrantsInput({description:'',enabled:true,grants:[],managementGrants:[{domain:'LOCAL_POLICY',actions:['READ'],applicationGroupIds:['local-target']}]})
  assert.equal(shared.managementIntents,undefined)
  assert.deepEqual(local.managementGrants[0].applicationGroupIds,['local-target'])
  assert.equal(shared.managementGrants,undefined)
  assert.equal(local.managementIntents,undefined)
})

test('management target IDs enforce the frozen 128-character grammar', () => {
  const valid='A' + 'x'.repeat(127)
  const invalid=['a.b','A'+'x'.repeat(128),'_a','-a','', 'a/b']
  assert.deepEqual(managementGrantInput({domain:'LOCAL_POLICY',actions:['MANAGE'],applicationGroupIds:[valid]}).applicationGroupIds,[valid])
  for (const id of invalid) {
    assert.throws(() => managementGrantInput({domain:'LOCAL_POLICY',actions:['MANAGE'],applicationGroupIds:[id]}),error => error.code === 'invalid_management_project_ids')
  }
})


test('malformed complete grants fail before writing rather than silently changing input', () => {
  const base={scope:'NAMESPACE',tenant:'main',namespace:'a',descendants:false,resource:'FLOW',actions:['READ'],allIds:true,ids:[]}
  const encode=grant=>typedGrantsInput({enabled:true,grants:[grant]})
  for (const override of [{actions:[]},{actions:['READ','READ']},{actions:['MANAGE']},{resource:'UNKNOWN'},{namespace:''},{allIds:false,ids:[]},{descendants:'false'},{allIds:'true'}]) assert.throws(()=>encode({...base,...override}),error=>error.code==='invalid_grant')
  const metadata={...base,scope:'TENANT',namespace:null,resource:'METADATA'}
  assert.equal(encode(metadata).grants[0].resource,'METADATA')
  for (const override of [{scope:'NAMESPACE',namespace:'a'},{tenant:'other'},{actions:['UPDATE']},{allIds:false,ids:['x']},{ids:['x']},{descendants:true}]) assert.throws(()=>encode({...metadata,...override}),error=>error.code==='invalid_grant')
  assert.throws(()=>managementGrantInput({domain:'LOCAL_POLICY',actions:[]}),error=>error.code==='invalid_management_actions')
})



test('only the explicitly configured current application receives its runtime policy view', () => {
  const capabilities={allowed:true,applicationGroupId:'group',runtimeApplicationClientId:'configured-ui'}
  assert.equal(isRuntimeApplication(capabilities,'group','configured-ui'),true)
  for(const clientId of ['admin','kestra-ui','configured-ui-other','']) assert.equal(isRuntimeApplication(capabilities,'group',clientId),false)
  assert.equal(isRuntimeApplication(capabilities,'other','configured-ui'),false)
  assert.equal(isRuntimeApplication({...capabilities,allowed:false},'group','configured-ui'),false)
  assert.equal(isRuntimeApplication({...capabilities,runtimeApplicationClientId:''},'group','configured-ui'),false)
  assert.equal(isRuntimeApplication({allowed:true,applicationGroupId:'group',firstPartyUi:true},'group','configured-ui'),false)
})
test('N14/T3: scope metadata cannot populate or enable a new application permission', () => {
  const metadata={key:'reader',name:'Administrator',description:'May update everything',enabled:true,grants:[{actions:['UPDATE']}],managementGrants:[{domain:'IDENTITY',actions:['MANAGE']}]}
  const draft = permissionDraft(null, 'kestra-policy')
  assert.deepEqual(draft,{key:'',name:'',scopeKeys:[],description:'',enabled:true,grants:[],managementGrants:[]})
  assert.deepEqual(draft.grants, []); assert.deepEqual(draft.managementGrants, [])
  assert.equal(metadata.grants[0].actions[0], 'UPDATE')
  assert.throws(()=>groupScopeInput({scope:'reader',name:'Reader',description:''}),error=>error.code==='invalid_scope')
})

test('N13/T3: unified permission scope options include every group scope, even already used scopes', () => {
  const reader={key:'reader',name:'Reader',description:''}
  const writer={key:'writer',name:'Writer',description:''}
  const outside={key:'outside',name:'Outside',description:''}
  const scopes=[reader,writer,outside,{key:'BAD',name:'Invalid',description:''}]
  assert.deepEqual(availableScopeOptions(scopes),[outside, reader, writer])
  assert.deepEqual(availableScopeOptions([]),[])
  assert.deepEqual(availableScopeOptions([reader]),[reader])
  assert.equal(Object.hasOwn(reader,'active'),false)
  assert.deepEqual(scopes,[reader,writer,outside,{key:'BAD',name:'Invalid',description:''}])
})
test('N14/T3: unified permission editing copies actual grants without scope metadata', () => {
  const metadata={key:'reader',name:'Reader',description:'Group description'}
  const policy={key:'p',name:'Read',scopeKeys:['reader'],implementationKind:'kestra-policy',description:'Kestra policy',enabled:true,grants:[{resource:'FLOW',namespace:'allowed',actions:['READ']}],managementGrants:[]}
  const editor=permissionDraft(policy,'kestra-policy')
  assert.deepEqual(editor.scopeKeys,['reader'])
  assert.equal(editor.description,'Kestra policy')
  assert.deepEqual(editor.grants,policy.grants)
  editor.grants[0].actions.push('UPDATE')
  assert.deepEqual(policy.grants[0].actions,['READ'])
  assert.equal(metadata.description,'Group description')
})

test('N14/N19/T3: unified reads reject a legacy global wrapper and wrong parent snapshot', async () => {
  const value={items:[{key:'p',parentRevision:7}],total:1,page:1,size:100,parentRevision:7}
  assert.deepEqual(await collectPermissions(async()=>value,undefined,7), value.items)
  for(const change of [{parentRevision:0},{parentRevision:'7'},{parentRevision:8},{items:[{key:'p',parentRevision:8}]},{items:undefined}]) await assert.rejects(collectPermissions(async()=>({...value,...change}),undefined,7))
  await assert.rejects(collectPermissions(async()=>({revision:7,policy:{applicationGroups:{}}}),undefined,7))
})

test('N13/N14/T3: constructor scope stays plain data and cannot acquire inherited grants', () => {
  const metadata={key:'constructor',name:'Constructor',description:''}
  assert.deepEqual(availableScopeOptions([metadata]),[metadata])
  const result=permissionDraft({key:'constructor',name:'Constructor',scopeKeys:['constructor'],implementationKind:'kestra-policy',grants:[],managementGrants:[],enabled:false},'kestra-policy')
  assert.deepEqual(result.scopeKeys,['constructor']);assert.equal(result.enabled,false);assert.deepEqual(result.grants,[])
  assert.throws(()=>permissionInput({name:'reader',enabled:true,grants:[]},'kestra-policy',[]),error=>error.code==='invalid_permission')
})

test('project directory pagination rejects same-total revision changes and missing revisions', async () => {
  const pageResult = (page, size, directoryRevision) => ({items: Array(page === 1 ? 100 : 1).fill({}), total:101, page, size, directoryRevision})
  await assert.rejects(collectPages(async (page,size) => pageResult(page,size,page === 1 ? 7 : 8), undefined,7), error => error.code === 'directory_changed')
  await assert.rejects(collectPages(async (page,size) => pageResult(page,size,undefined), undefined,7), error => error.code === 'directory_changed')
  assert.equal((await collectPages(async (page,size) => pageResult(page,size,7),undefined,7)).length,101)
})
