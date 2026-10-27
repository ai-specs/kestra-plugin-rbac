export function createDraftNavigationGuard(readState, confirm) {
  return () => {
    const {saving, dirty} = readState()
    if (saving) return false
    if (!dirty) return true
    return new Promise(resolve => confirm(resolve))
  }
}
