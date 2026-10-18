<script setup>
import {computed, ref, watch, onBeforeUnmount, nextTick} from 'vue'
import {useI18n} from 'vue-i18n'
import {useRouter} from 'vue-router'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import KsTabs from '@kestra-io/design-system/components/Navigation/KsTabs/KsTabs'
import KsTabPane from '@kestra-io/design-system/components/Navigation/KsTabs/KsTabPane'
import ProjectList from './ProjectList.vue'
import GrantEditor from './GrantEditor.vue'
import ProjectRecoveryEditor from './ProjectRecoveryEditor.vue'
import IntentEditor from './IntentEditor.vue'
import {createClient, encodeKey, decodeKey} from './client.js'
import {canonicalPath, collectPages, splitLines, copy, intentInput, scopeInput, memberInput, managementAccess, requiresReauthentication, loginEntry, reconciliationInput, directoryNeedsReconciliation, bindingOptionsModel, newLocalBinding} from './project-model.js'

const props = defineProps({context: {type: Object, required: true}})
const {t} = useI18n()
const router = useRouter()
const api = createClient(() => props.context)
const path = computed(() => canonicalPath(props.context.path))
const parts = computed(() => path.value.split('/'))
const isUsers = computed(() => parts.value[0] === 'users')
const projectId = computed(() => !isUsers.value && parts.value[1] && parts.value[1] !== 'new' ? decodeURIComponent(parts.value[1]) : '')
const tab = computed(() => parts.value[2] || 'overview')
const detail = computed(() => isUsers.value ? parts.value[1] : parts.value[3])
const isNew = computed(() => detail.value === 'new')
const projectNew = computed(() => path.value === 'projects/new')
const caps = ref(null)
const view = ref(null)
const project = ref(null)
const localProject = ref(null)
const identityScope = ref(null)
const localScope = ref(null)
const localScopeExists = ref(false)
const bindingOptions = ref(null)
const bindingName = ref('')
const bindingNew = computed(() => !isUsers.value && tab.value === 'bindings' && detail.value === 'new')
const recovery = ref(null)
const recoveryTarget = ref('')
const member = ref(null)
const assignment = ref(null)
const user = ref(null)
const password = ref('')
const items = ref([])
const scopeChoices = ref([])
const ready = ref(false)
const savedIdentityScope = ref(null)
const savedMember = ref(null)
const savedAssignment = ref(null)
const busy = ref(false)
const error = ref('')
const errorCode = ref('')
const reauthenticationRequired = ref(false)
const reauthenticationHref = computed(() => loginEntry(props.context.apiBase))
const notice = ref('')
const savedResult = ref(null)
const conflicted = ref(false)
const confirmation = ref('')
const main = ref(null)
let generation = 0
let disposed = false
let pendingLoad = false
const access = computed(() => managementAccess(caps.value, reauthenticationRequired.value))
const identityAllowed = computed(() => access.value.identity)
const localAllowed = computed(() => access.value.local && view.value?.readOnly === false)
const identityDisabled = computed(() => busy.value || conflicted.value || !identityAllowed.value)
const recoveryDisabled = computed(() => busy.value || conflicted.value || !access.value.local)
const localDisabled = computed(() => busy.value || conflicted.value || !localAllowed.value)
const usersDisabled = computed(() => identityDisabled.value || !caps.value?.userDirectory)
const storageKey = computed(() => ['rbac-management-filters', props.context.tenant, caps.value?.subject, path.value].join(':'))
const needsReconciliation = computed(() => caps.value?.needsReconciliation === true || directoryNeedsReconciliation(project.value, localProject.value))
const base = computed(() => 'projects/' + encodeURIComponent(projectId.value))
const columns = computed(() => isUsers.value ? ['subject', 'name', 'email', 'active'] : !projectId.value ? ['id', 'displayName', 'issuer', 'active', 'localStatus'] : tab.value === 'scopes' ? ['scope', 'description', 'active', 'localStatus', 'namespace', 'resource', 'actions', 'managementDomain'] : tab.value === 'clients' ? ['clientId', 'approved', 'audiences', 'allowedProjectScopes'] : ['subject', 'issuer', 'projectScopes'])
const showList = computed(() => !projectNew.value && (isUsers.value ? !detail.value : !projectId.value || tab.value !== 'overview' && !detail.value))
const selectedScope = computed(() => detail.value && !isNew.value && tab.value === 'scopes' ? decodeKey(detail.value) : '')
const localPolicyProject = () => view.value?.policy?.applicationProjects?.[projectId.value]
const policyProjectInput = () => ({id: localProject.value.id, issuer: localProject.value.issuer, audiences: splitLines(localProject.value.audiences), enabled: localProject.value.enabled, directoryRevision: Number(localProject.value.directoryRevision)})
const text = value => Array.isArray(value) ? value.join('\n') : value
const navigate = value => props.context.navigate(value)
const projectPath = id => 'projects/' + encodeURIComponent(id)
const displayLocalStatus = value => t(value ? value.enabled ? 'bound' : 'inactive' : 'unbound')
const scopeRows = (directory, local) => {
  const merged = new Map(directory.map(item => [item.scope, {...item}]))
  for (const [scope, value] of Object.entries(local ?? {})) if (!merged.has(scope)) merged.set(scope, {scope, description: value.description, active: undefined})
  return [...merged.values()].map(item => {
    const grants = local?.[item.scope]?.grants ?? []
    return {...item, localStatus: displayLocalStatus(local?.[item.scope]), namespace: grants.map(grant => grant.namespace ?? grant.scope).join(', '), resource: grants.map(grant => grant.resource).join(', '), actions: [...new Set(grants.flatMap(grant => grant.actions))], managementDomain: (local?.[item.scope]?.managementGrants ?? []).map(grant => grant.domain + ': ' + grant.actions.join('/')).join(', ')}
  })
}
function failureMessage(failure) {
  if (requiresReauthentication(failure)) return t('reauthenticationRequired')
  if (failure.status === 412) return t('conflict')
  if (failure.code === 'protected_subject') return t('protected')
  if (/first_party|receipt/i.test(failure.code ?? '')) return t('firstPartyUiReceipt')
  if (failure.status === 401 || failure.status === 403) return t('denied')
  if (/metadata/i.test(failure.code ?? '')) return t('metadataRange')
  if (/directory|stale/.test(failure.code ?? '')) return t('directoryStale')
  if (failure.status === 409 || failure.status === 428) return t('conflict')
  if (failure.status === 404) return t('notFound')
  return t('error')
}
function handleFailure(failure) {
  errorCode.value = /^[a-z0-9_]{1,80}$/.test(failure.code ?? '') ? failure.code : ''
  if (requiresReauthentication(failure)) {reauthenticationRequired.value = true; caps.value = null}
}
async function perform(action, message = 'saved', identityMutation = false) {
  if (busy.value || disposed || props.context.signal.aborted || reauthenticationRequired.value || identityMutation && !identityAllowed.value) return
  busy.value = true
  error.value = ''
  errorCode.value = ''
  notice.value = ''
  savedResult.value = null
  const current = generation
  try {
    await action()
    if (!disposed && current === generation) {
      notice.value = t(message)
      if (identityMutation) {reauthenticationRequired.value = true; caps.value = null}
    }
  } catch (failure) {
    if (!disposed && current === generation && failure.name !== 'AbortError') {
      if (failure.status === 412 || failure.status === 409 || failure.status === 428) conflicted.value = true
      handleFailure(failure)
      error.value = failureMessage(failure)
      await nextTick()
      main.value?.querySelector('[role="alert"]')?.focus()
    }
  } finally {
    busy.value = false
    if (pendingLoad && !disposed) {pendingLoad = false; void load()}
  }
}
async function optional(operation) {
  try {return await operation()} catch (failure) {if (failure.status === 404) return null; throw failure}
}
async function load() {
  if (busy.value) {pendingLoad = true; return}
  const current = ++generation
  ready.value = false
  recovery.value = null
  bindingOptions.value = null
  bindingName.value = ''
  savedIdentityScope.value = savedMember.value = savedAssignment.value = null
  confirmation.value = ''
  error.value = ''
  errorCode.value = ''
  notice.value = ''
  // Clear previous path data before any awaited call: failed navigation must not expose an old editor.
  project.value = localProject.value = identityScope.value = localScope.value = member.value = assignment.value = user.value = null
  items.value = []
  scopeChoices.value = []
  password.value = ''
  busy.value = true
  try {
    const raw = props.context.path.replace(/^\/+|\/+$/g, '')
    if (raw !== path.value) {await props.context.navigate(path.value, {replace: true}); return}
    await router.replace({path: '/' + path.value, query: {}})
    const nextCaps = await api.capabilities()
    if (current !== generation || disposed) return
    if (reauthenticationRequired.value) throw {status:401}
    caps.value = nextCaps
    if (!nextCaps.allowed) throw {status: 403}
    if (!isUsers.value && projectId.value && tab.value === 'reconciliation') {
      if (!access.value.local) throw {status:403}
      const projection = await api.reconciliation(projectId.value)
      if (projection.project?.id !== projectId.value || !Number.isSafeInteger(projection.policyRevision) || projection.policyRevision < 1 || !Number.isSafeInteger(projection.directoryRevision) || projection.directoryRevision < 1 || projection.upperBound?.project?.id !== projectId.value || !Array.isArray(projection.upperBound.scopes)) throw new Error('invalid_reconciliation_projection')
      const candidate = copy(projection.project)
      for (const scope of Object.values(candidate.scopes)) scope.managementGrants ??= []
      if (current !== generation || disposed) return
      recovery.value = {...projection,candidate}
      ready.value = true
      conflicted.value = false
      return
    }
    if (!isUsers.value && projectId.value && nextCaps.needsReconciliation === true) {await props.context.navigate(base.value + '/reconciliation',{replace:true}); return}
    if (bindingNew.value) {
      if (!access.value.local) throw {status:403}
      const projection = bindingOptionsModel(await api.bindingOptions(projectId.value), projectId.value)
      if (current !== generation || disposed) return
      bindingOptions.value = projection
      // Editing authority comes from the target-scoped API; no general policy
      // READ or IDENTITY directory request is required for this route.
      view.value = {mode:'database',revision:projection.policyRevision,readOnly:false}
      ready.value = true
      conflicted.value = false
      return
    }
    const nextView = nextCaps.localPolicyManagement && nextCaps.needsReconciliation !== true ? await api.policy() : null
    if (nextView && nextView.policy?.schemaVersion !== 2) throw new Error('invalid_policy_schema')
    if (current !== generation || disposed) return
    view.value = nextView
    const all = fetcher => collectPages(fetcher, props.context.signal)
    if (isUsers.value) {
      if (!nextCaps.userDirectory || !nextCaps.identityManagement) throw {status: 403}
      if (!detail.value) items.value = await all((page, size) => api.users(page, size))
      else user.value = isNew.value ? {username: '', name: '', email: '', password: ''} : await api.user(decodeKey(detail.value.startsWith('~') ? detail.value : '~' + detail.value))
    } else if (projectNew.value) {
      project.value = {id: '', tenant: 'main', displayName: ''}
    } else if (!projectId.value) {
      const directory = nextCaps.identityManagement ? await all((page, size) => api.identityProjects(page, size)) : []
      const merged = new Map(directory.map(item => [item.id, {...item}]))
      for (const [id, value] of Object.entries(nextView?.policy.applicationProjects ?? {})) if (!merged.has(id)) merged.set(id, {id, issuer: value.issuer})
      items.value = [...merged.values()].map(item => ({...item, localStatus: displayLocalStatus(nextView?.policy.applicationProjects[item.id])}))
    } else {
      project.value = nextCaps.identityManagement ? await optional(() => api.identityProject(projectId.value)) : null
      const stored = localPolicyProject()
      // Bind to the directory edit revision, not the independent authorizationVersion epoch.
      localProject.value = stored ? {...copy(stored), id: projectId.value} : project.value ? {id: projectId.value, issuer: project.value.issuer, audiences: [], enabled: false, directoryRevision: project.value.revision} : null
      if (!project.value && !stored) throw {status: 404}
      if (tab.value === 'scopes') {
        const directory = nextCaps.identityManagement && project.value ? await all((page, size) => api.identityScopes(projectId.value, page, size)) : []
        scopeChoices.value = directory
        if (!detail.value) items.value = scopeRows(directory, stored?.scopes)
        else {
          const name = isNew.value ? '' : decodeKey(detail.value)
          identityScope.value = isNew.value ? {projectId: projectId.value, scope: '', description: '', active: true, resourceIntents: [], managementIntents: []} : nextCaps.identityManagement ? await optional(() => api.identityScope(projectId.value, name)) : null
          const binding = stored?.scopes?.[name]
          localScopeExists.value = Boolean(binding)
          localScope.value = {name, description: binding?.description ?? identityScope.value?.description ?? '', enabled: binding?.enabled ?? false, grants: copy(binding?.grants ?? []), managementGrants: copy(binding?.managementGrants ?? [])}
          if (!isNew.value && !binding && !identityScope.value) throw {status: 404}
        }
      } else if (tab.value === 'clients' || tab.value === 'assignments') {
        if (!nextCaps.identityManagement || !project.value) throw {status: 403}
        scopeChoices.value = await all((page, size) => api.identityScopes(projectId.value, page, size))
        if (!detail.value) items.value = await all((page, size) => api[tab.value](projectId.value, page, size))
        else if (tab.value === 'clients') member.value = isNew.value ? {clientId: '', approved: false, firstPartyUi: false, audiences: [], allowedProjectScopes: []} : await api.client(projectId.value, decodeKey(detail.value))
        else assignment.value = isNew.value ? {issuer: project.value.issuer, subject: '', projectScopes: []} : await api.assignment(projectId.value, decodeKey(detail.value))
      } else if (tab.value !== 'overview') throw {status: 404}
    }
    if (current === generation && !disposed) {
      savedIdentityScope.value = identityScope.value ? copy(identityScope.value) : null
      savedMember.value = member.value ? copy(member.value) : null
      savedAssignment.value = assignment.value ? copy(assignment.value) : null
      conflicted.value = false
      ready.value = true
    }
  } catch (failure) {
    if (current === generation && !disposed && failure.name !== 'AbortError') {
      handleFailure(failure)
      error.value = failureMessage(failure)
      await nextTick()
      main.value?.querySelector('[role="alert"]')?.focus()
    }
  } finally {
    busy.value = false
    if (pendingLoad && !disposed) {pendingLoad = false; void load()}
  }
}
function openRow(row) {
  if (isUsers.value) return navigate('users/' + encodeKey(row.subject))
  if (!projectId.value) return navigate(projectPath(row.id))
  const key = tab.value === 'scopes' ? row.scope : tab.value === 'clients' ? row.clientId : row.subject
  return navigate(base.value + '/' + tab.value + '/' + encodeKey(key))
}
function create() {
  return navigate(isUsers.value ? 'users/new' : projectId.value ? base.value + '/' + tab.value + '/new' : 'projects/new')
}
function selectLocalBinding() {
  localScope.value = bindingName.value ? newLocalBinding(bindingOptions.value, bindingName.value) : null
}
async function saveNewLocalBinding() {
  if (!localAllowed.value || needsReconciliation.value || !bindingOptions.value || !localScope.value) return
  await perform(async () => {
    const input = scopeInput(localScope.value)
    if (input.name !== bindingName.value || !input.grants.length && !input.managementGrants.length) throw {code:'invalid_grant',status:400}
    const result = await api.createScope(projectId.value,input,bindingOptions.value.policyRevision)
    if (result.policy?.schemaVersion !== 2 || !result.policy.applicationProjects?.[projectId.value]?.scopes?.[input.name]) throw new Error('invalid_policy_response')
    view.value = result
    savedResult.value = {projectId:projectId.value, scope:input.name, revision:result.revision, binding:copy(result.policy.applicationProjects[projectId.value].scopes[input.name])}
    bindingOptions.value = null
    localScope.value = null
  }, 'localSaved')
}
async function saveIdentityProject() {
  await perform(async () => {
    if (projectNew.value) {
      const result = await api.createIdentityProject({id: project.value.id, tenant: 'main', displayName: project.value.displayName})
      project.value = result
    } else project.value = await api.updateIdentityProject(projectId.value, {displayName: project.value.displayName, active: project.value.active}, project.value.revision)
  }, 'identitySaved', true)
}
async function saveLocalProject() {
  if (!localAllowed.value || needsReconciliation.value) return
  await perform(async () => {
    const value = policyProjectInput()
    view.value = localPolicyProject() ? await api.updateProject(projectId.value, value, view.value.revision) : await api.createProject(value, view.value.revision)
    localProject.value = {...copy(localPolicyProject()), id: projectId.value}
  }, 'localSaved')
}
async function saveReconciliation(approved) {
  if (!approved || recoveryDisabled.value || !recovery.value) return
  await perform(async () => {
    const input = reconciliationInput(recovery.value.candidate)
    if (input.id !== projectId.value || input.directoryRevision !== recovery.value.directoryRevision) throw {code:'invalid_reconciliation',status:400}
    view.value = await api.reconcileProject(projectId.value,input,recovery.value.policyRevision)
    if (view.value.policy?.schemaVersion !== 2 || view.value.policy.applicationProjects[projectId.value]?.directoryRevision !== input.directoryRevision) throw new Error('invalid_reconciliation_response')
    savedResult.value = {id: input.id, revision: view.value.revision, directoryRevision: input.directoryRevision}
    reauthenticationRequired.value = true
    caps.value = null
  }, 'reconciliationSaved')
}
async function saveIdentityScope() {
  await perform(async () => {
    const input = intentInput(identityScope.value)
    identityScope.value = isNew.value ? await api.createIdentityScope(projectId.value, input, project.value.revision) : await api.updateIdentityScope(projectId.value, selectedScope.value, input, identityScope.value.revision)
    savedIdentityScope.value = copy(identityScope.value)
    savedResult.value = {scope: identityScope.value.scope, active: identityScope.value.active, revision: identityScope.value.revision}
    // Directory mutation invalidates identity; navigation resumes after production login.
  }, 'identitySaved', true)
}
async function saveLocalScope() {
  if (!localAllowed.value || needsReconciliation.value) return
  await perform(async () => {
    const input = scopeInput(localScope.value)
    if (!input.grants.length && !input.managementGrants.length) throw {status: 400}
    view.value = localScopeExists.value ? await api.updateScope(projectId.value, selectedScope.value, input, view.value.revision) : await api.createScope(projectId.value, input, view.value.revision)
    localProject.value = {...copy(localPolicyProject()), id: projectId.value}
    caps.value = await api.capabilities()
    localScopeExists.value = true
    localScope.value = {name: selectedScope.value, managementGrants: [], ...copy(localPolicyProject().scopes[selectedScope.value])}
  }, 'localSaved')
}
async function saveMember() {
  await perform(async () => {
    const input = memberInput(member.value)
    member.value = isNew.value ? await api.createClient(projectId.value, input, project.value.revision) : await api.updateClient(projectId.value, decodeKey(detail.value), input, member.value.revision)
    savedMember.value = copy(member.value)
    savedResult.value = {...memberInput(savedMember.value), revision: savedMember.value.revision}
    // Do not continue with the pre-mutation authenticated identity.
  }, 'identitySaved', true)
}
async function saveAssignment() {
  await perform(async () => {
    const input = {issuer: assignment.value.issuer, subject: assignment.value.subject, projectScopes: [...assignment.value.projectScopes]}
    assignment.value = isNew.value ? await api.createAssignment(projectId.value, input, project.value.revision) : await api.updateAssignment(projectId.value, input, assignment.value.revision)
    savedAssignment.value = copy(assignment.value)
    savedResult.value = {issuer: savedAssignment.value.issuer, subject: savedAssignment.value.subject, projectScopes: [...savedAssignment.value.projectScopes], revision: savedAssignment.value.revision}
    // A new sign-in is required before any subsequent protected request.
  }, 'identitySaved', true)
}
async function remove() {
  const identityMutation = confirmation.value !== 'local'
  await perform(async () => {
    if (confirmation.value === 'local') view.value = await api.deleteScope(projectId.value, selectedScope.value, view.value.revision)
    else if (confirmation.value === 'client') await api.deleteClient(projectId.value, decodeKey(detail.value), member.value.revision)
    else await api.deleteAssignment(projectId.value, assignment.value.subject, assignment.value.revision)
    if (!identityMutation) navigate(base.value + '/' + tab.value)
  }, identityMutation ? 'identitySaved' : 'saved', identityMutation)
}
async function saveUser() {
  await perform(async () => {
    if (isNew.value) {
      const result = await api.createUser({username: user.value.username, name: user.value.name, email: user.value.email, password: user.value.password})
      user.value.password = ''
      navigate('users/' + encodeKey(result.subject))
    } else user.value = await api.updateProfile({subject: user.value.subject, name: user.value.name, email: user.value.email}, user.value.revision)
  })
}
watch(() => props.context.path, () => {generation++; if (busy.value) pendingLoad = true; else void load()}, {immediate: true})
watch(() => props.context.tenant, () => {generation++; caps.value = view.value = null; if (busy.value) pendingLoad = true; else void load()})
onBeforeUnmount(() => {disposed = true; generation++; password.value = ''; if (user.value) user.value.password = ''})
</script>

