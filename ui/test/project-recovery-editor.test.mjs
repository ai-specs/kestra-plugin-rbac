import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {reactive, ref, watch, watchEffect, nextTick} from 'vue'

const source = readFileSync(new URL('../src/ProjectRecoveryEditor.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*\n/gm, '')
  .replace(/^const props = .*\n/m, '')
  .replace(/^const emit = .*\n/m, '')
  .replace(/^const \{t\} = .*\n/m, '')
const setup = new Function('props', 'ref', 'watch', script + '\nreturn {addScope,newScope,approved}')

test('adding a recovery scope updates reactive rendering and resets review approval', async () => {
  const project = reactive({scopes:{existing:{enabled:true}}})
  const editor = setup({project}, ref, watch)
  let rendered = []
  const stop = watchEffect(() => {rendered = Object.keys(project.scopes)})
  editor.approved.value = true
  editor.newScope.value = ' new-scope '
  editor.addScope()
  await nextTick()
  assert.deepEqual(rendered, ['existing', 'new-scope'])
  assert.deepEqual(project.scopes['new-scope'], {description:'',enabled:false,grants:[],managementGrants:[]})
  assert.equal(editor.newScope.value, '')
  assert.equal(editor.approved.value, false)
  stop()
})

test('blank and existing scope names preserve the original scopes and values', async () => {
  const project = reactive({scopes:{existing:{enabled:true}}})
  const editor = setup({project}, ref, watch)
  const original = project.scopes
  for (const name of ['   ', ' existing ']) {
    editor.approved.value = true
    editor.newScope.value = name
    editor.addScope()
    await nextTick()
    assert.equal(project.scopes, original)
    assert.deepEqual(Object.keys(project.scopes), ['existing'])
    assert.equal(project.scopes.existing.enabled, true)
    assert.equal(editor.newScope.value, name)
    assert.equal(editor.approved.value, true)
  }
})

test('__proto__ becomes an own reactive scope without changing any prototype', async () => {
  const project = reactive({scopes:{}})
  const editor = setup({project}, ref, watch)
  const prototype = Object.getPrototypeOf(project.scopes)
  let rendered = []
  const stop = watchEffect(() => {rendered = Object.keys(project.scopes)})
  editor.newScope.value = '__proto__'
  editor.addScope()
  await nextTick()
  assert.deepEqual(rendered, ['__proto__'])
  assert.equal(Object.hasOwn(project.scopes, '__proto__'), true)
  assert.equal(Object.getPrototypeOf(project.scopes), prototype)
  assert.equal(Object.hasOwn(Object.prototype, 'enabled'), false)
  assert.equal(project.scopes.__proto__.enabled, false)
  stop()
})
