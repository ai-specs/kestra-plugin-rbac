<script setup>
import {computed, ref, watch, onBeforeUnmount, nextTick} from 'vue'
import {useI18n} from 'vue-i18n'
import {useRouter} from 'vue-router'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import KsTabs from '@kestra-io/design-system/components/Navigation/KsTabs/KsTabs'
import KsTabPane from '@kestra-io/design-system/components/Navigation/KsTabs/KsTabPane'
import ApplicationGroupList from './ApplicationGroupList.vue'
import ApplicationGroupCards from './ApplicationGroupCards.vue'
import ApplicationDeclarationsTable from './ApplicationDeclarationsTable.vue'
import GrantEditor from './GrantEditor.vue'
import {createClient, encodeKey, decodeKey} from './client.js'
import {canonicalPath, collectPages, splitLines, copy, groupScopeInput, scopeInput, memberInput, managementAccess, requiresReauthentication, loginEntry, isRuntimeApplication, applicationPermissionModel, runtimePolicyView, runtimeScopeDefinition} from './application-group-model.js'

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
const projectNew = computed(() => path.value === 'application-groups/new')
const caps = ref(null)
const view = ref(null)
const project = ref(null)
const localProject = ref(null)
const identityEditing = ref(false)
const policyEditing = ref(false)
const identityScope = ref(null)
const localScope = ref(null)
const localScopeExists = ref(false)
const permissionScope = ref('')
const permissionEditing = ref(false)
const member = ref(null)
const declarationApplications = ref([])
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
const conflicted = ref(false)
const confirmation = ref('')
const main = ref(null)
let generation = 0
let disposed = false
let pendingLoad = false
const access = computed(() => managementAccess(caps.value, reauthenticationRequired.value))
const identityAllowed = computed(() => access.value.identity)
const localAllowed = computed(() => access.value.local && view.value !== null)
const showRuntimePolicy = computed(() => !isNew.value && member.value && caps.value?.localPolicyRead === true && isRuntimeApplication(caps.value,projectId.value,member.value.clientId))
const identityDisabled = computed(() => busy.value || conflicted.value || !identityAllowed.value)
const localDisabled = computed(() => busy.value || conflicted.value || !localAllowed.value)
const usersDisabled = computed(() => identityDisabled.value || !caps.value?.userDirectory)
const storageKey = computed(() => ['rbac-management-filters', props.context.tenant, caps.value?.subject, path.value].join(':'))
const base = computed(() => 'application-groups/' + encodeURIComponent(projectId.value))
const columns = computed(() => isUsers.value ? ['subject', 'name', 'email', 'active'] : !projectId.value ? ['id', 'displayName', 'issuer', 'active'] : tab.value === 'scopes' ? ['key','roleName','description'] : tab.value === 'applications' ? ['clientId', 'approved', 'audiences', 'supportedScopeKeys'] : ['subject', 'issuer', 'scopeKeys'])
const showList = computed(() => !projectNew.value && (isUsers.value ? !detail.value : !projectId.value || tab.value !== 'overview' && tab.value !== 'declarations' && !detail.value))
const selectedScope = computed(() => detail.value && !isNew.value && tab.value === 'scopes' ? decodeURIComponent(detail.value) : '')
const localPolicyProject = () => view.value?.policy
const runtimeScopes = computed(() => {
  const scopes = new Map(scopeChoices.value.map(scope => [scope.key,scope]))
  for (const key of Object.keys(localPolicyProject()?.scopes ?? {})) {
    if (!scopes.has(key)) scopes.set(key,{key,name:key,description:''})
  }
  return [...scopes.values()]
})
const policyProjectInput = () => ({issuer: localProject.value.issuer, audiences: splitLines(localProject.value.audiences), enabled: localProject.value.enabled})
const text = value => Array.isArray(value) ? value.join('\n') : value
const navigate = value => props.context.navigate(value)
const projectPath = id => 'application-groups/' + encodeURIComponent(id)
function failureMessage(failure) {
  if (requiresReauthentication(failure)) return t('reauthenticationRequired')
  if (failure.status === 412) return t('conflict')
  if (failure.code === 'protected_subject') return t('protected')
  if (failure.status === 401 || failure.status === 403) return t('denied')
  if (/metadata/i.test(failure.code ?? '')) return t('metadataRange')
  if (/directory|stale/.test(failure.code ?? '')) return t('error')
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
  identityEditing.value = false
  policyEditing.value = false
  view.value = null
  permissionScope.value = ''
  permissionEditing.value = false
  savedIdentityScope.value = savedMember.value = savedAssignment.value = null
  confirmation.value = ''
  error.value = ''
  errorCode.value = ''
  notice.value = ''
  // Clear previous path data before any awaited call: failed navigation must not expose an old editor.
  project.value = localProject.value = identityScope.value = localScope.value = member.value = assignment.value = user.value = null
  items.value = []
  declarationApplications.value = []
  scopeChoices.value = []
  password.value = ''
  busy.value = true
  try {
    if (!['application-groups','users'].includes(parts.value[0])) throw {status:404}
    const raw = props.context.path.replace(/^\/+|\/+$/g, '')
    if (raw !== path.value) {await props.context.navigate(path.value, {replace: true}); return}
    await router.replace({path: '/' + path.value, query: {}})
    const nextCaps = await api.capabilities(projectId.value || undefined)
    if (current !== generation || disposed) return
    if (reauthenticationRequired.value) throw {status:401}
    caps.value = nextCaps
    if (!nextCaps.allowed) throw {status: 403}
    const all = (fetcher, revision) => collectPages(fetcher, props.context.signal, revision)
    if (isUsers.value) {
      if (!nextCaps.userDirectory || !nextCaps.identityRead) throw {status: 403}
      if (!detail.value) items.value = await all((page, size) => api.users(page, size))
      else user.value = isNew.value ? {username: '', name: '', email: '', password: ''} : await api.user(decodeKey(detail.value.startsWith('~') ? detail.value : '~' + detail.value))
    } else if (projectNew.value) {
      project.value = {id: '', tenant: 'main', displayName: ''}
    } else if (!projectId.value) {
      const directory = nextCaps.identityRead ? await all((page, size) => api.applicationGroups(page, size)) : []
      items.value = directory
    } else {
      project.value = nextCaps.identityRead ? await optional(() => api.applicationGroup(projectId.value)) : null
      if (!nextCaps.identityRead) throw {status:403}
      if (!project.value) throw {status:404}
      if (tab.value === 'scopes') {
        const directory = nextCaps.identityRead && project.value ? await all((page, size) => api.applicationGroupScopes(projectId.value, page, size), project.value.revision) : []
        scopeChoices.value = directory
        if (!detail.value) items.value = directory.map(scope => ({...scope,roleName:scope.name}))
        else {
          const name = isNew.value ? '' : decodeURIComponent(detail.value)
          identityScope.value = isNew.value ? {applicationGroupId:projectId.value,key:'', name: '', description: ''} : nextCaps.identityRead ? await optional(() => api.applicationGroupScope(projectId.value, name)) : null
          if (!isNew.value && !identityScope.value) throw {status: 404}
        }
      } else if (tab.value === 'applications' || tab.value === 'scope-grants') {
        if (!nextCaps.identityRead || !project.value) throw {status: 403}
        scopeChoices.value = await all((page, size) => api.applicationGroupScopes(projectId.value, page, size), project.value.revision)
        if (!detail.value) items.value = await all((page, size) => (tab.value === 'applications' ? api.applications : api.scopeGrants)(projectId.value,page,size), project.value.revision)
        else if (tab.value === 'applications') {
          member.value = isNew.value ? {clientId: '', approved: false, audiences: [], supportedScopeKeys: []} : await api.application(projectId.value, decodeKey(detail.value))
          if (!isNew.value && nextCaps.localPolicyRead && isRuntimeApplication(nextCaps,projectId.value,member.value.clientId)) {
            const result = await api.runtimePolicy(projectId.value,member.value.clientId)
            if (current !== generation || disposed) return
            applyRuntimePolicy(runtimePolicyView(result,projectId.value,member.value.clientId))
          }
        }
        else assignment.value = isNew.value ? {issuer: project.value.issuer, subject: '', scopeKeys: []} : await api.scopeGrant(projectId.value, decodeKey(detail.value))
      } else if (tab.value === 'declarations') {
        if (!nextCaps.identityRead || !project.value) throw {status: 403}
        scopeChoices.value = await all((page, size) => api.applicationGroupScopes(projectId.value, page, size), project.value.revision)
        const applications = await all((page, size) => api.applications(projectId.value,page,size), project.value.revision)
        const withDeclarations = nextCaps.applicationScopeDeclarations ? await Promise.all(applications.map(async application => {
          const declarations = await optional(() => api.applicationScopeDeclarations(projectId.value, application.clientId))
          if (current !== generation || disposed) return null
          return {...application, declarations: declarations ?? []}
        })) : applications.map(application => ({...application, declarations: []}))
        if (current !== generation || disposed) return
        declarationApplications.value = withDeclarations.filter(Boolean)
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
  const key = tab.value === 'scopes' ? row.key : tab.value === 'applications' ? row.clientId : row.subject
  return navigate(base.value + '/' + tab.value + '/' + (tab.value === 'scopes' ? encodeURIComponent(key) : encodeKey(key)))
}
function create() {
  return navigate(isUsers.value ? 'users/new' : projectId.value ? base.value + '/' + tab.value + '/new' : 'application-groups/new')
}
function selectApplicationPermission() {
  const definition = runtimeScopes.value.find(scope => scope.key === permissionScope.value)
  const saved = runtimeScopeDefinition(localPolicyProject(),permissionScope.value)
  localScope.value = definition ? applicationPermissionModel(definition,saved) : null
  localScopeExists.value = Boolean(saved)
  permissionEditing.value = false
}
async function saveIdentityProject() {
  await perform(async () => {
    if (projectNew.value) {
      const result = await api.createApplicationGroup({id: project.value.id, tenant: 'main', displayName: project.value.displayName})
      project.value = result
    } else project.value = await api.updateApplicationGroup(projectId.value, {displayName: project.value.displayName, active: project.value.active}, project.value.revision)
    identityEditing.value = false
  }, 'saved', true)
}
function applyRuntimePolicy(result) {
  view.value = result
  localProject.value = result.policy ? copy(result.policy) : {issuer:project.value.issuer,audiences:copy(member.value.audiences),enabled:false}
}
async function refreshRuntimePolicy(receipt,previous,groupId,clientId,current) {
  if (!receipt || Object.keys(receipt).length !== 1 || !Number.isSafeInteger(receipt.revision) || receipt.revision <= previous) throw new Error('invalid_runtime_receipt')
  if (disposed || generation !== current) return false
  const capabilities = await api.capabilities(groupId)
  if (disposed || generation !== current) return false
  caps.value = capabilities
  policyEditing.value = false
  permissionEditing.value = false
  if (!showRuntimePolicy.value) {
    view.value = localProject.value = localScope.value = null
    return false
  }
  const result = runtimePolicyView(await api.runtimePolicy(groupId,clientId),groupId,clientId)
  if (disposed || generation !== current) return false
  if (result.revision < receipt.revision) throw new Error('invalid_runtime_receipt')
  applyRuntimePolicy(result)
  return true
}
async function saveLocalProject() {
  if (!showRuntimePolicy.value || !localAllowed.value) return
  await perform(async () => {
    const value = policyProjectInput()
    const groupId = projectId.value, clientId = member.value.clientId, current = generation, previous = view.value.revision
    const receipt = localPolicyProject() ? await api.updateRuntimePolicy(groupId,clientId,value,previous) : await api.createRuntimePolicy(groupId,clientId,value,previous)
    await refreshRuntimePolicy(receipt,previous,groupId,clientId,current)
  }, 'saved')
}
async function saveIdentityScope() {
  await perform(async () => {
    const input = groupScopeInput(identityScope.value)
    identityScope.value = isNew.value ? await api.createApplicationGroupScope(projectId.value, input, project.value.revision) : await api.updateApplicationGroupScope(projectId.value, selectedScope.value, input, identityScope.value.revision)
    savedIdentityScope.value = copy(identityScope.value)
    // Directory mutation invalidates identity; navigation resumes after production login.
  }, 'saved', true)
}
async function saveLocalScope() {
  if (!showRuntimePolicy.value || !localAllowed.value || !permissionEditing.value) return
  await perform(async () => {
    const input = scopeInput(localScope.value)
    const key = input.scopeKey
    if (!input.grants.length && !input.managementGrants.length) throw {status:400}
    const groupId = projectId.value, clientId = member.value.clientId, current = generation, previous = view.value.revision
    const receipt = localScopeExists.value ? await api.updateRuntimeScope(groupId,clientId,key,input,previous) : await api.createRuntimeScope(groupId,clientId,input,previous)
    if (await refreshRuntimePolicy(receipt,previous,groupId,clientId,current)) selectApplicationPermission()
  }, 'saved')
}
async function saveMember() {
  await perform(async () => {
    const input = memberInput(member.value)
    member.value = isNew.value ? await api.createApplication(projectId.value, input, project.value.revision) : await api.updateApplication(projectId.value, decodeKey(detail.value), input, member.value.revision)
    savedMember.value = copy(member.value)
    // Do not continue with the pre-mutation authenticated identity.
  }, 'saved', true)
}
async function reloadDeclarations() {
  const applications = await collectPages((page, size) => api.applications(projectId.value,page,size), props.context.signal, project.value.revision)
  declarationApplications.value = await Promise.all(applications.map(async application => {
    const declarations = caps.value?.applicationScopeDeclarations ? await optional(() => api.applicationScopeDeclarations(projectId.value, application.clientId)) : null
    return {...application, declarations: declarations ?? []}
  }))
}
async function createDeclaration(clientId, scope, permissions) {
  await perform(async () => {
    await api.createApplicationScopeDeclaration(projectId.value, clientId, scope, permissions)
    await reloadDeclarations()
  })
}
async function updateDeclaration(clientId, scope, permissions, revision) {
  await perform(async () => {
    await api.updateApplicationScopeDeclaration(projectId.value, clientId, scope, permissions, revision)
    await reloadDeclarations()
  })
}
async function deleteDeclaration(clientId, scope, revision) {
  await perform(async () => {
    await api.deleteApplicationScopeDeclaration(projectId.value, clientId, scope, revision)
    reauthenticationRequired.value = true
    caps.value = null
  })
}
async function saveAssignment() {
  await perform(async () => {
    const input = {issuer: assignment.value.issuer, subject: assignment.value.subject, scopeKeys: [...assignment.value.scopeKeys]}
    assignment.value = isNew.value ? await api.createScopeGrant(projectId.value, input, project.value.revision) : await api.updateScopeGrant(projectId.value, input, assignment.value.revision)
    savedAssignment.value = copy(assignment.value)
    // A new sign-in is required before any subsequent protected request.
  }, 'saved', true)
}
async function remove() {
  const identityMutation = confirmation.value !== 'local'
  await perform(async () => {
    if (confirmation.value === 'local') {
      if (!showRuntimePolicy.value || !localAllowed.value) throw {status:403}
      const groupId = projectId.value, clientId = member.value.clientId, current = generation, previous = view.value.revision
      const receipt = await api.deleteRuntimeScope(groupId,clientId,localScope.value.scopeKey,previous)
      if (await refreshRuntimePolicy(receipt,previous,groupId,clientId,current)) {
        localScope.value = null;localScopeExists.value = false;permissionScope.value = '';permissionEditing.value = false
      }
    }
    else if (confirmation.value === 'group') {
      if (project.value.builtIn) throw {status:403}
      await api.deleteApplicationGroup(projectId.value, project.value.revision)
      navigate('application-groups')
    }
    else if (confirmation.value === 'sharedScope') await api.deleteApplicationGroupScope(projectId.value, selectedScope.value, identityScope.value.revision)
    else if (confirmation.value === 'client') await api.deleteApplication(projectId.value, decodeKey(detail.value), member.value.revision)
    else await api.deleteScopeGrant(projectId.value, assignment.value.subject, assignment.value.revision)
  }, 'saved', identityMutation)
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
    <KsTabs type="box" :model-value="isUsers ? 'users' : 'application-groups'" :aria-label="t('title')" @update:model-value="navigate">
      <KsTabPane name="application-groups" :label="t('applicationGroups')" /><KsTabPane name="users" :label="t('users')" />
    </KsTabs>
    <header class="management-toolbar">
      <div class="toolbar-status"><span v-if="projectId">{{ projectId }}</span></div>
      <KsButton :disabled="busy" @click="load">{{ t('reload') }}</KsButton>
      <KsButton v-if="showList && (isUsers ? caps?.userDirectory && identityAllowed : projectId ? tab === 'scopes' || identityAllowed : identityAllowed)" type="primary" :disabled="identityDisabled" @click="create">{{ t('create') }}</KsButton>
    </header>
    <p v-if="busy" role="status">{{ t('loading') }}</p>
    <p v-if="error" class="message error" role="alert" tabindex="-1">{{ error }} <code v-if="errorCode">{{ errorCode }}</code></p>
    <section v-if="reauthenticationRequired" class="message" role="alert"><p>{{ t('reauthenticationRequired') }}</p><a :href="reauthenticationHref" data-test="reauthenticate">{{ t('signInAgain') }}</a></section>
    <p v-if="conflicted" class="message">{{ t('conflict') }} <KsButton :disabled="busy" @click="load">{{ t('reload') }}</KsButton></p>
    <p v-if="notice" class="message" role="status">{{ notice }}</p>
    <template v-if="caps?.allowed && ready">
      <template v-if="projectId">
        <KsButton :disabled="busy" @click="navigate('application-groups')">{{ t('back') }}</KsButton>
        <KsTabs type="box" :model-value="tab" :aria-label="t('applicationGroups')" @update:model-value="navigate(base + '/' + ($event === 'overview' ? '' : $event))">
          <KsTabPane v-for="name in ['overview', 'scopes', 'applications', 'declarations', 'scope-grants']" :key="name" :name="name" :label="t(name === 'applications' ? 'clients' : name === 'scope-grants' ? 'assignments' : name === 'declarations' ? 'applicationPermissions' : name)" />
        </KsTabs>
      </template>
      <ApplicationGroupCards v-if="showList && !isUsers && !projectId" :items="items" :busy="busy" :storage-key="storageKey" @open="openRow" @refresh="load" />
      <ApplicationGroupList v-else-if="showList" :key="path" :items="items" :columns="columns" :storage-key="storageKey" :busy="busy" @open="openRow" @refresh="load" />
      <ApplicationDeclarationsTable v-else-if="projectId && tab === 'declarations'" :applications="declarationApplications" :scopes="scopeChoices" :busy="busy" :disabled="identityDisabled" @create="createDeclaration" @update="updateDeclaration" @delete="deleteDeclaration" />
      <template v-else-if="projectNew || projectId && tab === 'overview'">
        <section v-if="project" aria-labelledby="identity-project-title">
          <h2 id="identity-project-title">{{ project.displayName || project.id }}</h2>
          <div v-if="!projectNew && !identityEditing" data-test="application-group-detail">
            <p>{{ project.id }}</p>
            <p v-if="project.builtIn" class="builtin-indicator" data-test="builtin-group-indicator">{{ t('builtinGroup') }}</p>
            <p>{{ t('active') }}: {{ t(project.active ? 'active' : 'inactive') }}</p>
            <KsButton v-if="identityAllowed" :disabled="identityDisabled" data-test="edit-application-group" @click="identityEditing = true">{{ t('edit') }}</KsButton>
          </div>
          <form v-else @submit.prevent="saveIdentityProject"><fieldset :disabled="identityDisabled">
            <label>{{ t('projectId') }}<KsInput v-if="projectNew" v-model="project.id" required name="project-id" /><output v-else>{{ project.id }}</output></label>
            <label>{{ t('displayName') }}<KsInput v-model="project.displayName" required name="project-display-name" /></label>
            <template v-if="!projectNew"><p>{{ t('issuer') }}: {{ project.issuer }}</p><p>{{ t('objectRevision') }}: {{ project.revision }} · {{ t('authorizationVersion') }}: {{ project.authorizationVersion }}</p><label class="checkbox"><input v-model="project.active" type="checkbox">{{ t('active') }}</label></template>
            <KsButton type="primary" native-type="submit" data-test="save-identity-project">{{ t('save') }}</KsButton>
            <KsButton v-if="!projectNew && !project.builtIn" native-type="button" data-test="delete-application-group" @click="confirmation = 'group'">{{ t('delete') }}</KsButton>
            <KsButton v-if="!projectNew" native-type="button" :disabled="busy" @click="load">{{ t('cancel') }}</KsButton>
          </fieldset></form>
        </section>

      </template>
      <template v-else-if="projectId && tab === 'scopes' && detail">
        <KsButton :disabled="busy" @click="navigate(base + '/scopes')">{{ t('back') }}</KsButton>
        <section v-if="identityScope" aria-labelledby="identity-scope-title">
          <h2 id="identity-scope-title">{{ t('scopes') }}</h2>
          <form @submit.prevent="saveIdentityScope"><fieldset :disabled="identityDisabled">
            <label>{{ t('scope') }}<KsInput v-if="isNew" v-model="identityScope.key" required name="identity-scope" /><output v-else>{{ identityScope.key }}</output></label>
            <label>{{ t('roleName') }}<KsInput v-model="identityScope.name" required name="identity-scope-name" /></label>
            <label>{{ t('description') }}<KsInput v-model="identityScope.description" name="identity-scope-description" /></label>
            <p>{{ t('objectRevision') }}: {{ isNew ? project?.revision : identityScope.revision }}</p>
            <div class="buttons"><KsButton type="primary" native-type="submit" data-test="save-identity-scope">{{ t('save') }}</KsButton><KsButton v-if="!isNew" native-type="button" @click="confirmation = 'sharedScope'">{{ t('delete') }}</KsButton></div>
          </fieldset></form>
        </section>
        <p v-else class="message">{{ t('unavailable') }}</p>

      </template>
      <template v-else-if="member">
        <KsButton :disabled="busy" @click="navigate(base + '/applications')">{{ t('back') }}</KsButton>
        <form @submit.prevent="saveMember"><fieldset :disabled="identityDisabled">
          <label>{{ t('clientId') }}<KsInput v-if="isNew" v-model="member.clientId" required name="member-client-id" /><output v-else>{{ member.clientId }}</output></label>
          <p>{{ t('objectRevision') }}: {{ isNew ? project?.revision : member.revision }}</p>
          <label class="checkbox"><input v-model="member.approved" type="checkbox" data-test="member-approved">{{ t('approved') }}</label>
          <label>{{ t('audiences') }}<textarea :value="text(member.audiences)" required @input="member.audiences = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
          <fieldset><legend>{{ t('supportedScopeKeys') }}</legend><label v-for="name in [...new Set([...scopeChoices.map(item => item.key), ...member.supportedScopeKeys])]" :key="name" class="checkbox"><input v-model="member.supportedScopeKeys" type="checkbox" :value="name">{{ name }}</label><p v-if="!scopeChoices.length">{{ t('noRows') }}</p></fieldset>
          <div class="buttons"><KsButton type="primary" native-type="submit" data-test="save-member">{{ t('save') }}</KsButton><KsButton v-if="!isNew" native-type="button" @click="confirmation = 'client'">{{ t('removeClient') }}</KsButton></div>
        </fieldset></form>
        <section v-if="savedMember" class="message"><h2>{{ t('saved') }}</h2><p>{{ t('approved') }}: {{ t(savedMember.approved ? 'active' : 'inactive') }}</p><p>{{ t('supportedScopeKeys') }}: {{ savedMember.supportedScopeKeys.join(', ') }}</p><p>{{ t('audiences') }}: {{ savedMember.audiences.join(', ') }}</p></section>
        <section v-if="showRuntimePolicy" data-test="application-runtime-policy">
          <section v-if="localProject" aria-labelledby="local-project-title">
            <h2 id="local-project-title">{{ t('kestraActualPermissions') }}</h2>
            <div v-if="!policyEditing" data-test="application-policy-detail">
              <p>{{ t('enabled') }}: {{ t(localProject.enabled ? 'active' : 'inactive') }}</p>
              <KsButton v-if="localAllowed" :disabled="localDisabled" @click="policyEditing = true">{{ t('edit') }}</KsButton>
            </div>
            <form v-else @submit.prevent="saveLocalProject"><fieldset :disabled="localDisabled">
              <label>{{ t('issuer') }}<KsInput v-model="localProject.issuer" required name="local-issuer" /></label>
              <label>{{ t('audiences') }}<textarea :value="text(localProject.audiences)" required @input="localProject.audiences = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
              <p v-if="project">{{ t('objectRevision') }}: {{ project.revision }}</p>
              <label class="checkbox"><input v-model="localProject.enabled" type="checkbox">{{ t('enabled') }}</label>
              <KsButton type="primary" native-type="submit" data-test="save-local-project">{{ t('save') }}</KsButton>
              <KsButton native-type="button" :disabled="busy" @click="load">{{ t('cancel') }}</KsButton>
            </fieldset></form>
          </section>
          <label>{{ t('scope') }}<select v-model="permissionScope" :disabled="busy || conflicted" name="application-permission-scope" @change="selectApplicationPermission"><option disabled value="">{{ t('scope') }}</option><option v-for="scope in runtimeScopes" :key="scope.key" :value="scope.key">{{ scope.name }} ({{ scope.key }})</option></select></label>
          <section v-if="localScope" aria-labelledby="local-scope-title">
            <h2 id="local-scope-title">{{ t('kestraActualPermissions') }}</h2>
            <p>{{ t('scope') }}: {{ localScope.scopeKey }}</p>
            <p class="message">{{ t('completeScopeRule') }}</p>
            <KsButton v-if="localAllowed && !permissionEditing" :disabled="localDisabled" data-test="edit-application-permission" @click="permissionEditing = true">{{ t('edit') }}</KsButton>
            <form @submit.prevent="saveLocalScope"><fieldset :disabled="localDisabled || !permissionEditing || !localPolicyProject()">
              <label>{{ t('description') }}<KsInput v-model="localScope.description" name="local-scope-description" /></label>
              <label class="checkbox"><input v-model="localScope.enabled" type="checkbox">{{ t('enabled') }}</label>
              <GrantEditor :grants="localScope.grants" :management-grants="localScope.managementGrants" />
              <div v-if="permissionEditing" class="buttons"><KsButton type="primary" native-type="submit" data-test="save-local-scope">{{ t('save') }}</KsButton><KsButton v-if="localScopeExists" native-type="button" @click="confirmation = 'local'">{{ t('delete') }}</KsButton><KsButton native-type="button" @click="selectApplicationPermission">{{ t('cancel') }}</KsButton></div>
            </fieldset></form>
          </section>
        </section>
      </template>
      <template v-else-if="assignment">
        <KsButton :disabled="busy" @click="navigate(base + '/scope-grants')">{{ t('back') }}</KsButton>
        <form @submit.prevent="saveAssignment"><fieldset :disabled="identityDisabled">
          <label>{{ t('subject') }}<KsInput v-if="isNew" v-model="assignment.subject" required name="assignment-subject" /><output v-else>{{ assignment.subject }}</output></label>
          <p>{{ t('issuer') }}: {{ assignment.issuer }}</p><p>{{ t('objectRevision') }}: {{ isNew ? project?.revision : assignment.revision }}</p>
          <fieldset><legend>{{ t('scopeKeys') }}</legend><label v-for="name in [...new Set([...scopeChoices.map(item => item.key), ...assignment.scopeKeys])]" :key="name" class="checkbox"><input v-model="assignment.scopeKeys" type="checkbox" :value="name">{{ name }}</label><p v-if="!scopeChoices.length">{{ t('noRows') }}</p></fieldset>
          <div class="buttons"><KsButton type="primary" native-type="submit" data-test="save-assignment">{{ t('save') }}</KsButton><KsButton v-if="!isNew" native-type="button" @click="confirmation = 'assignment'">{{ t('revokeAssignment') }}</KsButton></div>
        </fieldset></form>
        <section v-if="savedAssignment" class="message"><h2>{{ t('saved') }}</h2><p>{{ t('subject') }}: {{ savedAssignment.subject }}</p><p>{{ t('scopeKeys') }}: {{ savedAssignment.scopeKeys.join(', ') }}</p><p>{{ t('objectRevision') }}: {{ savedAssignment.revision }} · {{ t('authorizationVersion') }}: {{ project?.authorizationVersion }}</p></section>
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
          <KsButton :disabled="identityDisabled" @click="navigate('application-groups')">{{ t('assignments') }}</KsButton>
        </template>
      </template>
      <section v-if="confirmation" class="message" role="alertdialog" :aria-label="t('confirm')"><p>{{ t('confirm') }}</p><div class="buttons"><KsButton :disabled="busy || conflicted || (confirmation === 'local' ? !localAllowed : !identityAllowed)" @click="remove">{{ t('delete') }}</KsButton><KsButton :disabled="busy" @click="confirmation = ''">{{ t('cancel') }}</KsButton></div></section>
    </template>
  </main>
</template>
