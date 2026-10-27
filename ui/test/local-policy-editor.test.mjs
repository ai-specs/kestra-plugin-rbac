import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {reactive} from 'vue'
import {loginEntry, permissionScopeKeys, permissionParentRevision, typedGrantsInput} from '../src/application-group-model.js'
import {permissionInput, permissionDraft} from '../src/access-control-model.js'
import {createClient, encodeKey} from '../src/client.js'

test('T9: local policy creation reads only exact mutation metadata and sends its parent revision', async () => {
  const calls = []
  const api = createClient({apiBase: '/kestra/api/v1/main', request: async (path, options) => {
    calls.push({path, ...options})
    return {ok: true, status: 200, json: async () => options.method === 'POST' ? {key: 'new', revision: 1, parentRevision: 8} : {clientId: 'client/id', revision: 7, actions: ['MANAGE']}}
  }})
  const revision = await permissionParentRevision(api, 'group', 'client/id')
  const draft = {key: 'new', name: 'New', scopeKeys: ['known'], enabled: true, description: '', grants: [], managementGrants: []}
  const input = permissionInput(draft, 'kestra-policy', permissionScopeKeys(draft.scopeKeys, [], false))
  await api.createPermission('group', 'client/id', input, revision)
  const root = '/kestra/api/v1/main/extensions/rbac/application-groups/group/applications/' + encodeKey('client/id') + '/permissions'
  assert.deepEqual(calls.map(call => call.path), [root + '/-/mutation-metadata', root])
  assert.equal(calls[0].cache, 'no-store')
  assert.equal(calls[1].headers['If-Match'], '"7"')
  assert.deepEqual(JSON.parse(calls[1].body).scopeKeys, ['known'])
  assert(calls.every(call => !call.path.includes('/scopes') && !call.path.includes('/users')))
})

test('T9: local policy name edit preserves typed content and scope associations without identity directory', () => {
  const original = {key: 'policy', name: 'Original', scopeKeys: ['known'], enabled: true, description: '', grants: [{scope: 'NAMESPACE', tenant: 'main', namespace: 'exact', descendants: false, resource: 'FLOW', actions: ['READ', 'LIST'], ids: [], allIds: true}], managementGrants: [], revision: 3, implementationKind: 'kestra-policy'}
  const draft = reactive(permissionDraft(original, 'kestra-policy'))
  draft.name = 'Changed'
  const input = permissionInput(draft, 'kestra-policy', permissionScopeKeys(draft.scopeKeys, [], false))
  const typed = typedGrantsInput(input)
  assert.deepEqual(input.scopeKeys, original.scopeKeys)
  assert.deepEqual(typed.grants, original.grants)
  assert.deepEqual(typed.managementGrants, original.managementGrants)
  assert.equal(original.name, 'Original')
  for (const keys of [['INVALID'], ['same', 'same'], [''], [null]]) assert.throws(() => permissionScopeKeys(keys, [], false), /invalid_scope/)
  assert.throws(() => permissionScopeKeys(['unknown'], [{key: 'known'}], true), /invalid_scope/)
  assert.deepEqual(permissionScopeKeys([], [], false), [])
})

test('T9: metadata failure never invents revision or retries and server denial cannot imply identity manage', async () => {
  let reads = 0
  const denied = Object.assign(new Error('access_denied'), {status: 403})
  await assert.rejects(permissionParentRevision({permissionMutationMetadata: async () => {reads++; throw denied}}, 'g', 'client'), error => error === denied)
  assert.equal(reads, 1)
  for (const receipt of [{clientId: 'wrong', revision: 1, actions: ['MANAGE']}, {clientId: 'client', revision: 0, actions: ['MANAGE']}, {clientId: 'client', revision: 1, actions: ['READ']}, null]) {
    await assert.rejects(permissionParentRevision({permissionMutationMetadata: async () => receipt}, 'g', 'client'), /invalid_mutation_metadata/)
  }
})

test('T9/T10: picker has one explicit wrapper and local-only form selects known-key mode without leaking names', async () => {
  const source = file => readFile(new URL('../src/' + file, import.meta.url), 'utf8')
  const picker = await source('ScopePicker.vue'), app = await source('App.vue'), fields = await source('PermissionFields.vue')
  assert.match(picker, /<div data-test="scope-picker">[\s\S]*<KsSelect v-else/)
  assert.match(picker, /<KsInput[^>]*data-test="scope-known-keys"/)
  assert.match(fields, /:known-keys-only="knownScopeKeysOnly"/)
  assert.match(app, /:known-scope-keys-only="!identityRead"/)
  const creation = app.slice(app.indexOf('async function newPermission()'), app.indexOf('async function openSubject('))
  assert.match(creation, /if \(!identityRead.value\)/)
  assert.match(creation, /permissionParentRevision\(api, groupId.value, application.value.clientId\)/)
  assert.doesNotMatch(creation, /applicationGroupScopes|api.users|api.applicationGroup\(/)
  assert.match(app, /const permissionManage = computed\(\(\) => permissionRead.value &&/)
  assert.match(app, /<a v-if="denied \|\| reauthenticate" :href="loginHref" data-test="sign-in-again"/)
})

test('T9: account switching uses published logout with a safe deployment prefix, not a UI redirect', () => {
  assert.equal(loginEntry('/api/v1/main/'), '/oidc/logout')
  assert.equal(loginEntry('/deployment/base/api/v1/main'), '/deployment/base/oidc/logout')
  for (const path of ['//external/api/v1/main', '/base/../api/v1/main', '/base/%2f/api/v1/main', '/base\\evil/api/v1/main', '/api/v1/main?redirect=evil', '/api/v1/main#fragment']) assert.throws(() => loginEntry(path), /invalid_api_base/)
})
