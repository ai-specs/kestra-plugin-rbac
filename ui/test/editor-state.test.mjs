import test from 'node:test'
import assert from 'node:assert/strict'
import {reactive, readonly} from 'vue'
import {createEditorState, openEditor, editorDirty, requestEditorClose, cancelDiscard, confirmDiscard} from '../src/editor-state.js'

test('N17/T10 regression: real Vue reactive and nested readonly DTOs clone without aliasing and keep dirty guards', () => {
  const row = reactive({key: 'p', scopeKeys: ['a'], grants: [reactive({actions: ['READ']})], metadata: readonly({label: 'saved'})})
  const state = reactive(createEditorState())
  assert.equal(openEditor(state, 'permission', row), true)
  assert.equal(editorDirty(state), false)
  state.draft.grants[0].actions.push('UPDATE'); state.draft.scopeKeys.push('b'); state.draft.metadata.label = 'draft'
  assert.deepEqual(row.grants[0].actions, ['READ']); assert.deepEqual(row.scopeKeys, ['a']); assert.equal(row.metadata.label, 'saved')
  assert.equal(editorDirty(state), true); assert.equal(requestEditorClose(state), false)
  cancelDiscard(state); assert.equal(state.draft.metadata.label, 'draft')
  requestEditorClose(state); confirmDiscard(state); assert.equal(state.kind, '')
})

test('N17/T10: exactly four drawer kinds; drafts do not alias saved objects', () => {
  for (const kind of ['group', 'user', 'permission', 'subject']) {
    const state = createEditorState(), value = {name: 'original', scopeKeys: []}
    assert.equal(openEditor(state, kind, value), true)
    state.draft.scopeKeys.push('one')
    assert.deepEqual(value.scopeKeys, [])
    assert.equal(openEditor(state, 'group', value), false)
  }
  assert.equal(openEditor(createEditorState(), 'application', {}), false)
})
test('N17/T10: all dirty close paths defer navigation, cancel preserves draft and confirm resets fullscreen', () => {
  const state = createEditorState()
  openEditor(state, 'permission', {name: 'saved'})
  state.draft.name = 'changed'; state.maximized = true
  let navigated = 0
  assert.equal(editorDirty(state), true)
  assert.equal(requestEditorClose(state, () => navigated++), false)
  assert.equal(navigated, 0)
  cancelDiscard(state)
  assert.equal(state.draft.name, 'changed')
  assert.equal(confirmDiscard(state), false)
  requestEditorClose(state, () => navigated++)
  assert.equal(confirmDiscard(state), true)
  assert.equal(navigated, 1)
  assert.deepEqual(state, createEditorState())
})
test('N17/N19/T10: saving locks close/discard; failed save can retain the same draft', () => {
  const state = createEditorState()
  openEditor(state, 'user', {name: 'saved'})
  state.draft.name = 'changed'; state.saving = true
  assert.equal(requestEditorClose(state), false)
  assert.equal(confirmDiscard(state), false)
  assert.equal(state.discard, false)
  state.saving = false
  assert.equal(state.draft.name, 'changed')
  requestEditorClose(state)
  assert.equal(state.discard, true)
})
