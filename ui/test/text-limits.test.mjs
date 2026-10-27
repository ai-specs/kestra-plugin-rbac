import test from 'node:test'
import assert from 'node:assert/strict'
import {groupMetadataInput, withinTextLimit} from '../src/text-limits.js'
import {groupScopeInput, memberInput} from '../src/application-group-model.js'
import {permissionInput} from '../src/access-control-model.js'

const emoji = count => '😀'.repeat(count)
test('N3/N7/N8/T1/T2: group and scope metadata accept exact Unicode code point limits and reject overflow', () => {
  const group = {displayName: emoji(256), description: emoji(4096)}
  assert.deepEqual(groupMetadataInput(group), group)
  assert.deepEqual(groupScopeInput({key: 'scope', name: group.displayName, description: group.description}), {key: 'scope', name: group.displayName, description: group.description})
  for (const changes of [{displayName: emoji(257)}, {description: emoji(4097)}, {displayName: ' '}]) assert.throws(() => groupMetadataInput({...group, ...changes}), /invalid_group_metadata/)
  for (const changes of [{name: emoji(257)}, {description: emoji(4097)}, {name: ''}]) assert.throws(() => groupScopeInput({key: 'scope', name: group.displayName, description: group.description, ...changes}), /invalid_scope/)
  assert.equal(groupMetadataInput({displayName: 'a' + emoji(255), description: ''}).displayName, 'a' + emoji(255))
})
test('N11/N13/N14/T3: application and permission descriptions/details preserve supplementary characters at legal boundaries', () => {
  const application = {clientId: 'client', description: emoji(4096), audiences: []}
  assert.deepEqual(memberInput(application), application)
  assert.throws(() => memberInput({...application, description: emoji(4097)}), /invalid_client_metadata/)
  const external = {key: 'permission', name: emoji(256), scopeKeys: [], details: emoji(16384)}
  assert.deepEqual(permissionInput(external, 'external-declaration', []), external)
  assert.throws(() => permissionInput({...external, details: emoji(16385)}, 'external-declaration', []), /invalid_permission/)
  assert.throws(() => permissionInput({...external, name: emoji(257)}, 'external-declaration', []), /invalid_permission/)
  const policy = {key: 'permission', name: emoji(256), scopeKeys: [], enabled: true, description: emoji(4096), grants: [], managementGrants: []}
  assert.deepEqual(permissionInput(policy, 'kestra-policy', []), policy)
  assert.throws(() => permissionInput({...policy, description: emoji(4097)}, 'kestra-policy', []), /invalid_permission/)
})
test('N3/N7/N13: ASCII machine key boundaries remain unchanged and text types are checked before counting', () => {
  assert.equal(groupScopeInput({key: 'a'.repeat(64), name: 'Name', description: ''}).key.length, 64)
  assert.throws(() => groupScopeInput({key: 'a'.repeat(65), name: 'Name', description: ''}), /invalid_scope/)
  const permission = {key: 'a'.repeat(128), name: 'Name', scopeKeys: [], details: ''}
  assert.equal(permissionInput(permission, 'external-declaration', []).key.length, 128)
  assert.throws(() => permissionInput({...permission, key: 'a'.repeat(129)}, 'external-declaration', []), /invalid_permission/)
  for (const value of [null, undefined, 256, [], {}]) assert.equal(withinTextLimit(value, 256), false)
})
