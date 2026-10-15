export function createClient(currentContext) {
  async function call(path, options = {}) {
    const context = typeof currentContext === 'function' ? currentContext() : currentContext
    const base = context.apiBase.replace(/\/$/, '') + '/extensions/rbac'
    const headers = { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) }
    const response = await context.request(base + path, { ...options, headers })
    const body = typeof response?.json === 'function' ? await response.json().catch(() => ({})) : response
    if (!response || response.ok === false || (response.status && response.status >= 400)) {
      const error = new Error(body?.code || 'request_failed')
      error.code = body?.code
      error.status = response?.status
      throw error
    }
    return body
  }
  const match = revision => ({ 'If-Match': '"' + revision + '"' })
  return {
    capabilities: () => call('/capabilities'),
    policy: () => call('/policy'),
    createRole: (role, revision) => call('/roles', { method: 'POST', headers: match(revision), body: JSON.stringify(role) }),
    updateRole: (id, role, revision) => call('/roles/' + encodeURIComponent(id), { method: 'PUT', headers: match(revision), body: JSON.stringify(role) }),
    deleteRole: (id, revision) => call('/roles/' + encodeURIComponent(id), { method: 'DELETE', headers: match(revision) }),
    bindings: (subject, roles, revision) => call('/bindings', { method: 'PUT', headers: match(revision), body: JSON.stringify({ subject, roles }) }),
    roleMappings: (trustedRole, roles, revision) => call('/role-mappings', { method: 'PUT', headers: match(revision), body: JSON.stringify({ trustedRole, roles }) }),
    users: (page = 1, size = 20) => call('/users?page=' + page + '&size=' + size),
    user: subject => call('/users/detail?subject=' + encodeURIComponent(subject)),
    createUser: user => call('/users', { method: 'POST', body: JSON.stringify(user) }),
    updateProfile: (user, revision) => call('/users/profile', { method: 'PUT', headers: match(revision), body: JSON.stringify(user) }),
    updateState: (subject, active, revision) => call('/users/state', { method: 'PUT', headers: match(revision), body: JSON.stringify({ subject, active }) }),
    resetPassword: (subject, password, revision) => call('/users/password', { method: 'PUT', headers: match(revision), body: JSON.stringify({ subject, password }) })
  }
}
