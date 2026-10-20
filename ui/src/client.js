import {memberInput} from './project-model.js'

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
      throw error
    }
    return body
  }
  const write = (path, method, body, revision) => {
    if (revision === undefined || revision === null) throw new Error('revision_required')
    return call(path, {method, headers: {'If-Match': '"' + revision + '"'}, ...(body === undefined ? {} : {body: JSON.stringify(body)})})
  }
  const project = id => '/projects/' + encodeURIComponent(id)
  const identity = id => '/identity' + project(id)
  const page = (path, current = 1, size = 100) => call(path + '?page=' + current + '&size=' + size)
  const assignment = (id, subject) => identity(id) + '/assignments/detail?subject=' + encodeURIComponent(subject)
  const declarations = (group, clientId) => '/application-groups/' + encodeURIComponent(group) + '/applications/' + encodeKey(clientId) + '/scope-declarations'
  return {
    capabilities: () => call('/capabilities'),
    policy: () => call('/policy'),
    createProject: (value, revision) => write('/projects', 'POST', value, revision),
    updateProject: (id, value, revision) => write(project(id), 'PUT', value, revision),
    bindingOptions: id => call(project(id) + '/binding-options'),
    reconciliation: id => call(project(id) + '/reconciliation'),
    reconcileProject: (id, value, revision) => write(project(id) + '/reconciliation', 'PUT', value, revision),
    scopes: id => call(project(id) + '/scopes'),
    scope: (id, name) => call(project(id) + '/scopes/' + encodeKey(name)),
    createScope: (id, value, revision) => write(project(id) + '/scopes', 'POST', value, revision),
    updateScope: (id, name, value, revision) => write(project(id) + '/scopes/' + encodeKey(name), 'PUT', value, revision),
    deleteScope: (id, name, revision) => write(project(id) + '/scopes/' + encodeKey(name), 'DELETE', undefined, revision),
    identityProjects: (current, size) => page('/identity/projects', current, size),
    identityProject: id => call(identity(id)),
    createIdentityProject: value => call('/identity/projects', {method: 'POST', body: JSON.stringify(value)}),
    updateIdentityProject: (id, value, revision) => write(identity(id), 'PUT', value, revision),
    identityScopes: (id, current, size) => page(identity(id) + '/scopes', current, size),
    identityScope: (id, name) => call(identity(id) + '/scopes/' + encodeKey(name)),
    createIdentityScope: (id, value, revision) => write(identity(id) + '/scopes', 'POST', value, revision),
    updateIdentityScope: (id, name, value, revision) => write(identity(id) + '/scopes/' + encodeKey(name), 'PUT', value, revision),
    deleteIdentityScope: (id, name, revision) => write(identity(id) + '/scopes/' + encodeKey(name), 'DELETE', undefined, revision),
    applicationScopeDeclarations: (group, clientId) => call(declarations(group, clientId)),
    createApplicationScopeDeclaration: (group, clientId, scope, permissions) => call(declarations(group, clientId) + '/' + encodeURIComponent(scope), {method: 'POST', body: JSON.stringify({permissions})}),
    updateApplicationScopeDeclaration: (group, clientId, scope, permissions, revision) => write(declarations(group, clientId) + '/' + encodeURIComponent(scope), 'PUT', {permissions}, revision),
    deleteApplicationScopeDeclaration: (group, clientId, scope, revision) => write(declarations(group, clientId) + '/' + encodeURIComponent(scope), 'DELETE', undefined, revision),
    clients: (id, current, size) => page(identity(id) + '/clients', current, size),
    client: (id, clientId) => call(identity(id) + '/clients/' + encodeKey(clientId)),
    createClient: (id, value, revision) => write(identity(id) + '/clients', 'POST', memberInput(value), revision),
    updateClient: (id, clientId, value, revision) => {
      if (value.clientId !== clientId) throw new Error('client_id_immutable')
      return write(identity(id) + '/clients/' + encodeKey(clientId), 'PUT', memberInput(value), revision)
    },
    deleteClient: (id, clientId, revision) => write(identity(id) + '/clients/' + encodeKey(clientId), 'DELETE', undefined, revision),
    assignments: (id, current, size) => page(identity(id) + '/assignments', current, size),
    assignment: (id, subject) => call(assignment(id, subject)),
    createAssignment: (id, value, revision) => write(identity(id) + '/assignments', 'POST', value, revision),
    updateAssignment: (id, value, revision) => write(assignment(id, value.subject), 'PUT', value, revision),
    deleteAssignment: (id, subject, revision) => write(assignment(id, subject), 'DELETE', undefined, revision),
    users: (current, size) => page('/users', current, size),
    user: subject => call('/users/detail?subject=' + encodeURIComponent(subject)),
    createUser: value => call('/users', {method: 'POST', body: JSON.stringify(value)}),
    updateProfile: (value, revision) => write('/users/profile', 'PUT', value, revision),
    updateState: (subject, active, revision) => write('/users/state', 'PUT', {subject, active}, revision),
    resetPassword: (subject, password, revision) => write('/users/password', 'PUT', {subject, password}, revision)
  }
}
