import test from 'node:test'
import assert from 'node:assert/strict'
import {newOneTimeValue, clearOneTimeValue, revealOneTimeValue, copyOneTimeValue, credentialDisplay} from '../src/credential-state.js'
import {createClient, encodeKey} from '../src/client.js'
test('T7: late clipboard attempts cannot overwrite retry status or a replaced one-time value', async () => {
  const state = newOneTimeValue()
  revealOneTimeValue(state, 'test-only-first', 'client', 'secret')
  let rejectFirst
  const first = copyOneTimeValue(state, {writeText: () => new Promise((resolve, reject) => {rejectFirst = reject})})
  assert.equal(await copyOneTimeValue(state, {writeText: async () => {}}), true)
  rejectFirst(new Error('old denied attempt'))
  assert.equal(await first, false); assert.equal(state.copied, true); assert.equal(state.copyFailed, false)
  let finish
  const pending = copyOneTimeValue(state, {writeText: () => new Promise(resolve => {finish = resolve})})
  assert.equal(state.copied, false)
  revealOneTimeValue(state, 'test-only-replacement', 'other', 'secret'); finish()
  assert.equal(await pending, false); assert.equal(state.copied, false); assert.equal(state.copyFailed, false)
  clearOneTimeValue(state)
  assert.equal(state.value, '')
})
test('N5/N11/T5/T7: one-time value clears on close; copy cannot resurrect it after close', async () => {
  const state = newOneTimeValue()
  revealOneTimeValue(state, 'sensitive-test-only', 'test-account', 'secret')
  let finish
  const pending = copyOneTimeValue(state, {writeText: value => {assert.equal(value, 'sensitive-test-only'); return new Promise(resolve => {finish = resolve})}})
  clearOneTimeValue(state); finish()
  assert.equal(await pending, false)
  assert.equal(state.value, ''); assert.equal(state.subject, ''); assert.equal(state.kind, '')
  assert.equal(await copyOneTimeValue(state, {writeText: () => {throw new Error('must not run')}}), false)
})
test('N11/T7: copy failure retries only while modal is live; reveal replaces prior plaintext', async () => {
  const state = newOneTimeValue()
  revealOneTimeValue(state, 'old-test-only', 'a', 'password')
  assert.equal(await copyOneTimeValue(state, {writeText: async () => {throw new Error('denied')}}), false)
  assert.equal(state.copyFailed, true)
  assert.equal(await copyOneTimeValue(state, {writeText: async () => {}}), true)
  revealOneTimeValue(state, 'new-test-only', 'b', 'secret')
  assert.equal(state.value, 'new-test-only'); assert.equal(state.copied, false); assert.equal(state.copyFailed, false)
  clearOneTimeValue(state)
})
test('N11/T7/T8: never fabricate legacy hints and never rotate public or maintenance client online', () => {
  assert.deepEqual(credentialDisplay({clientType: 'public', rotationMode: 'none'}), {key: 'noSecret', hint: '', canRotate: false})
  const value = {clientType: 'confidential', credentialRevision: 4, secretSet: true, rotationMode: 'online'}
  assert.deepEqual(credentialDisplay(value), {key: 'secretHintUnavailable', hint: '', canRotate: true})
  assert.deepEqual(credentialDisplay({...value, maskedHint: 'abc••••••••wxyz', rotationMode: 'deployment-maintenance'}), {key: '', hint: 'abc••••••••wxyz', canRotate: false})
  assert.equal(credentialDisplay({...value, rotationMode: 'unknown'}).canRotate, false)
})
test('N5/N11/N19/T5/T7: candidate account and credential routes use strong revision, no caller password, no body on rotation and no retries', async () => {
  const calls = []
  const api = createClient({apiBase: '/api/v1/main', request: async (url, options) => {calls.push({url, ...options}); return {ok: true, status: 204, json: async () => {throw new Error('no body')}}}})
  await api.createUser({username: 'test-account', name: '', email: '', password: 'must-not-send'})
  await api.deleteUser('test/account', 3)
  await api.credentials('g', 'client/id')
  await api.rotateCredentials('g', 'client/id', 9)
  assert.deepEqual(JSON.parse(calls[0].body), {username: 'test-account', name: '', email: ''})
  assert.equal(calls[0].cache, 'no-store')
  assert.equal(calls[1].url, '/api/v1/main/extensions/rbac/users/detail?subject=test%2Faccount')
  assert.equal(calls[1].headers['If-Match'], '"3"')
  assert.ok(calls[2].url.endsWith('/applications/' + encodeKey('client/id') + '/credentials'))
  assert.equal(calls[3].body, undefined); assert.equal(calls[3].headers['If-Match'], '"9"')
  assert.equal(calls[3].method, 'POST')
  assert.equal(calls[3].cache, 'no-store')
})
