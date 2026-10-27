export const newOneTimeValue = () => ({value: '', subject: '', kind: '', generation: 0, copyAttempt: 0, copied: false, copyFailed: false})
export function clearOneTimeValue(state) {
  const generation = state.generation + 1
  Object.assign(state, newOneTimeValue(), {generation})
}
export function revealOneTimeValue(state, value, subject, kind) {
  clearOneTimeValue(state)
  if (typeof value !== 'string' || !value || !['password', 'secret'].includes(kind)) throw new Error('invalid_one_time_response')
  Object.assign(state, {value, subject, kind})
}
export async function copyOneTimeValue(state, clipboard) {
  if (!state.value) return false
  const current = state.generation
  const attempt = ++state.copyAttempt
  state.copied = false; state.copyFailed = false
  try {
    await clipboard.writeText(state.value)
    if (state.generation !== current || state.copyAttempt !== attempt || !state.value) return false
    state.copied = true; state.copyFailed = false; return true
  } catch {
    if (state.generation === current && state.copyAttempt === attempt && state.value) {state.copyFailed = true; state.copied = false}
    return false
  }
}
export function credentialDisplay(value) {
  if (value?.clientType === 'public') return {key: 'noSecret', hint: '', canRotate: false}
  if (value?.clientType !== 'confidential' || !Number.isSafeInteger(value.credentialRevision) || !['online', 'deployment-maintenance'].includes(value.rotationMode)) return {key: 'unavailable', hint: '', canRotate: false}
  if (!value.secretSet) return {key: 'noSecret', hint: '', canRotate: value.rotationMode === 'online'}
  return {key: value.maskedHint ? '' : 'secretHintUnavailable', hint: value.maskedHint ?? '', canRotate: value.rotationMode === 'online'}
}
