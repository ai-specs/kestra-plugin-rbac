export const themes = ['light', 'dark', 'dark-2', 'system']
export const locales = ['en', 'zh_CN']
export const widths = [1280, 768, 390]
export const behaviors = [
  ...['group', 'user', 'permission', 'subject'].flatMap(kind => [
    `drawer-${kind}-native-style`, `drawer-${kind}-focus-close-reopen`,
    `drawer-${kind}-draft-backdrop-history`, `drawer-${kind}-maximize-save-loading`
  ]),
  'cards-counts-title', 'list-permission-summary', 'filter-popup-geometry',
  'filter-popup-buttons-reopen-scroll', 'state-normal', 'state-empty', 'state-loading',
  'state-error-retry', 'state-denied', 'state-reauthentication', 'user-edit-save',
  'inline-discard', 'credential-copy-retry', 'theme-switch-refresh-system'
]
export const cases = themes.flatMap(theme => locales.flatMap(locale => widths.flatMap(width => behaviors.map(behavior => ({
  id: `plugin-ui/${theme}/${locale}/${width}/${behavior}`, theme, locale, width, behavior
})))))
export const manifest = {schemaVersion: 1, gate: 'plugin-ui-chromium', cases}
