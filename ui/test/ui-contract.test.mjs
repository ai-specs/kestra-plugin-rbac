import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
const source = path => readFile(new URL('../src/' + path, import.meta.url), 'utf8')
test('T10: native business table rows use immutable entity keys rather than positional DOM identity', async () => {
  const list = await source('AccessList.vue'), sidebar = await source('AccessSidebar.vue'), cards = await source('ApplicationGroupCards.vue')
  assert.match(list, /<KsTable[^>]*:row-key="kind === 'permissions' \? 'key' : 'subject'"/)
  assert.match(sidebar, /v-for="item in result.items" :key="item\[field\]"/)
  assert.match(cards, /v-for="item in cards" :key="item.id"/)
})
test('N5/N11/T5/T7: candidate app wires account actions and one-time modal without credential attributes or storage', async () => {
  const app=await source('App.vue'), modal=await source('OneTimeCredential.vue'), actions=await source('account-actions.js'), state=await source('credential-state.js')
  assert.match(app, /<AccountList/);assert.match(app, /createAccount\(api/);assert.match(app, /api\.deleteUser/)
  assert.match(app, /<OneTimeCredential :state="oneTime"/);assert.match(app, /<CredentialsPanel/)
  assert.match(modal, /class="rbac-one-time-value" data-sensitive-credential tabindex="0">{{ state.value }}/)
  for(const text of [modal,actions,state]) {
    assert.doesNotMatch(text,/console\.|localStorage|sessionStorage|location\./)
    assert.doesNotMatch(text, /(?:data-[\w-]+|title|aria-label)="[^"\n]*state\.value/)
  }
})
test('N17/T10/T11: 390px rules and persistent shadow-root focus targets exist, not browser style evidence', async () => {
  const css=await source('style.css'), drawer=await source('EditorDrawer.vue'), lifecycle=await source('lifecycle.js')
  assert.match(css,/@media \(max-width: 390px\)/);assert.match(css,/\.rbac-one-time-value[^}]*overflow-wrap: anywhere/)
  assert.match(drawer,/focusRoot = portals\?\.getRootNode\(\)/);assert.match(drawer,/restoreFocus\(trigger, focusRoot\)/)
  assert.match(lifecycle,/portals.lang = target.lang/)
})
