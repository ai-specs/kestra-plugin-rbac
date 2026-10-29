import test from 'node:test'
import assert from 'node:assert/strict'
import {createMemoryHistory, createRouter} from 'vue-router'
import {readFile} from 'node:fs/promises'
import {clearListQuery, canonicalListPage, readListQuery, matchesChips} from '../src/access-control-model.js'

test('T4: clear recovers invalid size and subject queries without retaining malformed input', async () => {
  const router = createRouter({history: createMemoryHistory(), routes: [{path: '/:pathMatch(.*)*', component: {}}]})
  for (const query of [{size: '0'}, {size: '17'}, {size: ['25', '100']}, {filterSubject: ''}, {filterSubject: ['a', 'b']}]) {
    await router.replace({path: '/application-groups/g/applications/client', query})
    assert.throws(() => readListQuery(router.currentRoute.value.query), /invalid_query/)
    await router.replace({path: router.currentRoute.value.path, query: clearListQuery(router.currentRoute.value.query)})
    assert.deepEqual(router.currentRoute.value.query, {})
    assert.equal(readListQuery(router.currentRoute.value.query).size, 25)
  }
  assert.deepEqual(clearListQuery({size: '50', permissionKey: 'p', page: '3'}), {size: '50'})
})

test('T4: deleting the sole final-page row replaces canonical page and preserves legal filters and size', async () => {
  const router = createRouter({history: createMemoryHistory(), routes: [{path: '/:pathMatch(.*)*', component: {}}]})
  const query = {page: '2', size: '25', scopeKeys: ['a', 'b'], filter: '{"field":"name","value":"Read"}'}
  await router.push('/application-groups/g')
  await router.push({path: '/application-groups/g/applications/client', query})
  assert.equal(canonicalListPage(router.currentRoute.value.query, 'permissions', 26), null)
  const next = canonicalListPage(router.currentRoute.value.query, 'permissions', 25)
  await router.replace({path: router.currentRoute.value.path, query: next})
  assert.equal(router.currentRoute.value.query.page, undefined)
  assert.equal(router.currentRoute.value.query.size, '25')
  assert.deepEqual(router.currentRoute.value.query.scopeKeys, ['a', 'b'])
  assert.equal(matchesChips({name: 'Read Flow', scopeKeys: ['b']}, readListQuery(router.currentRoute.value.query).chips), true)
  assert.deepEqual(canonicalListPage({page: '5', size: '10', permissionKey: 'p'}, 'permissions', 21), {page: '3', size: '10', permissionKey: 'p'})
  assert.deepEqual(canonicalListPage({page: '2', size: '25'}, 'permissions', 0), {size: '25'})
  await new Promise(resolve => {const remove = router.afterEach(() => {remove(); resolve()}); router.back()})
  assert.equal(router.currentRoute.value.path, '/application-groups/g')
})

test('T4: App statically validates list query before capabilities or subject reads and exposes query recovery', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const start = app.indexOf('async function readView('), end = app.indexOf('async function mutate(')
  assert(start >= 0 && end > start)
  const load = app.slice(start, end)
  const snapshot = load.indexOf('const query = router.resolve('), validate = load.indexOf('readListQuery(query, queryKind)')
  const native = load.indexOf('nativeChips(decodeSearchParams(query), queryKind)')
  const capabilities = load.indexOf('await api.capabilities('), subject = load.indexOf('await api.scopeGrant(')
  assert([snapshot, validate, native, capabilities, subject].every(index => index >= 0))
  assert(snapshot < validate && validate < native && native < capabilities && native < subject)
  assert.match(app, /problem.code === 'invalid_query'/)
  assert.match(app, /v-if="invalidQuery"[^>]*@click="clearInvalidQuery"/)
  assert.match(app, /context.navigate\(target, \{replace: true\}\)/)
})
