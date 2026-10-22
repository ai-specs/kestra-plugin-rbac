export const resources = ['FLOW', 'EXECUTION', 'NAMESPACE', 'LOG', 'METRIC', 'TRIGGER', 'KVSTORE', 'SECRET', 'NAMESPACE_FILE', 'DASHBOARD', 'BLUEPRINT', 'MCP_SERVER', 'AI', 'METADATA']
export const actions = ['READ', 'LIST', 'CREATE', 'UPDATE', 'DELETE', 'EXECUTE', 'ACCESS_OUTPUTS']
export const splitLines = value => Array.isArray(value) ? [...value] : String(value ?? '').split(/\r?\n/).map(item => item.trim()).filter(Boolean)
export const copy = value => JSON.parse(JSON.stringify(value))
export const newGrant = () => ({scope: 'NAMESPACE', tenant: 'main', namespace: '', descendants: false, resource: 'FLOW', actions: [], ids: [], allIds: true})
export function groupScopeInput(value) {
  if (typeof value.key !== 'string' || !/^[a-z][a-z0-9-]{0,63}$/.test(value.key)
      || typeof value.name !== 'string' || !value.name.trim() || typeof value.description !== 'string')
    throw Object.assign(new Error('invalid_scope'), {code:'invalid_scope',status:400})
  return {key:value.key,name:value.name,description:value.description}
}
export function scopeInput(value) {
  if (typeof value.scopeKey !== 'string' || !/^[a-z][a-z0-9-]{0,63}$/.test(value.scopeKey)) throw Object.assign(new Error('invalid_scope'), {code:'invalid_scope',status:400})
  if (!Array.isArray(value.grants)) throw Object.assign(new Error('invalid_grant'), {code:'invalid_grant',status:400})
  return {scopeKey:value.scopeKey,description: value.description, enabled: value.enabled, managementGrants: (value.managementGrants ?? []).map(managementGrantInput), grants: value.grants.map(item => {
    const invalid = () => {throw Object.assign(new Error('invalid_grant'), {code:'invalid_grant',status:400})}
    if (!resources.includes(item.resource) || !Array.isArray(item.actions) || !item.actions.length || item.actions.some(action => !actions.includes(action)) || new Set(item.actions).size !== item.actions.length || typeof item.allIds !== 'boolean' || typeof item.descendants !== 'boolean' || !item.allIds && !splitLines(item.ids).length) invalid()
    if (item.resource === 'MCP_SERVER') {
      if (item.scope !== 'TENANT' || item.tenant !== 'main' || item.namespace !== null || item.descendants || item.actions.some(action => !['READ', 'LIST', 'CREATE', 'UPDATE', 'DELETE'].includes(action))) throw Object.assign(new Error('invalid_grant'), {code:'invalid_grant',status:400})
      return {scope:'TENANT',tenant:'main',namespace:null,descendants:false,resource:'MCP_SERVER',actions:[...item.actions],allIds:item.allIds,ids:item.allIds?[]:splitLines(item.ids)}
    }
    if (item.resource === 'METADATA' && (item.scope !== 'TENANT' || item.tenant !== 'main' || item.namespace !== null || item.descendants || !item.allIds || splitLines(item.ids).length || item.actions.some(action => !['READ','LIST'].includes(action)))) invalid()
    if (item.resource === 'METADATA') return {scope: 'TENANT', tenant: 'main', namespace: null, descendants: false, resource: 'METADATA', actions: item.actions.filter(action => ['READ', 'LIST'].includes(action)), ids: [], allIds: true}
    if (item.scope !== 'NAMESPACE' || item.tenant !== 'main' || typeof item.namespace !== 'string' || !item.namespace.trim()) throw Object.assign(new Error('invalid_grant'), {code: 'invalid_grant', status: 400})
    return {scope: 'NAMESPACE', tenant: 'main', namespace: item.namespace, descendants: item.descendants, resource: item.resource, actions: [...item.actions], allIds: item.allIds, ids: item.allIds ? [] : splitLines(item.ids)}
  })}
}
export function selectGrantResource(grant) {
  if (grant.resource === 'METADATA') Object.assign(grant, {scope: 'TENANT', tenant: 'main', namespace: null, descendants: false, actions: grant.actions.filter(action => ['READ', 'LIST'].includes(action)), ids: [], allIds: true})
  else if (grant.resource === 'MCP_SERVER') Object.assign(grant, {scope:'TENANT',tenant:'main',namespace:null,descendants:false,actions:grant.actions.filter(action => ['READ','LIST','CREATE','UPDATE','DELETE'].includes(action))})
  else if (grant.scope !== 'NAMESPACE') Object.assign(grant, {scope: 'NAMESPACE', tenant: 'main', namespace: '', descendants: false})
}
// Filtering is local because the frozen HTTP API specifies page/size only. Read all
// pages before filtering, and reject a moving or incomplete directory.
export async function collectPages(fetchPage, signal, expectedDirectoryRevision) {
  const items = []
  let total
  let directoryRevision = expectedDirectoryRevision
  for (let page = 1; ; page++) {
    if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError')
    const result = await fetchPage(page, 100)
    if (!Array.isArray(result.items) || !Number.isSafeInteger(result.total) || result.total < 0 || result.page !== page || result.size !== 100 || (total !== undefined && total !== result.total) || (!result.items.length && items.length < result.total)) {
      throw Object.assign(new Error('directory_changed'), {code: 'directory_changed', status: 409})
    }
    if (directoryRevision !== undefined || result.directoryRevision !== undefined) {
      if (!Number.isSafeInteger(result.directoryRevision) || result.directoryRevision < 1 || (directoryRevision !== undefined && result.directoryRevision !== directoryRevision)) {
        throw Object.assign(new Error('directory_changed'), {code: 'directory_changed', status: 409})
      }
      directoryRevision = result.directoryRevision
    }
    total = result.total
    items.push(...result.items)
    if (items.length > total) throw Object.assign(new Error('directory_changed'), {code: 'directory_changed', status: 409})
    if (items.length === total) return items
  }
}
export function canonicalPath(value) {
  const path = value.replace(/^\/+|\/+$/g, '')
  return path || 'application-groups'
}

