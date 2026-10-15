import {createApp, h, shallowReactive} from 'vue'
import {createI18n} from 'vue-i18n'
import App from './App.vue'

const documents = import.meta.glob('./translations/*.json', {eager: true, import: 'default'})
const messages = Object.assign({}, ...Object.values(documents))
const mounted = new WeakSet()

export async function mountPage(container, initial) {
  if (initial.contractVersion !== 1 || mounted.has(container) || initial.signal.aborted) throw new Error('Invalid management page lifecycle')
  mounted.add(container)
  const root = container.shadowRoot ?? container.attachShadow({mode: 'open'})
  const target = document.createElement('div')
  target.className = 'extension-body rbac-page'
  const state = shallowReactive({context: initial})
  const locale = value => messages[value] ? value : 'en'
  const i18n = createI18n({legacy: false, locale: locale(initial.locale), fallbackLocale: 'en', messages})
  let app
  let disposed = false
  const unmount = () => {
    if (disposed) return
    disposed = true
    initial.signal.removeEventListener('abort', unmount)
    app?.unmount()
    root.replaceChildren()
    mounted.delete(container)
  }
  initial.signal.addEventListener('abort', unmount, {once: true})
  try {
    const styles = initial.styles.map(href => new Promise((resolve, reject) => {
      const link = document.createElement('link')
      const abort = () => reject(new Error('Management page mount cancelled'))
      const settle = callback => () => {
        initial.signal.removeEventListener('abort', abort)
        link.onload = null
        link.onerror = null
        callback()
      }
      link.rel = 'stylesheet'
      link.href = href
      link.onload = settle(resolve)
      link.onerror = settle(() => reject(new Error('Management stylesheet unavailable')))
      initial.signal.addEventListener('abort', abort, {once: true})
      root.append(link)
    }))
    await Promise.all(styles)
    if (disposed) throw new Error('Management page mount cancelled')
    root.append(target)
    const update = next => {
      if (disposed) return
      state.context = next
      i18n.global.locale.value = locale(next.locale)
      container.classList.remove('light', 'dark', 'dark-2')
      if (next.theme === 'dark-2') container.classList.add('dark', 'dark-2')
      else container.classList.add(next.theme)
      target.lang = locale(next.locale).replaceAll('_', '-')
    }
    update(initial)
    app = createApp({render: () => h(App, {context: state.context})})
    app.use(i18n)
    app.mount(target)
    return {update, unmount}
  } catch (failure) {
    unmount()
    throw failure
  }
}
