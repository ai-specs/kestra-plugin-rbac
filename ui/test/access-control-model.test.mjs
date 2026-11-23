import test from 'node:test'
import assert from 'node:assert/strict'
import {compareKeys, supportedScopes, involvedApplications, permissionSummary, permissionSummaryEntries, compactPermissionGrants, matchesChips, readListQuery, writeListQuery, paginate, selectionAfterDelete, countValue, permissionInput, detailTabs, collectPermissions, nativeChips, normalizeDeepLinkQuery, normalizeDeepLinkLocation} from '../src/access-control-model.js'

test('permission ranges preserve exact expression and child flag; explanation is separate', () => {
  for (const namespace of ['company', '!company']) for (const descendants of [false, true]) {
    const permission = {implementationKind:'kestra-policy',grants:[{scope:'NAMESPACE',tenant:'main',namespace,descendants,resource:'FLOW',actions:['READ','LIST'],allIds:false,ids:['a']}]}
    const before = JSON.stringify(permission)
    const translate = (key, args) => `${key}: ${args.namespace}`
    const [entry] = permissionSummaryEntries(permission, translate)
    assert.equal(entry.text, `main · ${namespace} · descendants=${descendants} · FLOW · READ / LIST · a`)
    assert.equal(entry.range, `${namespace} · descendants=${descendants}`)
    assert.equal(entry.explanation, namespace.startsWith('!') ? `namespaceComplement${descendants ? 'Tree' : 'Exact'}: company` : undefined)
    assert.deepEqual(permissionSummary(permission, translate), [entry.text])
    assert.equal(JSON.stringify(permission), before)
  }
})

test('permission summary compacts equal prefixes without changing grants or cross products', () => {
  const grant = (resource, actions, ids, extra = {}) => ({scope:'NAMESPACE',tenant:'main',namespace:'company',descendants:true,resource,actions,ids,allIds:false,...extra})
  const source = [grant('FLOW',['READ'],['a']),grant('FLOW',['READ'],['b']),grant('KVSTORE',['READ'],['a','b']),grant('FLOW',['LIST'],['a','b']),grant('KVSTORE',['LIST'],['a','b'])]
  const before = JSON.stringify(source)
  const compact = compactPermissionGrants(source)
  assert.equal(compact.length, 1)
  assert.deepEqual(compact[0].resources, ['FLOW','KVSTORE'])
  assert.deepEqual(new Set(compact[0].actions), new Set(['READ','LIST']))
  assert.deepEqual(compact[0].ids, ['a','b'])
  assert.equal(JSON.stringify(source), before)
  const correlated = [grant('FLOW',['READ'],['a']),grant('KVSTORE',['UPDATE'],['b'])]
  assert.equal(compactPermissionGrants(correlated).length, 2)
  for (const extra of [{namespace:'!company'},{descendants:false},{tenant:'other'},{namespace:'company.sub'}]) {
    assert.equal(compactPermissionGrants([grant('FLOW',['READ'],['a']),grant('FLOW',['READ'],['b'],extra)]).length, 2)
  }
  assert.equal(compactPermissionGrants([grant('FLOW',['READ'],['a']),grant('FLOW',['READ'],[],{allIds:true})])[0].allIds, true)
})

test('every small resource/action/ID relation is preserved exactly by display compaction', () => {
  const atoms = ['FLOW','EXECUTION'].flatMap(resource => ['READ','LIST'].flatMap(action => ['a','b'].map(id => ({
    scope:'NAMESPACE',tenant:'main',namespace:'!company',descendants:true,resource,actions:[action],ids:[id],allIds:false
  }))))
  const relation = rows => [...new Set(rows.flatMap(row => (row.resources ?? [row.resource]).flatMap(resource =>
    row.actions.flatMap(action => row.ids.map(id => JSON.stringify([resource,action,id]))))))].sort()
  for (let mask = 1; mask < 256; mask++) {
    const grants = atoms.filter((_, index) => mask & (1 << index))
    assert.deepEqual(relation(compactPermissionGrants(grants)), relation(grants), `relation mask ${mask}`)
  }
})

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
test('N12–N16/T4: deep links normalize into native params so chips match UI operations; pagination preserves native filters', () => {
  assert.deepEqual(normalizeDeepLinkQuery({permissionKey: 'read'}, 'permissions'), {'filters[key][EQUALS]': 'read'})
  assert.deepEqual(normalizeDeepLinkQuery({scopeKeys: 's1'}, 'permissions'), {'filters[scopeKeys][EQUALS]': 's1'})
  assert.deepEqual(normalizeDeepLinkQuery({scopeKeys: ['s1', 's2']}, 'permissions'), {'filters[scopeKeys][IN]': 's1,s2'})
  assert.deepEqual(normalizeDeepLinkQuery({filterSubject: 'user+中'}, 'subjects'), {'filters[subject][EQUALS]': 'user+中'})
  assert.equal(normalizeDeepLinkQuery({permissionKey: 'read', filterSubject: 'u'}, 'permissions'), null)
  assert.deepEqual(readListQuery({}), {page: 1, size: 25, filterSubject: undefined})
  assert.deepEqual(writeListQuery({page: 2, size: 50}, {'filters[key][EQUALS]': 'read'}), {'filters[key][EQUALS]': 'read', page: '2', size: '50'})
  assert.deepEqual(writeListQuery({page: 1, size: 25}, {'filters[key][EQUALS]': 'read', page: '3'}), {'filters[key][EQUALS]': 'read'})
})
test('N19/T4: invalid and retired vocabularies fail visibly, not broadened silently', () => {
  for (const query of [{page: '0'}, {page: '1.2'}, {page: ['1', '2']}, {size: '20'}, {scopeKeys: ['a', 'a']}, {scopeKeys: ['../']}, {permissionKey: 'A'}, {filter: '{'}, {filter: '{"field":"unknown","value":"a"}'}, {filter: '{"field":"key","value":"p"}'}]) assert.throws(() => readListQuery(query), /invalid_query/)
})
test('N12/T4: fresh-mount location normalization rewrites list deep links before any read', () => {
  const base = '/ui/main/extensions/rbac'
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups/g/applications/~abc?scopeKeys=s1&size=50'), base + '/application-groups/g/applications/~abc?size=50&' + encodeURIComponent('filters[scopeKeys][EQUALS]') + '=s1')
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups/g/applications/~abc?scopeKeys=s1&scopeKeys=s2'), base + '/application-groups/g/applications/~abc?' + encodeURIComponent('filters[scopeKeys][IN]') + '=s1%2Cs2')
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups/g/scope-grants?filterSubject=u'), base + '/application-groups/g/scope-grants?' + encodeURIComponent('filters[subject][EQUALS]') + '=u')
  assert.equal(normalizeDeepLinkLocation(base + '/users?filterSubject=u&page=2'), base + '/users?page=2&' + encodeURIComponent('filters[subject][EQUALS]') + '=u')
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups/g/applications/~abc?permissionKey=k&filterSubject=u'), null)
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups'), null)
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups/g'), null)
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups/g/scopes/s'), null)
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups/g/applications/~abc?permissionKey=BAD!'), null)
  assert.equal(normalizeDeepLinkLocation(base + '/application-groups/g/applications/~abc'), null)
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
