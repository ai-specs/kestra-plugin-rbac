import {stringifyQuery} from 'vue-router'

export function contextFullPath(context) {
  if (typeof context.fullPath === 'string') return context.fullPath
  const query = stringifyQuery(context.query ?? {})
  return context.path + (query ? '?' + query : '')
}

export function createNavigationHooks() {
  let disposed = false, pending = false, cancel
  return {
    guard: () => true,
    cleanup: () => {},
    async beforeNavigate(target) {
      if (disposed || pending) return false
      pending = true
      try {
        const result = await Promise.race([Promise.resolve().then(() => this.guard(target)), new Promise(resolve => {cancel = () => resolve(false)})])
        return !disposed && result === true
      } catch {return false}
      finally {pending = false; cancel = null}
    },
    dispose() {
      if (disposed) return
      disposed = true; cancel?.(); this.cleanup()
    }
  }
}
