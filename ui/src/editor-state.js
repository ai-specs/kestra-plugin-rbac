import {isProxy, toRaw} from 'vue'
export function cloneDraft(value) {
  const source = isProxy(value) ? toRaw(value) : value
  if (Array.isArray(source)) return source.map(cloneDraft)
  if (source !== null && typeof source === 'object') return Object.fromEntries(Object.entries(source).map(([key, child]) => [key, cloneDraft(child)]))
  return source
}
export function createEditorState() {
  return {kind: '', draft: null, baseline: '', saving: false, maximized: false, discard: false, pending: null}
}
export function openEditor(state, kind, value) {
  if (!['group', 'user', 'permission', 'subject'].includes(kind) || state.kind) return false
  const draft = cloneDraft(value)
  Object.assign(state, {kind, draft, baseline: JSON.stringify(draft), saving: false, maximized: false, discard: false, pending: null})
  return true
}
export const editorDirty = state => state.kind !== '' && JSON.stringify(state.draft) !== state.baseline
export function requestEditorClose(state, afterClose = null) {
  if (!state.kind || state.saving) return false
  if (editorDirty(state)) {state.discard = true; state.pending = afterClose; return false}
  Object.assign(state, createEditorState())
  afterClose?.()
  return true
}
export function cancelDiscard(state) {state.discard = false; state.pending = null}
export function confirmDiscard(state) {
  if (state.saving || !state.discard) return false
  const callback = state.pending
  Object.assign(state, createEditorState())
  callback?.()
  return true
}
