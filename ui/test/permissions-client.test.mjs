import test from 'node:test'
import assert from 'node:assert/strict'
import {createClient, encodeKey} from '../src/client.js'
test('T9: collection metadata has an impossible-key segment and legal mutation-metadata permission reads remain distinct', async () => {
  const calls=[]
  const api=createClient({apiBase:'/api/v1/main',request:async(path,options)=>{calls.push({path,...options});return {ok:true,json:async()=>({})}}})
  await api.permissionMutationMetadata('g','client')
  await api.permission('g','client','mutation-metadata')
  await api.updatePermission('g','client','mutation-metadata',{key:'mutation-metadata'},1)
  await api.deletePermission('g','client','mutation-metadata',2)
  const root='/api/v1/main/extensions/rbac/application-groups/g/applications/'+encodeKey('client')+'/permissions'
  assert.deepEqual(calls.map(call=>call.path),[root+'/-/mutation-metadata',root+'/mutation-metadata',root+'/mutation-metadata',root+'/mutation-metadata'])
  assert.equal(calls[0].cache,'no-store');assert.equal(calls[2].headers['If-Match'],'"1"');assert.equal(calls[3].headers['If-Match'],'"2"')
})
test('N12–N14/T3: permission CRUD uses only unified resource and correct object/parent revisions', async () => {
  const calls = []
  const api = createClient({apiBase: '/api/v1/main', request: async (url, options) => {calls.push({url, ...options}); return {ok: true, json: async () => ({key: 'p', revision: 9, parentRevision: 12})}}})
  const root = '/api/v1/main/extensions/rbac/application-groups/group/applications/' + encodeKey('client/中文') + '/permissions'
  const value = {key: 'p', name: 'Read', scopeKeys: ['s1', 's2'], details: ''}
  await api.permissions('group', 'client/中文', 2, 25)
  await api.permission('group', 'client/中文', 'p')
  await api.createPermission('group', 'client/中文', value, 10)
  await api.updatePermission('group', 'client/中文', 'p', value, 8)
  await api.deletePermission('group', 'client/中文', 'p', 9)
  assert.deepEqual(calls.map(call => call.url), [root + '?page=2&size=25', root + '/p', root, root + '/p', root + '/p'])
  assert.deepEqual(calls.slice(2).map(call => call.headers['If-Match']), ['"10"', '"8"', '"9"'])
  assert.deepEqual(JSON.parse(calls[2].body), value)
  assert.throws(() => api.updatePermission('group', 'client/中文', 'p', {...value, key: 'q'}, 8), /permission_key_immutable/)
  assert.throws(() => api.createPermission('group', 'client/中文', value), /revision_required/)
  assert.equal(calls.length, 5)
})
test('N19/T3: conflict keeps status and does not retry a permission mutation', async () => {
  let count = 0
  const api = createClient({apiBase: '/api/v1/main', request: async () => {count++; return {ok: false, status: 412, json: async () => ({code: 'revision_conflict'})}}})
  await assert.rejects(api.createPermission('g', 'client', {key: 'p'}, 2), error => error.status === 412 && error.code === 'revision_conflict')
  assert.equal(count, 1)
})
