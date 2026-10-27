import {ordered} from './access-control-model.js'
export function scopeReferenceLabels(references) {
  if (!Array.isArray(references)) return []
  return references.flatMap(value => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return []
    if (value.kind === 'permission' && typeof value.clientId === 'string' && value.clientId && typeof value.permissionKey === 'string' && value.permissionKey && value.issuer == null && value.subject == null) return [`${value.clientId}: ${value.permissionKey}`]
    if (value.kind === 'subject' && typeof value.issuer === 'string' && value.issuer && typeof value.subject === 'string' && value.subject && value.clientId == null && value.permissionKey == null) return [`subject: ${value.subject}`]
    return []
  })
}
export function withAccountState(grants, users) {
  const accounts = new Map(users.map(user => [user.subject, user]))
  return grants.map(grant => ({...grant, active: accounts.get(grant.subject)?.active === true}))
}
export function scopeRelations(scopeKey, applications, grants) {
  const apps = ordered(applications.filter(app => app.supportedScopeKeys?.includes(scopeKey)), 'clientId').map(app => ({clientId: app.clientId, permissions: ordered((app.permissions ?? []).filter(permission => permission.scopeKeys.includes(scopeKey)))}))
  const subjects = ordered(grants.filter(grant => grant.scopeKeys.includes(scopeKey)), 'subject')
  return {applications: apps, subjects}
}
export function blockingReferences(scopeKey, applications, subjects) {
  return [
    ...applications.flatMap(app => (app.permissions ?? []).filter(permission => permission.scopeKeys.includes(scopeKey)).map(permission => `${app.clientId}: ${permission.key}`)),
    ...subjects.filter(subject => subject.scopeKeys.includes(scopeKey)).map(subject => `subject: ${subject.subject}`)
  ]
}
