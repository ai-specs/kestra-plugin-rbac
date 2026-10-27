import {revealOneTimeValue} from './credential-state.js'
export async function createAccount(api, draft, oneTime, accept = () => true) {
  const receipt = await api.createUser({username: draft.username, name: draft.name ?? '', email: draft.email ?? ''})
  if (receipt?.subject !== draft.username || !Number.isSafeInteger(receipt.revision) || receipt.revision < 0 || typeof receipt.temporaryPassword !== 'string' || !receipt.temporaryPassword) throw new Error('invalid_account_receipt')
  if (accept()) revealOneTimeValue(oneTime, receipt.temporaryPassword, receipt.subject, 'password')
  return {subject: receipt.subject, revision: receipt.revision}
}
export async function rotateCredential(api, groupId, credential, oneTime, accept = () => true) {
  if (credential.clientType !== 'confidential' || credential.rotationMode !== 'online') throw new Error('rotation_not_online')
  const receipt = await api.rotateCredentials(groupId, credential.clientId, credential.credentialRevision)
  if (receipt?.clientId !== credential.clientId || !Number.isSafeInteger(receipt.credentialRevision) || receipt.credentialRevision <= credential.credentialRevision || typeof receipt.clientSecret !== 'string' || !receipt.clientSecret) throw new Error('invalid_credential_receipt')
  if (accept()) revealOneTimeValue(oneTime, receipt.clientSecret, receipt.clientId, 'secret')
  return {clientId: receipt.clientId, credentialRevision: receipt.credentialRevision}
}
