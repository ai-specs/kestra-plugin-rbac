import {memberInput} from './application-group-model.js'

// Opaque identifiers: UTF-8 base64url, without padding, with a required ~ prefix.
export function encodeKey(value) {
  const bytes = new TextEncoder().encode(value)
  return '~' + btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join('')).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

export function decodeKey(value) {
  if (!/^~[A-Za-z0-9_-]+$/.test(value)) throw new Error('invalid_identifier')
  const decoded = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(Uint8Array.from(atob(value.slice(1).replaceAll('-', '+').replaceAll('_', '/')), character => character.charCodeAt(0)))
  if (encodeKey(decoded) !== value) throw new Error('invalid_identifier')
  return decoded
}

export function createClient(currentContext) {
  async function call(path, options = {}) {
    const context = typeof currentContext === 'function' ? currentContext() : currentContext
    const base = context.apiBase.replace(/\/$/, '') + '/extensions/rbac'
    const headers = {Accept: 'application/json', ...(options.body ? {'Content-Type': 'application/json'} : {}), ...(options.headers || {})}
    const response = await context.request(base + path, {...options, headers, signal: context.signal})
    const body = typeof response?.json === 'function' ? await response.json().catch(() => ({})) : response
    if (!response || response.ok === false || (response.status && response.status >= 400)) {
      const error = new Error(body?.code || 'request_failed')
      error.code = body?.code
      error.status = response?.status
      if (body?.code === 'scope_in_use' && Array.isArray(body.references)) error.references = body.references
      throw error
    }
    return body
  }
  const write = (path, method, body, revision) => {
    if (revision === undefined || revision === null) throw new Error('revision_required')
    return call(path, {method, cache: 'no-store', headers: {'If-Match': '"' + revision + '"'}, ...(body === undefined ? {} : {body: JSON.stringify(body)})})
  }
  const group = id => '/application-groups/' + encodeURIComponent(id)
  const page = (path, current = 1, size = 100) => call(path + '?page=' + current + '&size=' + size)
  const assignment = (id, subject) => group(id) + '/scope-grants/detail?subject=' + encodeURIComponent(subject)
  const permissions = (id, clientId) => group(id) + '/applications/' + encodeKey(clientId) + '/permissions'
  return {
    capabilities: applicationGroupId => call('/capabilities' + (applicationGroupId ? '?applicationGroupId=' + encodeURIComponent(applicationGroupId) : '')),
    permissions: (id, clientId, current, size) => page(permissions(id, clientId), current, size),
    permission: (id, clientId, key) => call(permissions(id, clientId) + '/' + encodeURIComponent(key)),
    permissionMutationMetadata: (id, clientId) => call(permissions(id, clientId) + '/-/mutation-metadata', {cache: 'no-store'}),
    createPermission: (id, clientId, value, revision) => write(permissions(id, clientId), 'POST', value, revision),
    updatePermission: (id, clientId, key, value, revision) => {
      if (value.key !== key) throw new Error('permission_key_immutable')
      return write(permissions(id, clientId) + '/' + encodeURIComponent(key), 'PUT', value, revision)
    },
    deletePermission: (id, clientId, key, revision) => write(permissions(id, clientId) + '/' + encodeURIComponent(key), 'DELETE', undefined, revision),
    credentials: (id, clientId) => call(group(id) + '/applications/' + encodeKey(clientId) + '/credentials', {cache: 'no-store'}),
    rotateCredentials: (id, clientId, revision) => write(group(id) + '/applications/' + encodeKey(clientId) + '/credentials/rotate', 'POST', undefined, revision),
    applicationGroups: (current, size) => page('/application-groups', current, size),
    applicationGroup: id => call(group(id)),
    createApplicationGroup: value => call('/application-groups', {method: 'POST', body: JSON.stringify(value)}),
    updateApplicationGroup: (id, value, revision) => write(group(id), 'PUT', value, revision),
    deleteApplicationGroup: (id, revision) => write(group(id), 'DELETE', undefined, revision),
    applicationGroupScopes: (id, current, size) => page(group(id) + '/scopes', current, size),
    applicationGroupScope: (id, name) => call(group(id) + '/scopes/' + encodeURIComponent(name)),
    createApplicationGroupScope: (id, value, revision) => write(group(id) + '/scopes', 'POST', value, revision),
    updateApplicationGroupScope: (id, name, value, revision) => write(group(id) + '/scopes/' + encodeURIComponent(name), 'PUT', value, revision),
    deleteApplicationGroupScope: (id, name, revision) => write(group(id) + '/scopes/' + encodeURIComponent(name), 'DELETE', undefined, revision),
    applications: (id, current, size) => page(group(id) + '/applications', current, size),
    application: (id, clientId) => call(group(id) + '/applications/' + encodeKey(clientId)),
    createApplication: (id, value, revision) => write(group(id) + '/applications', 'POST', memberInput(value), revision),
    updateApplication: (id, clientId, value, revision) => {
      if (value.clientId !== clientId) throw new Error('client_id_immutable')
      return write(group(id) + '/applications/' + encodeKey(clientId), 'PUT', memberInput(value), revision)
    },
    deleteApplication: (id, clientId, revision) => write(group(id) + '/applications/' + encodeKey(clientId), 'DELETE', undefined, revision),
    scopeGrants: (id, current, size) => page(group(id) + '/scope-grants', current, size),
    scopeGrant: (id, subject) => call(assignment(id, subject)),
    createScopeGrant: (id, value, revision) => write(group(id) + '/scope-grants', 'POST', value, revision),
    updateScopeGrant: (id, value, revision) => write(assignment(id, value.subject), 'PUT', value, revision),
    deleteScopeGrant: (id, subject, revision) => write(assignment(id, subject), 'DELETE', undefined, revision),
    users: (current, size) => page('/users', current, size),
    user: subject => call('/users/detail?subject=' + encodeURIComponent(subject)),
    createUser: value => call('/users', {method: 'POST', cache: 'no-store', body: JSON.stringify({username: value.username, name: value.name, email: value.email})}),
    deleteUser: (subject, revision) => write('/users/detail?subject=' + encodeURIComponent(subject), 'DELETE', undefined, revision),
    updateProfile: (value, revision) => write('/users/profile', 'PUT', value, revision),
    updateState: (subject, active, revision) => write('/users/state', 'PUT', {subject, active}, revision),
    resetPassword: (subject, password, revision) => write('/users/password', 'PUT', {subject, password}, revision)
  }
}
