export function deepestActiveElement(root) {
  let active = root?.activeElement ?? null
  while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement
  return active
}
export function restoreFocus(trigger, root) {
  const target = trigger?.isConnected ? trigger : root?.querySelector('[data-focus-fallback]') ?? root?.querySelector('[data-test="access-control-root"]')
  target?.focus()
  return target ?? null
}
