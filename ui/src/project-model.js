export const resources = ['FLOW', 'EXECUTION', 'NAMESPACE', 'LOG', 'METRIC', 'TRIGGER', 'KVSTORE', 'SECRET', 'NAMESPACE_FILE', 'DASHBOARD', 'BLUEPRINT', 'MCP_SERVER', 'AI', 'METADATA']
export const actions = ['READ', 'LIST', 'CREATE', 'UPDATE', 'DELETE', 'EXECUTE', 'ACCESS_OUTPUTS']
export const splitLines = value => Array.isArray(value) ? [...value] : String(value ?? '').split(/\r?\n/).map(item => item.trim()).filter(Boolean)
export const copy = value => JSON.parse(JSON.stringify(value))
export const newGrant = () => ({scope: 'NAMESPACE', tenant: 'main', namespace: '', descendants: false, resource: 'FLOW', actions: [], ids: [], allIds: true})
export const newIntent = () => ({vocabularyVersion: 'kestra-resources-v1', resourceType: 'FLOW', namespaces: {kind: 'RESTRICTED', exact: [], subtrees: []}, resourceIds: {kind: 'ALL', ids: []}, actions: []})
export function intentInput(value) {
  return {
    scope: value.scope, description: value.description, active: value.active,
    resourceIntents: value.resourceIntents.map(item => ({
      vocabularyVersion: 'kestra-resources-v1', resourceType: item.resourceType,
      namespaces: {kind: item.namespaces.kind, exact: item.namespaces.kind === 'ALL' ? [] : splitLines(item.namespaces.exact), subtrees: item.namespaces.kind === 'ALL' ? [] : splitLines(item.namespaces.subtrees)},
      resourceIds: {kind: item.resourceIds.kind, ids: item.resourceIds.kind === 'ALL' ? [] : splitLines(item.resourceIds.ids)}, actions: [...item.actions]
    })),
    managementIntents: value.managementIntents.map(managementGrantInput)
  }
}
export function scopeInput(value) {
  if (!Array.isArray(value.grants)) throw Object.assign(new Error('invalid_grant'), {code:'invalid_grant',status:400})
  return {name: value.name, description: value.description, enabled: value.enabled, managementGrants: (value.managementGrants ?? []).map(managementGrantInput), grants: value.grants.map(item => {
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
  return !path || ['roles', 'bindings', 'role-mappings'].includes(path.split('/')[0]) ? 'projects' : path
}

// Identity flags are explicit booleans, never inferred from client names/IDs.
export function memberInput(value) {
  if (typeof value.approved !== 'boolean' || typeof value.firstPartyUi !== 'boolean') throw Object.assign(new Error('invalid_client_flags'), {code:'invalid_client_flags',status:400})
  return {clientId:value.clientId, approved:value.approved, firstPartyUi:value.firstPartyUi, audiences:splitLines(value.audiences), allowedProjectScopes:splitLines(value.allowedProjectScopes)}
}
export function managementAccess(capabilities, reauthenticationRequired = false) {
  const trusted = !reauthenticationRequired && capabilities?.allowed === true
  return {identity:trusted && capabilities.identityManagement === true, local:trusted && capabilities.localPolicyManagement === true}
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

export function reconciliationInput(value) {
  if (!value || typeof value.id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value.id) || typeof value.issuer !== 'string' || typeof value.enabled !== 'boolean' || !Number.isSafeInteger(Number(value.directoryRevision)) || Number(value.directoryRevision) < 1 || !value.scopes || Array.isArray(value.scopes) || typeof value.scopes !== 'object') throw Object.assign(new Error('invalid_reconciliation'),{code:'invalid_reconciliation',status:400})
  const scopes = Object.fromEntries(Object.entries(value.scopes).map(([name, scope]) => {
    const input = scopeInput({...scope,name})
    if (!input.grants.length && !input.managementGrants.length) throw Object.assign(new Error('invalid_reconciliation'),{code:'invalid_reconciliation',status:400})
    const {name:ignored,...binding} = input
    return [name,binding]
  }))
  return {id:value.id,issuer:value.issuer,audiences:splitLines(value.audiences),enabled:value.enabled,directoryRevision:Number(value.directoryRevision),scopes}
}

export function managementGrantInput(value) {
  const projectIds = splitLines(value.projectIds ?? [])
  if (!['IDENTITY','LOCAL_POLICY','BUSINESS_INSTANCE'].includes(value.domain) || projectIds.some(id => !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(id)) || new Set(projectIds).size !== projectIds.length || value.domain !== 'LOCAL_POLICY' && projectIds.length) throw Object.assign(new Error('invalid_management_project_ids'),{code:'invalid_management_project_ids',status:400})
  if (!Array.isArray(value.actions) || !value.actions.length || new Set(value.actions).size !== value.actions.length || value.actions.some(action => !['READ','MANAGE'].includes(action))) throw Object.assign(new Error('invalid_management_actions'),{code:'invalid_management_actions',status:400})
  return {domain:value.domain,actions:[...value.actions],projectIds}
}

// directoryRevision pins the shared Project.revision (directory edit revision).
// authorizationVersion is a separate token/session revocation epoch; never use
// it to confirm local policy bindings, even when the two numbers happen to match.
export function directoryNeedsReconciliation(project, localProject) {
  return Boolean(project && localProject && Number(project.revision) !== Number(localProject.directoryRevision))
}


// Read-only, target-specific editor input. It never confers IDENTITY authority.
export function bindingOptionsModel(value, projectId) {
  const invalid = () => {throw Object.assign(new Error('invalid_binding_options'), {code:'invalid_binding_options',status:400})}
  if (!value || !Number.isSafeInteger(value.policyRevision) || value.policyRevision < 1 || !Number.isSafeInteger(value.directoryRevision) || value.directoryRevision < 1 || value.upperBound?.project?.id !== projectId || value.upperBound.project.revision !== value.directoryRevision || !Array.isArray(value.upperBound.scopes)) invalid()
  const names = new Set()
  for (const scope of value.upperBound.scopes) {
    if (typeof scope.scope !== 'string' || !scope.scope || names.has(scope.scope) || typeof scope.active !== 'boolean') invalid()
    names.add(scope.scope)
  }
  return copy(value)
}
export function newLocalBinding(options, name) {
  const scope = options.upperBound.scopes.find(item => item.scope === name && item.active === true)
  if (!scope) throw Object.assign(new Error('invalid_scope'), {code:'invalid_scope',status:400})
  // Explicitly start empty and disabled. Shared intent is an upper bound, not
  // a permission template or an instruction to enable the local binding.
  return {name:scope.scope,description:scope.description ?? '',enabled:false,grants:[],managementGrants:[]}
}
