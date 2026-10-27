import test from 'node:test'
import assert from 'node:assert/strict'
import {createMemoryHistory, createRouter} from 'vue-router'
import {deepestActiveElement, restoreFocus} from '../src/focus.js'
import {createDraftNavigationGuard} from '../src/draft-navigation.js'
import {readListQuery, writeListQuery, matchesChips} from '../src/access-control-model.js'
test('N17/T10: focus walks complete shadow chain and restores persistent fallback after trigger removal', () => {
  let focused = ''
  const button = {isConnected:true, focus:()=>{focused='trigger'}}
  const root = {activeElement:{shadowRoot:{activeElement:{shadowRoot:{activeElement:button}}}}, querySelector:selector => selector==='[data-focus-fallback]' ? {focus:()=>{focused='sidebar'}} : null}
  assert.equal(deepestActiveElement(root),button)
  restoreFocus(button,root);assert.equal(focused,'trigger')
  button.isConnected=false;restoreFocus(button,root);assert.equal(focused,'sidebar')
  restoreFocus(null,{querySelector:selector=>selector==='[data-test="access-control-root"]'?{focus:()=>{focused='main'}}:null});assert.equal(focused,'main')
})
test('N17/T10: real Vue router navigation defers dirty route changes, cancel retains route, saving blocks', async () => {
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{}}]})
  let dirty=false,saving=false,answer
  const remove=router.beforeEach(createDraftNavigationGuard(()=>({dirty,saving}),resolve=>{answer=resolve}))
  await router.push('/applications/a');dirty=true
  const cancelled=router.push('/scopes/b');await new Promise(resolve=>setTimeout(resolve,0))
  assert.equal(router.currentRoute.value.path,'/applications/a');answer(false);await cancelled
  assert.equal(router.currentRoute.value.path,'/applications/a')
  const confirmed=router.push('/scopes/b');await new Promise(resolve=>setTimeout(resolve,0));dirty=false;answer(true);await confirmed
  assert.equal(router.currentRoute.value.path,'/scopes/b')
  saving=true;await router.push('/users');assert.equal(router.currentRoute.value.path,'/scopes/b');remove()
})
test('N12/N15/T4: real memory-router copy and back preserve manual/deep-link conjunction; not a browser history assertion', async () => {
  const create=()=>createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{}}]})
  const router=create()
  const initial={permissionKey:'p',filterSubject:'test-account',scopeKeys:['a','b']}
  await router.push({path:'/applications/client',query:initial})
  const state=readListQuery(router.currentRoute.value.query)
  state.chips.push({field:'name',value:'Read',source:'manual'})
  await router.push({path:router.currentRoute.value.path,query:writeListQuery(state)})
  const copied=create();await copied.push(router.currentRoute.value.fullPath)
  assert.deepEqual(readListQuery(copied.currentRoute.value.query),readListQuery(router.currentRoute.value.query))
  assert.equal(matchesChips({key:'p',name:'Read',scopeKeys:['b']},readListQuery(copied.currentRoute.value.query).chips),true)
  assert.equal(matchesChips({key:'p',name:'Write',scopeKeys:['b']},readListQuery(copied.currentRoute.value.query).chips),false)
  await new Promise(resolve=>{const remove=router.afterEach(()=>{remove();resolve()});router.back()})
  assert.deepEqual(router.currentRoute.value.query,initial)
})