<template>
  <main ref="main" :aria-busy="busy">
    <KsTabs type="box" :model-value="isUsers ? 'users' : 'projects'" :aria-label="t('title')" @update:model-value="navigate">
      <KsTabPane name="projects" :label="t('projects')" /><KsTabPane name="users" :label="t('users')" />
    </KsTabs>
    <header class="management-toolbar">
      <div class="toolbar-status"><span v-if="view">{{ t('policyMode') }}: {{ view.mode }} · {{ t('revision') }}: {{ view.revision }}</span><span v-if="projectId">{{ projectId }}</span></div>
      <KsButton :disabled="busy" @click="load">{{ t('reload') }}</KsButton>
      <KsButton v-if="showList && projectId && tab === 'scopes' && localAllowed && !needsReconciliation" type="primary" :disabled="localDisabled" data-test="new-local-binding" @click="navigate(base + '/bindings/new')">{{ t('newLocalBinding') }}</KsButton>
      <KsButton v-if="showList && (isUsers ? caps?.userDirectory && identityAllowed : projectId ? tab === 'scopes' || identityAllowed : identityAllowed)" type="primary" :disabled="identityDisabled" @click="create">{{ t(projectId && tab === 'scopes' ? 'createSharedScope' : 'create') }}</KsButton>
    </header>
    <p v-if="busy" role="status">{{ t('loading') }}</p>
    <p v-if="error" class="message error" role="alert" tabindex="-1">{{ error }} <code v-if="errorCode">{{ errorCode }}</code></p>
    <section v-if="reauthenticationRequired" class="message" role="alert"><p>{{ t('reauthenticationRequired') }}</p><a :href="reauthenticationHref" data-test="reauthenticate">{{ t('signInAgain') }}</a></section>
    <p v-if="conflicted" class="message">{{ t('conflict') }} <KsButton :disabled="busy" @click="load">{{ t('reload') }}</KsButton></p>
    <p v-if="notice" class="message" role="status">{{ notice }}</p>
    <section v-if="savedResult" class="message" data-test="saved-result"><h2>{{ t('authorizationState') }}</h2><pre>{{ JSON.stringify(savedResult, null, 2) }}</pre><p>{{ t('stateDisclaimer') }}</p></section>
    <template v-if="caps?.allowed && ready">
      <p class="message muted">{{ t('independentSaves') }}</p>
      <p v-if="needsReconciliation" class="message">{{ t('localRecoveryOnly') }}</p>
      <form v-if="needsReconciliation && !projectId" @submit.prevent="navigate('projects/' + encodeURIComponent(recoveryTarget) + '/reconciliation')"><label>{{ t('projectId') }}<KsInput v-model="recoveryTarget" required /></label><KsButton native-type="submit">{{ t('atomicReconciliation') }}</KsButton></form>
      <p v-if="needsReconciliation" class="message error" role="alert" data-test="reconciliation-required">{{ t('reconciliationRequired') }}</p>
      <template v-if="projectId">
        <KsButton :disabled="busy" @click="navigate('projects')">{{ t('back') }}</KsButton>
        <KsTabs type="box" :model-value="bindingNew ? 'scopes' : tab" :aria-label="t('projects')" @update:model-value="navigate(base + '/' + ($event === 'overview' ? '' : $event))">
          <KsTabPane v-for="name in ['overview', 'scopes', 'clients', 'assignments', 'reconciliation']" :key="name" :name="name" :label="t(name)" />
        </KsTabs>
      </template>
      <section v-if="bindingNew && bindingOptions" aria-labelledby="new-local-binding-title">
        <h2 id="new-local-binding-title">{{ t('newLocalBinding') }}</h2>
        <p class="message">{{ t('newLocalBindingHelp') }}</p>
        <p>{{ t('directoryRevision') }}: {{ bindingOptions.directoryRevision }}</p>
        <details><summary>{{ t('sharedIntent') }}</summary><pre data-test="binding-options-upper">{{ JSON.stringify(bindingOptions.upperBound, null, 2) }}</pre></details>
        <form @submit.prevent="saveNewLocalBinding"><fieldset :disabled="localDisabled || needsReconciliation">
          <label>{{ t('scope') }}<select v-model="bindingName" required name="binding-scope" @change="selectLocalBinding"><option disabled value="">{{ t('selectSharedScope') }}</option><option v-for="scope in bindingOptions.upperBound.scopes.filter(item => item.active)" :key="scope.scope" :value="scope.scope">{{ scope.scope }}</option></select></label>
          <template v-if="localScope">
            <label>{{ t('description') }}<KsInput v-model="localScope.description" name="local-scope-description" /></label>
            <label class="checkbox"><input v-model="localScope.enabled" type="checkbox">{{ t('enabled') }}</label>
            <p class="message">{{ t('completeScopeRule') }}</p>
            <GrantEditor :grants="localScope.grants" :management-grants="localScope.managementGrants" />
            <KsButton type="primary" native-type="submit" data-test="save-new-local-binding">{{ t('saveLocal') }}</KsButton>
          </template>
        </fieldset></form>
      </section>
      <ProjectRecoveryEditor v-else-if="recovery" :project="recovery.candidate" :upper="recovery.upperBound" :revision="recovery.policyRevision" :target-revision="recovery.directoryRevision" :disabled="recoveryDisabled" @save="saveReconciliation" />
      <ProjectList v-else-if="showList" :key="path" :items="items" :columns="columns" :storage-key="storageKey" :busy="busy" @open="openRow" @refresh="load" />
      <template v-else-if="projectNew || projectId && tab === 'overview'">
        <section v-if="project" aria-labelledby="identity-project-title">
          <h2 id="identity-project-title">{{ t('identityDirectory') }}</h2>
          <form @submit.prevent="saveIdentityProject"><fieldset :disabled="identityDisabled">
            <label>{{ t('projectId') }}<KsInput v-if="projectNew" v-model="project.id" required name="project-id" /><output v-else>{{ project.id }}</output></label>
            <label>{{ t('displayName') }}<KsInput v-model="project.displayName" required name="project-display-name" /></label>
            <template v-if="!projectNew"><p>{{ t('issuer') }}: {{ project.issuer }}</p><p>{{ t('objectRevision') }}: {{ project.revision }} · {{ t('authorizationVersion') }}: {{ project.authorizationVersion }}</p><label class="checkbox"><input v-model="project.active" type="checkbox">{{ t('active') }}</label></template>
            <KsButton type="primary" native-type="submit" data-test="save-identity-project">{{ t('saveIdentity') }}</KsButton>
          </fieldset></form>
        </section>
        <section v-if="!projectNew && localProject" aria-labelledby="local-project-title">
          <h2 id="local-project-title">{{ t('localPolicy') }}</h2>
          <form @submit.prevent="saveLocalProject"><fieldset :disabled="localDisabled">
            <label>{{ t('issuer') }}<KsInput v-model="localProject.issuer" required name="local-issuer" /></label>
            <label>{{ t('audiences') }}<textarea :value="text(localProject.audiences)" required @input="localProject.audiences = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
            <p>{{ t('directoryRevision') }}: {{ localProject.directoryRevision }}</p>
            <p v-if="project">{{ t('objectRevision') }}: {{ project.revision }}</p>
            <label class="checkbox"><input v-model="localProject.enabled" type="checkbox">{{ t('enabled') }}</label>
            <KsButton type="primary" native-type="submit" data-test="save-local-project">{{ t('saveLocal') }}</KsButton>
          </fieldset></form>
          <section v-if="needsReconciliation" class="message"><p>{{ t('atomicReconciliationHelp') }}</p><KsButton :disabled="busy" @click="navigate(base + '/reconciliation')">{{ t('atomicReconciliation') }}</KsButton></section>
        </section>
      </template>
      <template v-else-if="projectId && tab === 'scopes' && detail">
        <KsButton :disabled="busy" @click="navigate(base + '/scopes')">{{ t('back') }}</KsButton>
        <section v-if="identityScope" aria-labelledby="identity-scope-title">
          <h2 id="identity-scope-title">{{ t('sharedIntent') }}</h2>
          <form @submit.prevent="saveIdentityScope"><fieldset :disabled="identityDisabled">
            <label>{{ t('scope') }}<KsInput v-if="isNew" v-model="identityScope.scope" required name="identity-scope" /><output v-else>{{ identityScope.scope }}</output></label>
            <label>{{ t('description') }}<KsInput v-model="identityScope.description" name="identity-scope-description" /></label>
            <label class="checkbox"><input v-model="identityScope.active" type="checkbox">{{ t('active') }}</label>
            <p>{{ t('objectRevision') }}: {{ isNew ? project?.revision : identityScope.revision }}</p>
            <IntentEditor :value="identityScope" />
            <div class="buttons"><KsButton type="primary" native-type="submit" data-test="save-identity-scope">{{ t('saveIdentity') }}</KsButton></div>
          </fieldset></form>
        </section>
        <p v-else class="message">{{ t('identityUnavailable') }}</p>
        <section v-if="!isNew && localScope" aria-labelledby="local-scope-title">
          <h2 id="local-scope-title">{{ t('completeGrants') }}</h2>
          <p>{{ t('scope') }}: {{ localScope.name }}</p>
          <p class="message">{{ t('completeScopeRule') }}</p>
          <p v-if="!localPolicyProject()" class="message">{{ t('configureLocalFirst') }}</p>
          <form @submit.prevent="saveLocalScope"><fieldset :disabled="localDisabled || needsReconciliation || !localPolicyProject()">
            <label>{{ t('description') }}<KsInput v-model="localScope.description" name="local-scope-description" /></label>
            <label class="checkbox"><input v-model="localScope.enabled" type="checkbox">{{ t('enabled') }}</label>
            <GrantEditor :grants="localScope.grants" :management-grants="localScope.managementGrants" />
            <div class="buttons"><KsButton type="primary" native-type="submit" data-test="save-local-scope" :disabled="needsReconciliation">{{ t('saveLocal') }}</KsButton><KsButton v-if="localScopeExists" native-type="button" @click="confirmation = 'local'">{{ t('deleteLocal') }}</KsButton></div>
          </fieldset></form>
        </section>
        <section class="message" aria-labelledby="result-title"><h2 id="result-title">{{ t('authorizationState') }}</h2><p>{{ t('identityDirectory') }}: {{ savedIdentityScope ? t(savedIdentityScope.active ? 'active' : 'inactive') : t('unavailable') }} · {{ t('localPolicy') }}: {{ localPolicyProject()?.enabled ? displayLocalStatus(localPolicyProject()?.scopes?.[selectedScope]) : t('inactive') }}</p><p>{{ t('objectRevision') }}: {{ savedIdentityScope?.revision }} · {{ t('authorizationVersion') }}: {{ project?.authorizationVersion }}</p><p>{{ t('stateDisclaimer') }}</p></section>
      </template>
      <template v-else-if="member">
        <KsButton :disabled="busy" @click="navigate(base + '/clients')">{{ t('back') }}</KsButton>
        <form @submit.prevent="saveMember"><fieldset :disabled="identityDisabled">
          <label>{{ t('clientId') }}<KsInput v-if="isNew" v-model="member.clientId" required name="member-client-id" /><output v-else>{{ member.clientId }}</output></label>
          <p>{{ t('objectRevision') }}: {{ isNew ? project?.revision : member.revision }}</p>
          <label class="checkbox"><input v-model="member.approved" type="checkbox" data-test="member-approved">{{ t('approved') }}</label>
          <label class="checkbox"><input v-model="member.firstPartyUi" type="checkbox" data-test="member-first-party-ui">{{ t('firstPartyUi') }}</label>
          <p class="message">{{ t('firstPartyUiReceipt') }}</p>
          <label>{{ t('audiences') }}<textarea :value="text(member.audiences)" required @input="member.audiences = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
          <fieldset><legend>{{ t('allowedProjectScopes') }}</legend><label v-for="name in [...new Set([...scopeChoices.map(item => item.scope), ...member.allowedProjectScopes])]" :key="name" class="checkbox"><input v-model="member.allowedProjectScopes" type="checkbox" :value="name">{{ name }}</label><p v-if="!scopeChoices.length">{{ t('noRows') }}</p></fieldset>
          <div class="buttons"><KsButton type="primary" native-type="submit" data-test="save-member">{{ t('saveIdentity') }}</KsButton><KsButton v-if="!isNew" native-type="button" @click="confirmation = 'client'">{{ t('removeClient') }}</KsButton></div>
        </fieldset></form>
        <section v-if="savedMember" class="message"><h2>{{ t('authorizationState') }}</h2><p>{{ t('approved') }}: {{ t(savedMember.approved ? 'active' : 'inactive') }}</p><p>{{ t('firstPartyUi') }}: {{ t(savedMember.firstPartyUi ? 'active' : 'inactive') }}</p><p>{{ t('allowedProjectScopes') }}: {{ savedMember.allowedProjectScopes.join(', ') }}</p><p>{{ t('audiences') }}: {{ savedMember.audiences.join(', ') }}</p><p>{{ t('stateDisclaimer') }}</p></section>
      </template>
      <template v-else-if="assignment">
        <KsButton :disabled="busy" @click="navigate(base + '/assignments')">{{ t('back') }}</KsButton>
        <form @submit.prevent="saveAssignment"><fieldset :disabled="identityDisabled">
          <label>{{ t('subject') }}<KsInput v-if="isNew" v-model="assignment.subject" required name="assignment-subject" /><output v-else>{{ assignment.subject }}</output></label>
          <p>{{ t('issuer') }}: {{ assignment.issuer }}</p><p>{{ t('objectRevision') }}: {{ isNew ? project?.revision : assignment.revision }}</p>
          <fieldset><legend>{{ t('projectScopes') }}</legend><label v-for="name in [...new Set([...scopeChoices.map(item => item.scope), ...assignment.projectScopes])]" :key="name" class="checkbox"><input v-model="assignment.projectScopes" type="checkbox" :value="name">{{ name }}</label><p v-if="!scopeChoices.length">{{ t('noRows') }}</p></fieldset>
          <div class="buttons"><KsButton type="primary" native-type="submit" data-test="save-assignment">{{ t('saveIdentity') }}</KsButton><KsButton v-if="!isNew" native-type="button" @click="confirmation = 'assignment'">{{ t('revokeAssignment') }}</KsButton></div>
        </fieldset></form>
        <section v-if="savedAssignment" class="message"><h2>{{ t('authorizationState') }}</h2><p>{{ t('subject') }}: {{ savedAssignment.subject }}</p><p>{{ t('projectScopes') }}: {{ savedAssignment.projectScopes.join(', ') }}</p><p>{{ t('objectRevision') }}: {{ savedAssignment.revision }} · {{ t('authorizationVersion') }}: {{ project?.authorizationVersion }}</p><p>{{ t('stateDisclaimer') }}</p></section>
      </template>
      <template v-else-if="isUsers && user">
        <KsButton :disabled="busy" @click="navigate('users')">{{ t('back') }}</KsButton>
        <form @submit.prevent="saveUser"><fieldset :disabled="usersDisabled">
          <label>{{ t('subject') }}<KsInput v-if="isNew" v-model="user.username" required autocomplete="off" /><output v-else>{{ user.subject }}</output></label>
          <label>{{ t('name') }}<KsInput v-model="user.name" required /></label><label>{{ t('email') }}<KsInput v-model="user.email" type="email" /></label>
          <label v-if="isNew">{{ t('password') }}<KsInput v-model="user.password" type="password" required autocomplete="new-password" /></label>
          <KsButton type="primary" native-type="submit" data-test="save-user">{{ t('save') }}</KsButton>
        </fieldset></form>
        <template v-if="!isNew"><p>{{ t(user.active ? 'active' : 'inactive') }} · {{ t('objectRevision') }}: {{ user.revision }}</p>
          <KsButton :disabled="usersDisabled" @click="perform(async () => {user = await api.updateState(user.subject, !user.active, user.revision)})">{{ t(user.active ? 'inactive' : 'active') }}</KsButton>
          <form @submit.prevent="perform(async () => {user = await api.resetPassword(user.subject, password, user.revision); password = ''})"><fieldset :disabled="usersDisabled"><label>{{ t('password') }}<KsInput v-model="password" type="password" required autocomplete="new-password" /></label><KsButton native-type="submit">{{ t('reset') }}</KsButton></fieldset></form>
          <p>{{ t('accountNoGrant') }}</p><KsButton :disabled="identityDisabled" @click="navigate('projects')">{{ t('manageAssignments') }}</KsButton>
        </template>
      </template>
      <section v-if="confirmation" class="message" role="alertdialog" :aria-label="t('confirm')"><p>{{ t('confirm') }}</p><div class="buttons"><KsButton :disabled="busy || conflicted || (confirmation === 'local' ? !localAllowed : !identityAllowed)" @click="remove">{{ t('delete') }}</KsButton><KsButton :disabled="busy" @click="confirmation = ''">{{ t('cancel') }}</KsButton></div></section>
    </template>
  </main>
</template>
