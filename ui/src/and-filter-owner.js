// Version-bound DS0.5 adapter. A registered area's activation owns only its newly opened portal layer.
const registries = new WeakMap()
const events = ['click', 'mousedown', 'pointerdown', 'keydown']
export function ownAndFilter({area, portal, description, descriptionId, fail, savedFiltersShown = false, savedLabel, allLabel}) {
  if (!area || !portal || area.getRootNode() !== portal.getRootNode()) throw new Error('Owned filter portal required')
  let registry = registries.get(portal)
  if (!registry) {registry = {pending: null, claims: new Map()}; registries.set(portal, registry)}
  const token = {}, records = new Map()
  let closed = false, failureNotified = false, failurePending = false, generation = 0
  const visible = node => node.isConnected && !node.closest('[hidden],[aria-hidden="true"]') && node.style.display !== 'none'
  const snapshot = (node, names) => Object.fromEntries(names.map(name => [name, node.getAttribute(name)]))
  const restore = (node, attributes) => {for (const [name, value] of Object.entries(attributes)) value === null ? node.removeAttribute(name) : node.setAttribute(name, value)}
  const remember = layer => {
    if (!records.has(layer)) records.set(layer, {buttons: new Map(), hidden: new Map(), layout: new Map(), note: null, block: null, inert: layer.inert, invalid: layer.getAttribute('data-and-filter-invalid'), dismiss: null, rejected: false, generation})
    return records.get(layer)
  }
  const release = layer => {
    const record = records.get(layer)
    if (!record) return
    if (record.block) for (const type of events) layer.removeEventListener(type, record.block, true)
    for (const [button, attributes] of record.buttons) restore(button, attributes)
    for (const [node, attributes] of record.hidden) restore(node, attributes)
    for (const [node, attributes] of record.layout) restore(node, attributes)
    record.note?.remove(); layer.inert = record.inert
    restore(layer, {'data-and-filter-invalid': record.invalid})
    if (registry.claims.get(layer) === token) registry.claims.delete(layer)
    records.delete(layer)
    if (record.rejected && record.generation === generation) notifyFailure()
  }
  const notifyFailure = () => {
    if (closed || failureNotified) return
    failureNotified = true; failurePending = false; fail()
  }
  const reject = layer => {
    const record = remember(layer); registry.claims.set(layer, token)
    if (record.rejected) return
    layer.inert = true; layer.setAttribute('data-and-filter-invalid', '')
    const ownedDismiss = registry.claims.get(layer) === token && record.generation === generation && record.dismiss
    const dismiss = ownedDismiss && ownedDismiss.isConnected && portal.contains(ownedDismiss)
      && (!layer.matches('.mobile-sheet') || ownedDismiss.matches('.mobile-scrim')) ? ownedDismiss : null
    if (dismiss) {
      record.rejected = true; failurePending = true
      // Let the native close handler drive renderer.remove and SDK modal cleanup
      // before the consumer unmounts this filter's original subtree.
      dismiss.click()
    } else notifyFailure()
  }
  const installBlock = (layer, record) => {
    if (record.block) return
    record.block = event => {
      if (event.type === 'keydown' && !['Enter', ' ', 'Spacebar'].includes(event.key)) return
      if (event.composedPath().some(node => record.buttons.has(node) || record.hidden.has(node))) {event.preventDefault(); event.stopImmediatePropagation()}
    }
    for (const type of events) layer.addEventListener(type, record.block, true)
  }
  const restrictMobile = layer => {
    if (savedFiltersShown !== false) {reject(layer); return}
    const record = remember(layer), tabs = layer.querySelectorAll('.sheet-tabs'), actions = layer.querySelectorAll('.sheet-footer-actions')
    const labels = tabs.length === 1 ? [...tabs[0].querySelectorAll('label.kel-segmented__item')] : []
    const buttons = actions.length === 1 ? [...actions[0].querySelectorAll('button')] : []
    const all = labels.filter(node => node.querySelector('.kel-segmented__item-label')?.textContent.trim() === allLabel?.())
    const saved = labels.filter(node => node.querySelector('.kel-segmented__item-label')?.textContent.trim() === savedLabel?.())
    const apply = buttons.filter(node => node.matches('.sheet-apply')), save = buttons.filter(node => !node.matches('.sheet-apply'))
    if (labels.length !== 2 || all.length !== 1 || saved.length !== 1 || all[0] === saved[0] || buttons.length !== 2 || save.length !== 1 || apply.length !== 1 || !all[0].querySelector('input[type="radio"]')?.checked || layer.querySelector('.saved-filters-panel')) {reject(layer); return}
    for (const node of [saved[0], save[0]]) {
      if (!record.hidden.has(node)) record.hidden.set(node, snapshot(node, ['hidden', 'inert', 'aria-hidden', 'style']))
      if (!node.hidden) node.hidden = true
      if (!node.inert) node.inert = true
      if (node.getAttribute('aria-hidden') !== 'true') node.setAttribute('aria-hidden', 'true')
      if (node.style.getPropertyValue('display') !== 'none' || node.style.getPropertyPriority('display') !== 'important') node.style.setProperty('display', 'none', 'important')
      for (const control of node.matches('button') ? [node] : node.querySelectorAll('input,button')) {
        if (!record.buttons.has(control)) record.buttons.set(control, snapshot(control, ['disabled', 'aria-disabled', 'aria-describedby']))
        if (!control.disabled) control.disabled = true
        if (control.getAttribute('aria-disabled') !== 'true') control.setAttribute('aria-disabled', 'true')
      }
    }
    const indicator = tabs[0].querySelector('.kel-segmented__item-selected')
    for (const node of [all[0], indicator].filter(Boolean)) if (!record.layout.has(node)) record.layout.set(node, snapshot(node, ['style']))
    if (all[0].style.flex !== '1 1 100%') all[0].style.flex = '1 1 100%'
    if (indicator && indicator.style.width !== '100%') indicator.style.width = '100%'
    installBlock(layer, record)
  }
  const restrict = layer => {
    const record = remember(layer), footers = layer.querySelectorAll('.adv-footer'), footer = footers[0]
    const actions = footer ? [...footer.children].filter(node => node.matches('button.adv-add')) : []
    if (footers.length !== 1 || actions.length !== 2 || layer.querySelectorAll('.adv-body').length !== 1) {reject(layer); return}
    if (!record.note) {
      record.note = layer.ownerDocument.createElement('p'); record.note.dataset.andFilterNote = ''; record.note.id = descriptionId
      record.note.className = 'rbac-and-filter-note'
      record.note.style.cssText = 'margin:var(--ks-spacing-2) 0 0;padding:0 var(--ks-spacing-3);font:inherit;color:var(--ks-text-secondary);white-space:normal;overflow-wrap:anywhere;max-width:100%;box-sizing:border-box'
      footer.after(record.note)
    }
    if (record.note.textContent !== description()) record.note.textContent = description()
    for (const button of [actions[1], ...layer.querySelectorAll('button.adv-conj.toggle')]) {
      if (!record.buttons.has(button)) record.buttons.set(button, snapshot(button, ['disabled', 'aria-disabled', 'aria-describedby']))
      if (!button.disabled) button.disabled = true
      if (button.getAttribute('aria-disabled') !== 'true') button.setAttribute('aria-disabled', 'true')
      if (button.getAttribute('aria-describedby') !== descriptionId) button.setAttribute('aria-describedby', descriptionId)
    }
    installBlock(layer, record)
  }
  const scan = () => {
    if (closed) return
    for (const layer of records.keys()) if (!portal.contains(layer)) release(layer)
    const layers = [...portal.querySelectorAll('.adv-builder,.mobile-sheet')].filter(visible)
    if (registry.pending?.token === token) {
      const candidates = layers.filter(layer => !registry.claims.has(layer) && !registry.pending.existing.has(layer))
      if (candidates.length > 1) {for (const layer of candidates) reject(layer); registry.pending = null}
      else if (candidates.length === 1) {
        const layer = candidates[0]
        registry.claims.set(layer, token)
        if (layer.matches('.mobile-sheet')) {
          const scrims = [...portal.querySelectorAll('.mobile-scrim')].filter(node => visible(node) && !registry.pending.scrims.has(node))
          if (scrims.length === 1) remember(layer).dismiss = scrims[0]
        } else remember(layer).dismiss = layer.closest('.adv-overlay')
        registry.pending = null
      }
    }
    for (const layer of layers) if (registry.claims.get(layer) === token && !layer.hasAttribute('data-and-filter-invalid')) (layer.matches('.mobile-sheet') ? restrictMobile(layer) : restrict(layer))
  }
  const activate = event => {
    const trigger = event.composedPath().find(node => node?.matches?.('.customize-button,.rules-pill,.mobile-toggle'))
    if (!failurePending && trigger && area.contains(trigger)) registry.pending = {token, existing: new Set(portal.querySelectorAll('.adv-builder,.mobile-sheet')), scrims: new Set(portal.querySelectorAll('.mobile-scrim'))}
  }
  area.addEventListener('pointerdown', activate, true); area.addEventListener('click', activate, true)
  const observer = new MutationObserver(scan)
  observer.observe(portal, {subtree: true, childList: true, attributes: true, attributeFilter: ['disabled', 'aria-disabled', 'aria-describedby', 'hidden', 'inert', 'aria-hidden', 'style']})
  return {
    refresh: scan,
    reset() {
      generation++; failureNotified = false; failurePending = false
      if (registry.pending?.token === token) registry.pending = null
      for (const layer of [...records.keys()]) if (layer.hasAttribute('data-and-filter-invalid')) {
        const overlay = layer.closest('.adv-overlay'); release(layer); overlay?.click()
      }
    },
    dispose() {
      closed = true; observer.disconnect()
      area.removeEventListener('pointerdown', activate, true); area.removeEventListener('click', activate, true)
      if (registry.pending?.token === token) registry.pending = null
      for (const layer of [...records.keys()]) release(layer)
    },
  }
}
