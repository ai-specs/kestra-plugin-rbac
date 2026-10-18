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
test('opaque scope and client keys round-trip UTF-8 without ambiguous separators', () => {
  for (const value of ['kestra:read', 'name/用户名?x#y', 'é😀', '~new', '\ufeffscope']) {
    const encoded = encodeKey(value)
    assert.match(encoded, /^~[A-Za-z0-9_-]+$/)
    assert.equal(decodeKey(encoded), value)
  }
  for (const value of ['reader', '~', '~YQ==', '~YQ/', '~YR', '~_w']) assert.throws(() => decodeKey(value))
})
test('local writes use global revision and name payload, never legacy role endpoints', async () => {
  const {client, calls, signal} = recorder()
  const name = 'flows:/读取'
  const value = {name, description: 'Read', enabled: true, grants: []}
  await client.createProject({id: 'p', issuer: 'https://issuer', audiences: ['ui'], enabled: true, directoryRevision: 4}, 11)
  await client.createScope('p', value, 12)
  await client.updateScope('p', name, value, 13)
  await client.deleteScope('p', name, 14)
  assert.equal(calls[0].url, '/kestra/api/v1/main/extensions/rbac/projects')
  assert.equal(calls[2].url, '/kestra/api/v1/main/extensions/rbac/projects/p/scopes/' + encodeKey(name))
  assert.deepEqual(JSON.parse(calls[2].body), value)
  assert.equal(calls[2].headers['If-Match'], '"13"')
  assert.equal(calls[3].body, undefined)
  assert.ok(calls.every(call => call.signal === signal))
  assert.equal(client.bindings, undefined)
  assert.equal(client.roleMappings, undefined)
})
test('identity object writes preserve their distinct revisions and exact subjects', async () => {
  const {client, calls} = recorder()
  await client.createIdentityScope('p', {scope: 'read', resourceIntents: [], managementIntents: []}, 4)
  await client.updateIdentityScope('p', 'read', {scope: 'read', active: false}, 9)
  await client.createClient('p', {clientId: 'mcp/a', approved: true, firstPartyUi: false, audiences: ['mcp'], allowedProjectScopes: ['read']}, 10)
  await client.updateClient('p', 'mcp/a', {clientId: 'mcp/a', approved: false, firstPartyUi: false, audiences: [], allowedProjectScopes: []}, 17)
  await client.createAssignment('p', {issuer: 'https://issuer', subject: '用户/a', projectScopes: ['read']}, 11)
  await client.updateAssignment('p', {issuer: 'https://issuer', subject: '用户/a', projectScopes: []}, 21)
  await client.deleteAssignment('p', '用户/a', 22)
  assert.deepEqual(calls.map(call => call.headers['If-Match']), ['"4"', '"9"', '"10"', '"17"', '"11"', '"21"', '"22"'])
  assert.ok(calls[3].url.endsWith('/clients/' + encodeKey('mcp/a')))
  assert.ok(calls[5].url.endsWith('/assignments/detail?subject=' + encodeURIComponent('用户/a')))
  assert.deepEqual(JSON.parse(calls[5].body).projectScopes, [])
})
test('missing revisions cannot silently write and account revision zero remains valid', async () => {
  const {client, calls} = recorder()
  assert.throws(() => client.createScope('p', {}, undefined), /revision_required/)
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
  await assert.rejects(client.updateProject('p', {}, 4), error => error.status === 412 && error.code === 'revision_conflict')
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

test('firstPartyUi is an explicit boolean and server receipt rejection is preserved without retry', async () => {
  let writes = 0
  const client = createClient({apiBase:'/api/v1/main',request:async (url, options) => {
    writes++
    assert.equal(JSON.parse(options.body).firstPartyUi, true)
    return {ok:false,status:403,json:async () => ({code:'first_party_ui_receipt_required'})}
  }})
  await assert.rejects(client.createClient('p',{clientId:'looks-like-ui',approved:true,firstPartyUi:true,audiences:['a'],allowedProjectScopes:['s']},4),error => error.status === 403 && error.code === 'first_party_ui_receipt_required')
  assert.equal(writes,1)
  assert.throws(() => client.updateClient('p','original',{clientId:'changed',approved:true,firstPartyUi:false},4),/client_id_immutable/)
})

test('atomic reconciliation uses its dedicated endpoint, full project body and global policy revision', async () => {
  const {client,calls}=recorder()
  const project={id:'p',issuer:'https://issuer',audiences:['ui'],enabled:true,directoryRevision:8,scopes:{a:{description:'',enabled:true,grants:[],managementGrants:[{domain:'LOCAL_POLICY',actions:['MANAGE'],projectIds:[]}]}}}
  await client.reconciliation('p')
  await client.reconcileProject('p',project,27)
  assert.equal(calls[0].url,'/kestra/api/v1/main/extensions/rbac/projects/p/reconciliation')
  assert.equal(calls[1].method,'PUT')
  assert.equal(calls[1].headers['If-Match'],'"27"')
  assert.deepEqual(JSON.parse(calls[1].body),project)
})

test('local binding options and save use only target local-policy endpoints and exact policy revision', async () => {
  const {client,calls}=recorder()
  await client.bindingOptions('target')
  await client.createScope('target',{name:'opaque:/scope',enabled:false,grants:[],managementGrants:[]},37)
  assert.equal(calls[0].url,'/kestra/api/v1/main/extensions/rbac/projects/target/binding-options')
  assert.equal(calls[0].body,undefined)
  assert.equal(calls[1].url,'/kestra/api/v1/main/extensions/rbac/projects/target/scopes')
  assert.equal(calls[1].headers['If-Match'],'"37"')
  assert.equal(JSON.parse(calls[1].body).name,'opaque:/scope')
  assert.ok(calls.every(call=>!call.url.includes('/identity/')&&!call.url.endsWith('/policy')))
})
test('normal binding projection denial is preserved without retry or falling back to recovery', async () => {
  const paths=[]
  const client=createClient({apiBase:'/api/v1/main',request:async path=>{
    paths.push(path)
    return {ok:false,status:403,json:async()=>({code:'directory_revision_changed'})}
  }})
  await assert.rejects(client.bindingOptions('target'),error=>error.status===403&&error.code==='directory_revision_changed')
  assert.deepEqual(paths,['/api/v1/main/extensions/rbac/projects/target/binding-options'])
})
