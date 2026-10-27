import test from 'node:test'
import assert from 'node:assert/strict'
import {compareKeys, supportedScopes, involvedApplications, permissionSummary, matchesChips, readListQuery, writeListQuery, paginate, selectionAfterDelete, countValue, permissionInput, detailTabs, collectPermissions, nativeChips} from '../src/access-control-model.js'

test('N1/N6: four detail tabs, server counts never infer zero', () => {
  assert.deepEqual(detailTabs, ['overview', 'scopes', 'applications', 'scope-grants'])
  assert.equal(countValue(null, 'subjects'), null)
  assert.equal(countValue({subjects: 0}, 'subjects'), 0)
  assert.equal(countValue({subjects: -1}, 'subjects'), null)
})
test('N10–N14/T3: support is a union including disabled and empty grants', () => {
  const p1 = {scopeKeys: ['s1', 's2']}, p2 = {scopeKeys: ['s2'], enabled: false, grants: []}
  assert.deepEqual(supportedScopes([p1, p2]), ['s1', 's2'])
  p1.scopeKeys = ['s1']
  assert.deepEqual(supportedScopes([p1, p2]), ['s1', 's2'])
  assert.deepEqual(supportedScopes([p1]), ['s1'])
  assert.deepEqual(supportedScopes([{scopeKeys: []}]), [])
})
test('N15/T4: involved applications use overlap, exclude disabled subject and inactive app', () => {
  const apps = [{clientId: 'z', permissions: [{scopeKeys: ['a', 'b']}]}, {clientId: 'x', permissions: [{scopeKeys: ['c']}]}, {clientId: 'y', active: false, permissions: [{scopeKeys: ['b']}]}]
  assert.deepEqual(involvedApplications({scopeKeys: ['b']}, apps).map(a => a.clientId), ['z'])
  assert.deepEqual(involvedApplications({scopeKeys: ['b'], active: false}, apps), [])
  assert.deepEqual(involvedApplications({scopeKeys: []}, apps), [])
})
test('N12/N15/T4: scope values ANY, chips AND including repeated same field; case-sensitive names', () => {
  const item = {key: 'p', name: 'Read Flow', scopeKeys: ['a', 'b']}
  assert.equal(matchesChips(item, [{field: 'scopeKeys', values: ['x', 'a']}, {field: 'scopeKeys', values: ['b']}]), true)
  assert.equal(matchesChips(item, [{field: 'scopeKeys', values: ['a']}, {field: 'scopeKeys', values: ['x']}]), false)
  assert.equal(matchesChips(item, [{field: 'name', value: 'read'}]), false)
  assert.equal(matchesChips(item, [{field: 'name', value: 'Read'}]), true)
  assert.equal(matchesChips(item, [{field: 'key', value: 'p'}, {field: 'key', value: 'q'}]), false)
})
test('N12–N16/T4: query round-trip, source and manual chips coexist without preference storage', () => {
  const input = {permissionKey: 'read', filterSubject: 'user+中', scopeKeys: ['s1', 's2'], filter: ['{"field":"name","value":"Read"}'], page: '2', size: '50'}
  assert.deepEqual(writeListQuery(readListQuery(input)), input)
  const state = readListQuery(input)
  state.chips = state.chips.filter(chip => chip.field !== 'scopeKeys')
  assert.equal(writeListQuery(state).filterSubject, undefined)
  assert.deepEqual(readListQuery({}), {page: 1, size: 25, chips: [], filterSubject: undefined})
})
test('N19/T4: invalid queries fail visibly, not broadened silently', () => {
  for (const query of [{page: '0'}, {page: '1.2'}, {page: ['1', '2']}, {size: '20'}, {scopeKeys: ['a', 'a']}, {scopeKeys: ['../']}, {permissionKey: 'A'}, {filter: '{'}, {filter: '{"field":"unknown","value":"a"}'}]) assert.throws(() => readListQuery(query), /invalid_query/)
})
test('N4/N12/N15/T4: filtered total, last-page clamp and code point selection', () => {
  const items = Array.from({length: 26}, (_, i) => i)
  assert.deepEqual(paginate(items, 2), {items: [25], total: 26, page: 2, size: 25})
  assert.equal(paginate(items.slice(0, 25), 2).page, 1)
  assert.equal(selectionAfterDelete([{key: 'a'}, {key: 'c'}, {key: 'e'}], 'c'), 'e')
  assert.equal(selectionAfterDelete([{key: 'a'}, {key: 'c'}], 'c'), 'a')
  assert.equal(selectionAfterDelete([{key: 'a'}], 'a'), '')
  assert.ok(compareKeys('\uE000', '😀') < 0)
})
test('N13/N14/T3: external content remains text; caller cannot switch implementation kind', () => {
  const draft = {key: 'p', name: 'Admin', scopeKeys: ['s1', 's2'], details: '<script>admin</script>', implementationKind: 'kestra-policy', grants: [{resource: 'FLOW'}]}
  assert.deepEqual(permissionInput(draft, 'external-declaration', ['s1', 's2']), {key: 'p', name: 'Admin', scopeKeys: ['s1', 's2'], details: '<script>admin</script>'})
  assert.throws(() => permissionInput(draft, 'unknown', ['s1', 's2']))
  assert.throws(() => permissionInput(draft, 'external-declaration', ['s1']))
  assert.deepEqual(permissionSummary({...draft, implementationKind: 'external-declaration'}), ['<script>admin</script>'])
  assert.deepEqual(permissionSummary({implementationKind: 'unknown', details: 'admin'}), [])
})
test('N12/N19/T4: permission pages reject drifting revisions, duplicate keys and incomplete snapshots', async () => {
  const result = {items: [{key: 'p1', parentRevision: 4}], total: 2, page: 1, size: 100, parentRevision: 4}
  assert.deepEqual(await collectPermissions(async page => page === 1 ? result : {...result, page, items: [{key: 'p2', parentRevision: 4}]}, undefined, 4), [{key: 'p1', parentRevision: 4}, {key: 'p2', parentRevision: 4}])
  for (const second of [{...result, page: 2, parentRevision: 5}, {...result, page: 2}, {...result, page: 2, items: []}]) await assert.rejects(collectPermissions(async page => page === 1 ? result : second, undefined, 4), /application_changed/)
  await assert.rejects(collectPermissions(async () => result, AbortSignal.abort(), 4), {name: 'AbortError'})
})
test('N12/N15/T4: native decoded parameters keep AND semantics, scope arrays use ANY and OR fails visibly', () => {
  const item={key:'p',name:'Read flow',scopeKeys:['a','b']}
  const parsed=[{field:'scopeKeys',operation:'EQUALS',value:['x','a']},{field:'scopeKeys',operation:'EQUALS',value:['b']},{field:'name',operation:'CONTAINS',value:'Read'}]
  assert.equal(matchesChips(item,nativeChips(parsed,'permissions')),true)
  assert.equal(matchesChips(item,nativeChips([...parsed,{field:'key',operation:'EQUALS',value:'other'}],'permissions')),false)
  for(const change of [{topLogical:'OR'},{wrapperLogical:'OR'},{operation:'UNKNOWN'},{field:'unknown'}]) assert.throws(()=>nativeChips([{...parsed[0],...change}],'permissions'),/invalid_query/)
})
