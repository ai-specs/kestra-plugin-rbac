import test from 'node:test'
import assert from 'node:assert/strict'
import {createClient} from '../src/client.js'

test('management requests preserve deployment prefix and distinct policy and account revisions', async () => {
  const calls = []
  const context = {apiBase: '/kestra/api/v1/main', request: async (url, options) => {
    calls.push({url, options})
    return {ok: true, json: async () => ({revision: 23})}
  }}
  const client = createClient(() => context)
  await client.updateRole('stable-role', {id: 'stable-role', description: 'changed'}, 19)
  await client.updateProfile({subject: 'name/用户名', name: 'Changed', email: ''}, 0)
  await client.user('name/用户名')
  assert.equal(calls[0].url, '/kestra/api/v1/main/extensions/rbac/roles/stable-role')
  assert.equal(calls[0].options.headers['If-Match'], '"19"')
  assert.equal(calls[1].options.headers['If-Match'], '"0"')
  assert.equal(JSON.parse(calls[1].options.body).subject, 'name/用户名')
  assert.equal(calls[2].url, '/kestra/api/v1/main/extensions/rbac/users/detail?subject=' + encodeURIComponent('name/用户名'))
})

test('conflicts retain status and stable code without retrying a mutation', async () => {
  let writes = 0
  const client = createClient({apiBase: '/api/v1/main', request: async () => {
    writes++
    return {ok: false, status: 412, json: async () => ({code: 'revision_conflict'})}
  }})
  await assert.rejects(client.bindings('user', ['reader'], 4), error => error.status === 412 && error.code === 'revision_conflict')
  assert.equal(writes, 1)
})

test('lifecycle updates use the current host request function and API base', async () => {
  let current = {apiBase: '/old/api/v1/main', request: () => {throw new Error('stale request')}}
  const client = createClient(() => current)
  current = {apiBase: '/new/api/v1/main', request: async url => {
    assert.equal(url, '/new/api/v1/main/extensions/rbac/capabilities')
    return {ok: true, json: async () => ({allowed: false})}
  }}
  assert.deepEqual(await client.capabilities(), {allowed: false})
})
