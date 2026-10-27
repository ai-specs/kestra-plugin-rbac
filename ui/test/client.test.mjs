import test from 'node:test'
import assert from 'node:assert/strict'
import {createClient, encodeKey, decodeKey} from '../src/client.js'

function recorder() {
  const calls = []
  const signal = new AbortController().signal
  const client = createClient({apiBase: '/kestra/api/v1/main/', signal, request: async (url, options) => {
    calls.push({url, ...options})
    return {ok: true, json: async () => ({revision: 23})}
  }})
  return {client, calls, signal}
}
test('opaque client and subject identifiers round-trip UTF-8 without ambiguous separators', () => {
  for (const value of ['kestra:read', 'name/用户名?x#y', 'é😀', '~new', '\ufeffscope']) {
    const encoded = encodeKey(value)
    assert.match(encoded, /^~[A-Za-z0-9_-]+$/)
    assert.equal(decodeKey(encoded), value)
  }
  for (const value of ['reader', '~', '~YQ==', '~YQ/', '~YR', '~_w']) assert.throws(() => decodeKey(value))
})
test('N14/T3: runtime writes target one application permission with exact parent/object revision', async () => {
  const {client, calls, signal} = recorder()
  const name = 'flows-read'
  const value = {key:name,name:'Read',scopeKeys:['reader'], description:'Read',enabled:true,grants:[{scope:'NAMESPACE',tenant:'main',namespace:'a',descendants:false,resource:'FLOW',actions:['READ'],ids:[],allIds:true}],managementGrants:[]}
  await client.permissions('p','ui/client',1,100)
  await client.createPermission('p','ui/client',value,12)
  await client.updatePermission('p','ui/client',name,value,13)
  await client.deletePermission('p','ui/client',name,14)
  const runtime='/kestra/api/v1/main/extensions/rbac/application-groups/p/applications/' + encodeKey('ui/client') + '/permissions'
  assert.equal(calls[0].url,runtime + '?page=1&size=100')
  assert.equal(calls[2].url,runtime + '/' + name)
  assert.deepEqual(JSON.parse(calls[2].body), value)
  assert.equal(calls[2].headers['If-Match'], '"13"')
  assert.equal(calls[3].body, undefined)
  assert.ok(calls.every(call => call.signal === signal))
  assert.equal(client.bindings, undefined)
  assert.equal(client.roleMappings, undefined)
})
test('identity object writes preserve their distinct revisions and exact subjects', async () => {
  const {client, calls} = recorder()
  await client.createApplicationGroupScope('p', {key:'read',name:'Reader',description:''}, 4)
  await client.updateApplicationGroupScope('p', 'read', {key:'read',name:'Renamed reader',description:'Updated'}, 9)
  await client.createApplication('p', {clientId: 'mcp/a', description: 'Registered', approved: true, firstPartyUi: false, audiences: ['mcp'], supportedScopeKeys: ['read']}, 10)
  await client.updateApplication('p', 'mcp/a', {clientId: 'mcp/a', description: '', approved: false, firstPartyUi: false, audiences: [], supportedScopeKeys: []}, 17)
  await client.createScopeGrant('p', {issuer: 'https://issuer', subject: '用户/a', scopeKeys: ['read']}, 11)
  await client.updateScopeGrant('p', {issuer: 'https://issuer', subject: '用户/a', scopeKeys: []}, 21)
  await client.deleteScopeGrant('p', '用户/a', 22)
  assert.deepEqual(calls.map(call => call.headers['If-Match']), ['"4"', '"9"', '"10"', '"17"', '"11"', '"21"', '"22"'])
  assert.ok(calls[3].url.endsWith('/applications/' + encodeKey('mcp/a')))
  assert.ok(calls[5].url.endsWith('/scope-grants/detail?subject=' + encodeURIComponent('用户/a')))
  assert.deepEqual(JSON.parse(calls[5].body).scopeKeys, [])
})
test('missing revisions cannot silently write and account revision zero remains valid', async () => {
  const {client, calls} = recorder()
  assert.throws(() => client.createPermission('p','ui/client',{},undefined), /revision_required/)
  await client.updateProfile({subject: 's', name: 'Changed', email: ''}, 0)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].headers['If-Match'], '"0"')
})
test('conflicts retain status and stable code without retrying a mutation', async () => {
  let writes = 0
  const client = createClient({apiBase: '/api/v1/main', request: async () => {
    writes++
    return {ok: false, status: 412, json: async () => ({code: 'revision_conflict'})}
  }})
  await assert.rejects(client.updatePermission('p','ui/client','read',{key:'read'},4), error => error.status === 412 && error.code === 'revision_conflict')
  assert.equal(writes, 1)
})
test('context updates replace request, signal and deployment prefix', async () => {
  let current = {apiBase: '/old/api/v1/main', request: () => {throw new Error('stale request')}}
  const client = createClient(() => current)
  current = {apiBase: '/new/api/v1/main', request: async url => {
    assert.equal(url, '/new/api/v1/main/extensions/rbac/capabilities')
    return {ok: true, json: async () => ({allowed: false})}
  }}
  assert.deepEqual(await client.capabilities(), {allowed: false})
})

