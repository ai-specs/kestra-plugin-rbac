export const pageSizes = Object.freeze([10, 25, 50, 100])
export const detailTabs = Object.freeze(['overview', 'scopes', 'applications', 'scope-grants'])
export const compareKeys = (a, b) => {
  const left = Array.from(String(a), c => c.codePointAt(0)), right = Array.from(String(b), c => c.codePointAt(0))
  for (let i = 0; i < Math.min(left.length, right.length); i++) if (left[i] !== right[i]) return left[i] - right[i]
  return left.length - right.length
}
export const ordered = (items, field = 'key') => [...items].sort((a, b) => compareKeys(a[field], b[field]))
export const supportedScopes = permissions => [...new Set(permissions.flatMap(permission => permission.scopeKeys))].sort(compareKeys)
export function involvedApplications(subject, applications) {
  if (subject.active === false) return []
  return ordered(applications.filter(application => application.active !== false && application.permissions?.some(permission => permission.scopeKeys.some(key => subject.scopeKeys.includes(key)))), 'clientId')
}
export function permissionSummary(permission) {
  if (permission.implementationKind === 'external-declaration') return String(permission.details ?? '').split(/\r?\n/).filter(Boolean)
  if (permission.implementationKind !== 'kestra-policy') return []
  return [
    ...(permission.grants ?? []).map(grant => [grant.namespace ?? grant.tenant, grant.resource, grant.actions.join(' / '), grant.allIds ? '*' : grant.ids.join(', ')].join(' · ')),
    ...(permission.managementGrants ?? []).map(grant => [grant.domain, grant.actions.join(' / '), ...(grant.applicationGroupIds ?? [])].join(' · '))
  ]
}
export function matchesChips(item, chips) {
  return chips.every(chip => {
    if (chip.field === 'scopeKeys') return chip.values.some(key => item.scopeKeys?.includes(key))
    if (chip.field === 'name') return String(item.name ?? '').includes(chip.value)
    if (['key', 'subject'].includes(chip.field)) return item[chip.field] === chip.value
    return false
  })
}
export function nativeChips(parameters, kind) {
  return parameters.filter(parameter => parameter.field !== 'q').map(parameter => {
    if (parameter.topLogical === 'OR' || parameter.wrapperLogical === 'OR') throw invalidQuery()
    const field = parameter.field
    if (field === 'scopeKeys' && ['EQUALS', 'IN'].includes(parameter.operation)) {
      const values = Array.isArray(parameter.value) ? parameter.value : [parameter.value]
      if (!values.length || values.some(value => typeof value !== 'string')) throw invalidQuery()
      return {field, values}
    }
    if ((kind === 'permissions' ? ['key', 'name'] : ['subject']).includes(field) && parameter.operation === (field === 'name' ? 'CONTAINS' : 'EQUALS') && typeof parameter.value === 'string') return {field, value: parameter.value}
    throw invalidQuery()
  })
}
const invalidQuery = () => Object.assign(new Error('invalid_query'), {code: 'invalid_query', status: 400})
export function readListQuery(query, kind = 'permissions') {
  const values = key => query[key] === undefined ? [] : Array.isArray(query[key]) ? query[key] : [query[key]]
  const single = key => {
    const entries = values(key)
    if (entries.length > 1 || entries.some(value => typeof value !== 'string')) throw invalidQuery()
    return entries[0]
  }
  const integer = (key, fallback) => {
    const value = single(key)
    if (value === undefined) return fallback
    if (!/^[1-9][0-9]*$/.test(value) || !Number.isSafeInteger(Number(value))) throw invalidQuery()
    return Number(value)
  }
  const page = integer('page', 1), size = integer('size', 25)
  if (!pageSizes.includes(size)) throw invalidQuery()
  const chips = []
  const permissionKey = single('permissionKey'), filterSubject = single('filterSubject')
  const scopes = values('scopeKeys')
  if (scopes.some(key => typeof key !== 'string' || !/^[a-z][a-z0-9-]{0,63}$/.test(key)) || new Set(scopes).size !== scopes.length) throw invalidQuery()
  if (permissionKey !== undefined) {
    if (kind !== 'permissions' || !/^[a-z][a-z0-9-]{0,127}$/.test(permissionKey)) throw invalidQuery()
    chips.push({field: 'key', value: permissionKey, source: 'deep-link'})
  }
  if (filterSubject !== undefined) {
    if (!filterSubject || [...filterSubject].length > 256) throw invalidQuery()
    if (kind !== 'permissions') chips.push({field: 'subject', value: filterSubject, source: 'deep-link'})
  }
  if (scopes.length) chips.push({field: 'scopeKeys', values: [...scopes], source: 'deep-link', subject: filterSubject})
  const filters = values('filter')
  for (const text of filters) {
    if (typeof text !== 'string') throw invalidQuery()
    let chip
    try {chip = JSON.parse(text)} catch {throw invalidQuery()}
    if (!chip || typeof chip !== 'object' || Array.isArray(chip)) throw invalidQuery()
    if (chip.field === 'scopeKeys') {
      if (!Array.isArray(chip.values) || !chip.values.length || chip.values.some(value => typeof value !== 'string' || !/^[a-z][a-z0-9-]{0,63}$/.test(value))) throw invalidQuery()
      chips.push({field: 'scopeKeys', values: [...new Set(chip.values)], source: 'manual'})
    } else if ((kind === 'permissions' ? ['key', 'name'] : ['subject']).includes(chip.field) && typeof chip.value === 'string' && chip.value.length && [...chip.value].length <= 256) {
      chips.push({field: chip.field, value: chip.value, source: 'manual'})
    } else throw invalidQuery()
  }
  return {page, size, chips, filterSubject}
}
export function writeListQuery(state, previous = {}) {
  const result = {...previous}
  for (const key of ['page', 'size', 'permissionKey', 'filterSubject', 'scopeKeys', 'filter']) delete result[key]
  if (state.page !== 1) result.page = String(state.page)
  if (state.size !== 25) result.size = String(state.size)
  const manual = []
  for (const chip of state.chips) {
    if (chip.source === 'deep-link' && chip.field === 'key') result.permissionKey = chip.value
    else if (chip.source === 'deep-link' && chip.field === 'subject') result.filterSubject = chip.value
    else if (chip.source === 'deep-link' && chip.field === 'scopeKeys') {
      result.scopeKeys = [...chip.values]
      if (chip.subject) result.filterSubject = chip.subject
    } else manual.push(JSON.stringify(chip.field === 'scopeKeys' ? {field: chip.field, values: chip.values} : {field: chip.field, value: chip.value}))
  }
  if (manual.length) result.filter = manual
  return result
}
export function clearListQuery(query, kind = 'permissions') {
  try {
    const {size} = readListQuery(query, kind)
    return size === 25 ? {} : {size: String(size)}
  } catch {return {}}
}
export function canonicalListPage(query, kind, total) {
  const state = readListQuery(query, kind)
  const last = Math.max(1, Math.ceil(total / state.size))
  if (state.page <= last) return null
  const next = {...query}
  if (last === 1) delete next.page
  else next.page = String(last)
  return next
}
export function paginate(items, page = 1, size = 25) {
  if (!pageSizes.includes(size) || !Number.isSafeInteger(page) || page < 1) throw invalidQuery()
  const last = Math.max(1, Math.ceil(items.length / size))
  const current = Math.min(page, last)
  return {items: items.slice((current - 1) * size, current * size), total: items.length, page: current, size}
}
export function selectionAfterDelete(items, removed, field = 'key') {
  const keys = items.map(item => item[field]).filter(key => key !== removed).sort(compareKeys)
  return keys.find(key => compareKeys(key, removed) > 0) ?? keys.at(-1) ?? ''
}
export function countValue(counts, field) {
  return Number.isSafeInteger(counts?.[field]) && counts[field] >= 0 ? counts[field] : null
}
import {withinTextLimit, textLimits} from './text-limits.js'
export function permissionInput(draft, implementationKind, scopeKeys) {
  const fail = () => {throw Object.assign(new Error('invalid_permission'), {code: 'invalid_permission', status: 400})}
  if (!/^[a-z][a-z0-9-]{0,127}$/.test(draft.key) || !withinTextLimit(draft.name, textLimits.name, true) || !Array.isArray(draft.scopeKeys) || new Set(draft.scopeKeys).size !== draft.scopeKeys.length || draft.scopeKeys.some(key => !scopeKeys.includes(key))) fail()
  const common = {key: draft.key, name: draft.name, scopeKeys: [...draft.scopeKeys]}
  if (implementationKind === 'external-declaration') {
    if (!withinTextLimit(draft.details, textLimits.details)) fail()
    return {...common, details: draft.details}
  }
  if (implementationKind !== 'kestra-policy' || typeof draft.enabled !== 'boolean' || !withinTextLimit(draft.description, textLimits.description) || !Array.isArray(draft.grants) || !Array.isArray(draft.managementGrants)) fail()
  return {...common, enabled: draft.enabled, description: draft.description, grants: draft.grants, managementGrants: draft.managementGrants}
}
export function permissionDraft(permission, implementationKind) {
  if (!['kestra-policy', 'external-declaration'].includes(implementationKind) || permission && permission.implementationKind !== implementationKind) throw new Error('invalid_permission_kind')
  const common = {key: permission?.key ?? '', name: permission?.name ?? '', scopeKeys: [...(permission?.scopeKeys ?? [])]}
  if (permission?.revision !== undefined) common.revision = permission.revision
  if (permission?.parentRevision !== undefined) common.parentRevision = permission.parentRevision
  return {...common, ...(implementationKind === 'kestra-policy'
    ? {description: permission?.description ?? '', enabled: permission?.enabled ?? true, grants: JSON.parse(JSON.stringify(permission?.grants ?? [])), managementGrants: JSON.parse(JSON.stringify(permission?.managementGrants ?? []))}
    : {details: permission?.details ?? ''})}
}
export const availableScopeOptions = scopes => ordered(scopes.filter(scope => typeof scope.key === 'string' && /^[a-z][a-z0-9-]{0,63}$/.test(scope.key)))
export async function collectPermissions(fetchPage, signal, expectedParentRevision) {
  const items = [], keys = new Set()
  let total, revision = expectedParentRevision
  for (let page = 1; ; page++) {
    if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError')
    const result = await fetchPage(page, 100)
    const fail = () => {throw Object.assign(new Error('application_changed'), {code: 'application_changed', status: 409})}
    if (!result || !Array.isArray(result.items) || !Number.isSafeInteger(result.total) || result.total < 0 || result.page !== page || result.size !== 100 || !Number.isSafeInteger(result.parentRevision) || result.parentRevision < 1) fail()
    if (revision !== undefined && result.parentRevision !== revision || total !== undefined && result.total !== total) fail()
    revision = result.parentRevision; total = result.total
    for (const item of result.items) {
      if (!item || typeof item.key !== 'string' || keys.has(item.key) || item.parentRevision !== revision) fail()
      keys.add(item.key); items.push(item)
    }
    if (items.length > total || !result.items.length && items.length < total) fail()
    if (items.length === total) return items
  }
}
