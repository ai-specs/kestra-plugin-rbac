import {h, shallowReactive} from 'vue'
import {nodeOps, patchProp} from '@vue/runtime-dom'
import * as Vue from 'vue'
import {createI18n} from 'vue-i18n'
import {ElConfigProvider, ID_INJECTION_KEY} from 'element-plus'
import {createMemoryHistory, createRouter} from 'vue-router'
import {registerDesignSystemI18n, KsButton, KsInput, KsSearch, KsPopover, KsTooltip, KsIcon, KsSelect, KsOption, KsTag, KsSegmented} from '@kestra-io/design-system'
import App from './App.vue'
import {resolveLocale} from '../../.integration/ui-sdk/locales.mjs'
import {createShadowOverlayScope, createShadowRenderer, shadowOverlayComponents} from '../../.integration/ui-sdk/shadow-overlays.mjs'
import {contextFullPath, createNavigationHooks} from './navigation-lifecycle.js'
import {nativeLocale} from './native-locale.js'
import KsDrawer from '@kestra-io/design-system/components/Feedback/KsDrawer'
import KsDialog from '@kestra-io/design-system/components/Feedback/KsDialog'
import KsFilter from '@kestra-io/design-system/components/Data/KsDataTable/KsFilter'
import {KsDropdown, KsDropdownMenu, KsDropdownItem} from '@kestra-io/design-system'

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
  const locale = value => resolveLocale(value)
  const i18n = createI18n({legacy: false, locale: locale(initial.locale), fallbackLocale: 'en', messages})
  let app
  let overlayScope
  const hooks = createNavigationHooks()
  let disposed = false
  const unmount = () => {
    if (disposed) return
    disposed = true
    initial.signal.removeEventListener('abort', unmount)
    hooks.dispose()
    app?.unmount()
    overlayScope?.dispose()
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
      portals.lang = target.lang
    }
    update(initial)
    overlayScope = createShadowOverlayScope({container, content: target, portal: portals, signal: initial.signal, fallback: () => target.querySelector('[data-focus-fallback]') ?? target.querySelector('main')})
    const renderer = createShadowRenderer({...Vue, nodeOps, patchProp}, overlayScope, {idInjectionKey: ID_INJECTION_KEY, namespace: 'kel'})
    app = renderer.createApp({render: () => h(ElConfigProvider, {namespace: 'kel', locale: nativeLocale(state.context.locale)}, {default: () => h(App, {context: state.context})})})
    app.provide('rbac-portals', portals)
    app.provide('rbac-navigation-hooks', hooks)
    app.use(i18n)
    const router = createRouter({history: createMemoryHistory(), routes: [{path: '/:pathMatch(.*)*', name: 'rbac-management', component: {render: () => null}}]})
    await router.push('/' + contextFullPath(initial))
    app.use(router)
    await registerDesignSystemI18n(i18n)
    if (disposed) throw new Error('Management page mount cancelled')
    for (const [name, component] of Object.entries({KsButton, KsInput, KsSearch, KsIcon, KsOption, KsTag, KsSegmented, KsDropdownMenu, KsDropdownItem})) app.component(name, component)
    const filterSupportingComponents = {KsPopover, KsTooltip, KsDropdown}
    const adapted = shadowOverlayComponents(Vue, {KsDrawer, KsDialog, KsSelect, ...filterSupportingComponents}, overlayScope)
    for (const [name, component] of Object.entries(adapted)) app.component(name, component)
    app.component('KsFilter', KsFilter)
    app.mount(target)
    return {update, unmount, beforeNavigate: target => disposed ? false : hooks.beforeNavigate(target)}
  } catch (failure) {
    unmount()
    throw failure
  }
}