test('application registration excludes the host UI flag and preserves server rejection without retry', async () => {
  let writes = 0
  const client = createClient({apiBase:'/api/v1/main',request:async (url, options) => {
    writes++
    assert.equal(Object.hasOwn(JSON.parse(options.body),'firstPartyUi'),false)
    return {ok:false,status:409,json:async () => ({code:'directory_conflict'})}
  }})
  await assert.rejects(client.createApplication('p',{clientId:'looks-like-ui',description:'',approved:true,firstPartyUi:true,audiences:['a'],supportedScopeKeys:['s']},4),error => error.status === 409 && error.code === 'directory_conflict')
  assert.equal(writes,1)
  assert.throws(() => client.updateApplication('p','original',{clientId:'changed',approved:true,firstPartyUi:false},4),/client_id_immutable/)
})

test('normal policy editing does not expose full group recovery', async () => {
  const {client,calls}=recorder()
  assert.equal(client.reconciliation,undefined)
  assert.equal(client.reconcileProject,undefined)
  for(const key of ['policy','createProject','updateProject','createScope','updateScope','deleteScope']) assert.equal(client[key],undefined)
  for (const key of ['runtimePolicy','createRuntimePolicy','updateRuntimePolicy','runtimeScopes','runtimeScope','createRuntimeScope','updateRuntimeScope','deleteRuntimeScope','applicationScopeDeclarations','createApplicationScopeDeclaration','updateApplicationScopeDeclaration','deleteApplicationScopeDeclaration']) assert.equal(client[key],undefined)
  await client.updatePermission('p','ui/client','reader',{key:'reader',name:'Read',scopeKeys:['reader'],description:'Read',enabled:true,grants:[],managementGrants:[]},27)
  assert.equal(calls.length,1)
  assert.equal(calls[0].method,'PUT')
  assert.equal(calls[0].headers['If-Match'],'"27"')
  assert.equal(calls[0].url,'/kestra/api/v1/main/extensions/rbac/application-groups/p/applications/' + encodeKey('ui/client') + '/permissions/reader')
})

test('application group capabilities use an exact encoded target and retain policy compare and swap', async () => {
  const {client,calls}=recorder()
  await client.capabilities('target/group')
  await client.createPermission('target','ui/client',{key:'reader',name:'Read',scopeKeys:['reader'],enabled:false,grants:[],managementGrants:[]},37)
  assert.equal(calls[0].url,'/kestra/api/v1/main/extensions/rbac/capabilities?applicationGroupId=target%2Fgroup')
  assert.equal(calls[0].body,undefined)
  assert.equal(calls[1].url,'/kestra/api/v1/main/extensions/rbac/application-groups/target/applications/' + encodeKey('ui/client') + '/permissions')
  assert.equal(calls[1].headers['If-Match'],'"37"')
  assert.deepEqual(JSON.parse(calls[1].body).scopeKeys,['reader'])
  assert.ok(calls.every(call=>!call.url.includes('/identity/')&&!call.url.endsWith('/policy')))
})
test('target capability denial is preserved without retry or falling back to another group', async () => {
  const paths=[]
  const client=createClient({apiBase:'/api/v1/main',request:async path=>{
    paths.push(path)
    return {ok:false,status:403,json:async()=>({code:'directory_revision_changed'})}
  }})
  await assert.rejects(client.capabilities('target'),error=>error.status===403&&error.code==='directory_revision_changed')
  assert.deepEqual(paths,['/api/v1/main/extensions/rbac/capabilities?applicationGroupId=target'])
})