export function memberInput(value) {
  if (typeof value.approved !== 'boolean') throw Object.assign(new Error('invalid_client_flags'), {code:'invalid_client_flags',status:400})
  const supportedScopeKeys=splitLines(value.supportedScopeKeys)
  if (new Set(supportedScopeKeys).size !== supportedScopeKeys.length || supportedScopeKeys.some(key=>!/^[a-z][a-z0-9-]{0,63}$/.test(key))) throw Object.assign(new Error('invalid_scope'),{code:'invalid_scope',status:400})
  return {clientId:value.clientId,approved:value.approved,audiences:splitLines(value.audiences),supportedScopeKeys}
}
export function managementAccess(capabilities, reauthenticationRequired = false) {
  const trusted = !reauthenticationRequired && capabilities?.allowed === true
  return {identity:trusted && capabilities.identityManagement === true, local:trusted && capabilities.localPolicyManagement === true}
}
export function isRuntimeApplication(capabilities, applicationGroupId, clientId) {
  return capabilities?.allowed === true && capabilities.applicationGroupId === applicationGroupId
    && typeof capabilities.runtimeApplicationClientId === 'string' && capabilities.runtimeApplicationClientId.length > 0
    && typeof clientId === 'string' && capabilities.runtimeApplicationClientId === clientId
}
export function applicationPermissionModel(scope, policy) {
  groupScopeInput(scope)
  return {scopeKey:scope.key,description:policy?.description ?? '',enabled:policy?.enabled ?? false,
    grants:copy(policy?.grants ?? []),managementGrants:copy(policy?.managementGrants ?? [])}
}
export function runtimePolicyView(value,applicationGroupId,clientId) {
  const invalid = () => {throw Object.assign(new Error('invalid_runtime_policy'),{code:'invalid_runtime_policy',status:503})}
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !['revision','applicationGroupId','clientId','policy'].includes(key))
      || !Number.isSafeInteger(value.revision) || value.revision < 1 || typeof applicationGroupId !== 'string' || !applicationGroupId || value.applicationGroupId !== applicationGroupId
      || typeof clientId !== 'string' || !clientId || value.clientId !== clientId || !Object.hasOwn(value,'policy')) invalid()
  if (value.policy !== null && (typeof value.policy !== 'object' || Array.isArray(value.policy)
      || Object.keys(value.policy).some(key => !['issuer','audiences','enabled','scopes'].includes(key))
      || typeof value.policy.issuer !== 'string' || !Array.isArray(value.policy.audiences)
      || typeof value.policy.enabled !== 'boolean' || !value.policy.scopes || typeof value.policy.scopes !== 'object' || Array.isArray(value.policy.scopes))) invalid()
  return copy(value)
}
export function runtimeScopeDefinition(policy,key) {
  return policy?.scopes && Object.hasOwn(policy.scopes,key) ? policy.scopes[key] : null
}
export function availableDeclarationScopes(scopes,supported,declarations) {
  const allowed = new Set(supported)
  const existing = new Set(declarations.map(declaration => declaration.scopeKey))
  return scopes.filter(scope => typeof scope.key === 'string' && /^[a-z][a-z0-9-]{0,63}$/.test(scope.key)
    && allowed.has(scope.key) && !existing.has(scope.key))
}
export function requiresReauthentication(failure) {
  return failure?.status === 401 || ['directory_revision_changed','identity_epoch_changed','stale_identity','session_expired'].includes(failure?.code)
}
export function loginEntry(apiBase) {
  if (typeof apiBase !== 'string' || !apiBase.startsWith('/') || apiBase.startsWith('//')) throw new Error('invalid_api_base')
  const index = apiBase.indexOf('/api/v1/')
  if (index < 0) throw new Error('invalid_api_base')
  return apiBase.slice(0,index) + '/ui/'
}

export function managementGrantInput(value) {
  const applicationGroupIds = splitLines(value.applicationGroupIds ?? [])
  if (!['IDENTITY','LOCAL_POLICY','BUSINESS_INSTANCE'].includes(value.domain) || applicationGroupIds.some(id => !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(id)) || new Set(applicationGroupIds).size !== applicationGroupIds.length || value.domain !== 'LOCAL_POLICY' && applicationGroupIds.length) throw Object.assign(new Error('invalid_management_project_ids'),{code:'invalid_management_project_ids',status:400})
  if (!Array.isArray(value.actions) || !value.actions.length || new Set(value.actions).size !== value.actions.length || value.actions.some(action => !['READ','MANAGE'].includes(action))) throw Object.assign(new Error('invalid_management_actions'),{code:'invalid_management_actions',status:400})
  return {domain:value.domain,actions:[...value.actions],applicationGroupIds}
}
