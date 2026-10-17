import {createRenderer, h, shallowReactive} from 'vue'
import {nodeOps, patchProp} from '@vue/runtime-dom'
import {createI18n} from 'vue-i18n'
import {ElConfigProvider} from 'element-plus'
import {createMemoryHistory, createRouter} from 'vue-router'
import {registerDesignSystemI18n, KsButton, KsInput, KsSearch, KsPopover, KsTooltip, KsIcon, KsSelect, KsOption, KsDatePicker, KsTag, KsSegmented} from '@kestra-io/design-system'
import App from './App.vue'

const documents = import.meta.glob('./translations/*.json', {eager: true, import: 'default'})
const messages = Object.assign({}, ...Object.values(documents))
const mounted = new WeakSet()

export async function mountPage(container, initial) {
  if (initial.contractVersion !== 1 || mounted.has(container) || initial.signal.aborted) throw new Error('Invalid management page lifecycle')
  mounted.add(container)
  const root = container.shadowRoot ?? container.attachShadow({mode: 'open'})
  const target = document.createElement('div')
  const portals = document.createElement('div')
  portals.className = 'extension-body extension-portals'
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
    root.append(target, portals)
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
    const renderer = createRenderer({...nodeOps, patchProp, querySelector: selector => selector === 'body' ? portals : root.querySelector(selector)})
    app = renderer.createApp({render: () => h(ElConfigProvider, {namespace: 'kel'}, {default: () => h(App, {context: state.context})})})
    app.use(i18n)
    const router = createRouter({history: createMemoryHistory(), routes: [{path: '/:pathMatch(.*)*', name: 'rbac-management', component: {render: () => null}}]})
    await router.push('/' + initial.path)
    app.use(router)
    await registerDesignSystemI18n(i18n)
    if (disposed) throw new Error('Management page mount cancelled')
    for (const [name, component] of Object.entries({KsButton, KsInput, KsSearch, KsIcon, KsSelect, KsOption, KsDatePicker, KsTag, KsSegmented})) app.component(name, component)
    app.component('KsPopover', {inheritAttrs: false, setup: (_, {attrs, slots}) => () => h(KsPopover, {...attrs, teleported: false}, slots)})
    app.component('KsTooltip', {inheritAttrs: false, setup: (_, {attrs, slots}) => () => h(KsTooltip, {...attrs, teleported: false}, slots)})
    app.mount(target)
    return {update, unmount}
  } catch (failure) {
    unmount()
    throw failure
  }
}
