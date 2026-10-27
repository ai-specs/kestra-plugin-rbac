import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {contextFullPath, createNavigationHooks} from '../src/navigation-lifecycle.js'

test('formal SDK: fullPath preserves canonical query and path compatibility never loses repeated data', () => {
  assert.equal(contextFullPath({path:'items', fullPath:'items?scopeKeys=a&scopeKeys=b&flag&empty='}), 'items?scopeKeys=a&scopeKeys=b&flag&empty=')
  assert.equal(contextFullPath({path:'users'}),'users')
  assert.equal(contextFullPath({path:'items',query:{scopeKeys:['a','b']}}),'items?scopeKeys=a&scopeKeys=b')
  assert.equal(contextFullPath({path:'items',query:{flag:null,empty:'',opaque:'a/b#c'}}),'items?flag&empty=&opaque=a/b%23c')
})
test('formal SDK: concurrent leave, errors and non-true results fail closed', async () => {
  const hooks=createNavigationHooks();let finish
  hooks.guard=()=>new Promise(resolve=>{finish=resolve})
  const first=hooks.beforeNavigate({extensionPath:null});await Promise.resolve()
  assert.equal(await hooks.beforeNavigate({extensionPath:'users'}),false)
  finish(true);assert.equal(await first,true)
  for(const value of [false,undefined,'true']){hooks.guard=()=>value;assert.equal(await hooks.beforeNavigate({}),false)}
  hooks.guard=()=>{throw Error('failed')};assert.equal(await hooks.beforeNavigate({}),false)
})
test('formal SDK: abort disposal resolves pending confirmation false and cleanup runs once', async () => {
  const hooks=createNavigationHooks();let cleanup=0
  hooks.guard=()=>new Promise(()=>{});hooks.cleanup=()=>{cleanup++}
  const pending=hooks.beforeNavigate({});await Promise.resolve();hooks.dispose();hooks.dispose()
  assert.equal(await pending,false);assert.equal(cleanup,1);assert.equal(await hooks.beforeNavigate({}),false)
})
test('formal SDK: lifecycle uses only published overlay contract and owned renderer', async () => {
  const source=await readFile(new URL('../src/lifecycle.js',import.meta.url),'utf8')
  assert.match(source,/\.integration\/ui-sdk\/shadow-overlays.mjs/)
  assert.match(source,/createShadowRenderer\([\s\S]*idInjectionKey: ID_INJECTION_KEY/)
  assert.match(source,/shadowOverlayComponents\(Vue,/)
  assert.match(source,/overlayScope\?\.dispose\(\)/)
  assert.match(source,/beforeNavigate: target/)
  const contract=JSON.parse(await readFile(new URL('../../.integration/ui-sdk/contract.json',import.meta.url),'utf8'))
  assert.equal(contract.overlayCompatibility.status,'supported')
  assert.deepEqual(contract.overlayCompatibility.components,['KsDrawer','KsDialog','KsSelect','KsFilter'])
  assert.equal(contract.candidateOverlayCompatibility,undefined)
})
