<script setup>
import {computed, inject, nextTick, onBeforeUnmount, reactive, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {useRoute, useRouter} from 'vue-router'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import KsTabs from '@kestra-io/design-system/components/Navigation/KsTabs/KsTabs'
import KsTabPane from '@kestra-io/design-system/components/Navigation/KsTabs/KsTabPane'
import ApplicationGroupCards from './ApplicationGroupCards.vue'
import AccessSidebar from './AccessSidebar.vue'
import AccessList from './AccessList.vue'
import PermissionTable from './PermissionTable.vue'
import PermissionFields from './PermissionFields.vue'
import ScopePicker from './ScopePicker.vue'
import EditorDrawer from './EditorDrawer.vue'
import DeleteConfirmation from './DeleteConfirmation.vue'
import InlineDetails from './InlineDetails.vue'
import UserFields from './UserFields.vue'
import OneTimeCredential from './OneTimeCredential.vue'
import CredentialsPanel from './CredentialsPanel.vue'
import AccountList from './AccountList.vue'
import {createAccount} from './account-actions.js'
import {decodeSearchParams} from '@kestra-io/design-system'
import {groupMetadataInput} from './text-limits.js'
import {contextFullPath} from './navigation-lifecycle.js'
import {newOneTimeValue, clearOneTimeValue} from './credential-state.js'
import {withAccountState, scopeRelations, blockingReferences, scopeReferenceLabels} from './scope-relations.js'
import {createDraftNavigationGuard} from './draft-navigation.js'
import {createClient, encodeKey, decodeKey} from './client.js'
import {canonicalPath, collectPages, copy, groupScopeInput, typedGrantsInput, managementAccess, requiresReauthentication, loginEntry, splitLines, isRuntimeApplication, permissionScopeKeys, permissionParentRevision} from './application-group-model.js'
import {collectPermissions, countValue, detailTabs, ordered, permissionInput, permissionDraft, selectionAfterDelete, readListQuery, nativeChips} from './access-control-model.js'
import {createEditorState, openEditor, requestEditorClose, editorDirty} from './editor-state.js'

const props = defineProps({context: {type: Object, required: true}})
const navigationHooks = inject('rbac-navigation-hooks', null)
const {t} = useI18n(), route = useRoute(), router = useRouter()
const api = createClient(() => props.context)
const main = ref(null), caps = ref(null), group = ref(null)
const groups = ref([]), scopes = ref([]), applications = ref([]), subjects = ref([]), users = ref([]), permissions = ref([])
const application = ref(null), selectedScope = ref(null)
const busy = ref(false), loaded = ref(false), error = ref(''), notice = ref(''), conflicted = ref(false), denied = ref(false), reauthenticate = ref(false)
const editor = reactive(createEditorState())
const oneTime = reactive(newOneTimeValue())
const credentialBusy = ref(false), pendingRoute = ref(null)
const invalidQuery = ref(false), queryRefreshing = ref(false)
const inline = ref(null), inlineBaseline = ref(''), discardInline = ref(false)
const deletion = ref(null), deletionError = ref(''), sourcePath = ref('')
const path = computed(() => canonicalPath(sourcePath.value.split('?')[0]))
const parts = computed(() => path.value.split('/'))
const isUsers = computed(() => parts.value[0] === 'users')
const groupId = computed(() => !isUsers.value && parts.value[1] ? decodeURIComponent(parts.value[1]) : '')
const tab = computed(() => parts.value[2] || 'overview')
const selection = computed(() => parts.value[3] ?? '')
const groupBase = computed(() => 'application-groups/' + encodeURIComponent(groupId.value))
const access = computed(() => managementAccess(caps.value, reauthenticate.value || denied.value))
const identityRead = computed(() => caps.value?.identityRead === true && !denied.value && !reauthenticate.value)
const identityManage = computed(() => identityRead.value && access.value.identity)
const isKestra = computed(() => application.value?.implementationKind === 'kestra-policy')
const permissionRead = computed(() => isKestra.value ? caps.value?.localPolicyRead === true : identityRead.value)
const permissionManage = computed(() => permissionRead.value && (isKestra.value ? access.value.local : identityManage.value))
const inlineDirty = computed(() => inline.value !== null && JSON.stringify(inline.value) !== inlineBaseline.value)
const editorTitle = computed(() => t(editor.draft?.revision !== undefined ? 'edit' : 'create') + ' · ' + (editor.kind === 'subject' ? 'subject' : editor.kind === 'permission' ? t('permissionName') : editor.kind === 'user' ? t('users') : t('applicationGroups')))
const selectedApplications = computed(() => scopeRelations(selectedScope.value?.key, applications.value, subjects.value).applications)
const selectedSubjects = computed(() => subjects.value.filter(item => item.scopeKeys.includes(selectedScope.value?.key)))
const subjectCandidates = computed(() => ordered(users.value.filter(user => user.active && !subjects.value.some(subject => subject.issuer === group.value?.issuer && subject.subject === user.subject)), 'subject'))
const columnsSubjects = computed(() => [{key: 'subject', label: 'subject'}, {key: 'scopeKeys', label: t('scopeKeys')}, {key: 'applications', label: t('involvedApplications')}, {key: 'operations', label: '', width: 100}])
const statistics = computed(() => [{field: 'scopes', tab: 'scopes', label: t('scopes')}, {field: 'applications', tab: 'applications', label: t('clients')}, {field: 'subjects', tab: 'scope-grants', label: 'subject'}])
let generation = 0, disposed = false, pendingNavigation = null, routingFromHost = false
let activeLoad = Promise.resolve()
const loginHref = computed(() => loginEntry(props.context.apiBase))
function clearSensitive(clearCredential = true) {
  if (clearCredential) clearOneTimeValue(oneTime)
  group.value = application.value = selectedScope.value = inline.value = null
  groups.value = []; scopes.value = []; applications.value = []; subjects.value = []; users.value = []; permissions.value = []
  Object.assign(editor, createEditorState()); deletion.value = null
}
function failure(problem) {
  if (problem.code === 'invalid_query') {invalidQuery.value = true; error.value = t('invalidQuery'); clearSensitive(); return}
  if ([401, 403].includes(problem.status)) {generation++; busy.value = false; credentialBusy.value = false}
  if (requiresReauthentication(problem)) {reauthenticate.value = true; caps.value = null; clearSensitive()}
  if ([401, 403].includes(problem.status)) {denied.value = true; caps.value = null; clearSensitive()}
  if ([409, 412, 428].includes(problem.status)) conflicted.value = true
  error.value = t([409, 412, 428].includes(problem.status) ? 'conflict' : [401, 403].includes(problem.status) ? 'denied' : problem.status === 404 ? 'notFound' : 'error')
}
function discardGuard(action, allowQueryRefresh = false) {
  if (busy.value && !(allowQueryRefresh && queryRefreshing.value) || editor.saving || credentialBusy.value) return
  if (oneTime.value) return
  if (editor.kind) {requestEditorClose(editor, action); return}
  if (inlineDirty.value) {pendingNavigation = action; discardInline.value = true; return}
  inline.value = null; action()
}
function navigate(target, query = {}, replace = false) {
  discardGuard(() => {void props.context.navigate(router.resolve({path: '/' + target, query}).fullPath.slice(1), {replace})})
}
function openInline(value, kind) {inline.value = {...copy(value), _kind: kind}; inlineBaseline.value = JSON.stringify(inline.value)}
function discardInlineConfirm() {inline.value = null; discardInline.value = false; const action = pendingNavigation; pendingNavigation = null; action?.()}
function load(...arguments_) {
  activeLoad = readView(...arguments_)
  return activeLoad
}
async function readView(value = contextFullPath(props.context), preserveOneTime = false, preserveView = false) {
  const current = ++generation
  queryRefreshing.value = preserveView
  busy.value = true; invalidQuery.value = false; error.value = ''; notice.value = ''; conflicted.value = false; denied.value = false
  if (!preserveView) {loaded.value = false; clearSensitive(!preserveOneTime)}
  sourcePath.value = value
  try {
    if (!preserveView) await nextTick()
    if (!['application-groups', 'users'].includes(parts.value[0])) throw {status: 404}
    routingFromHost = true
    try {await router.replace('/' + value.replace(/^\/+/, ''))} finally {routingFromHost = false}
    if (current !== generation || disposed) return
    const query = router.resolve('/' + value.replace(/^\/+/, '')).query
    const queryKind = isUsers.value ? 'users' : tab.value === 'scope-grants' ? 'subjects' : 'permissions'
    if (isUsers.value || groupId.value && ['applications', 'scope-grants'].includes(tab.value)) {
      try {
        readListQuery(query, queryKind)
        nativeChips(decodeSearchParams(query), queryKind)
      } catch {throw {status: 400, code: 'invalid_query'}}
    }
    const id = groupId.value, capability = await api.capabilities(groupId.value || undefined)
    if (current !== generation || disposed) return
    if (preserveView && JSON.stringify(caps.value) !== JSON.stringify(capability)) {loaded.value = false; clearSensitive()}
    caps.value = capability
    if (reauthenticate.value || !capability.allowed) throw {status: 403}
    const all = (fetcher, revision) => collectPages(fetcher, props.context.signal, revision)
    let g = null, gs = [], ss = [], apps = [], subs = [], us = [], ps = [], app = null, scope = null
    if (isUsers.value) {
      if (!capability.identityRead || !capability.userDirectory) throw {status: 403}
      us = await all((page, size) => api.users(page, size))
    } else if (!id) {
      if (!capability.identityRead) throw {status: 403}
      gs = await all((page, size) => api.applicationGroups(page, size))
    } else {
      if (!detailTabs.includes(tab.value)) throw {status: 404}
      if (capability.identityRead) {
        g = await api.applicationGroup(id)
        if (tab.value !== 'overview') ss = await all((page, size) => api.applicationGroupScopes(id, page, size), g.revision)
        if (['scopes', 'applications', 'scope-grants'].includes(tab.value)) apps = await all((page, size) => api.applications(id, page, size), g.revision)
        if (['scopes', 'scope-grants'].includes(tab.value)) {
          subs = await all((page, size) => api.scopeGrants(id, page, size), g.revision)
          if (capability.userDirectory) us = await all((page, size) => api.users(page, size))
          subs = withAccountState(subs, us)
        }
      } else if (tab.value !== 'applications' || !selection.value || !capability.localPolicyRead) throw {status: 403}
      if (tab.value === 'scopes') {
        apps = await Promise.all(apps.map(async item => {
          const runtime = isRuntimeApplication(capability, id, item.clientId)
          if (runtime && !capability.localPolicyRead) return item
          const rows = await collectPermissions((page, size) => api.permissions(id, item.clientId, page, size), props.context.signal, item.revision)
          return {...item, permissions: rows}
        }))
        scope = selection.value ? ss.find(item => item.key === decodeURIComponent(selection.value)) : ordered(ss)[0] ?? null
        if (selection.value && !scope) throw {status: 404}
      }
      if (tab.value === 'applications') {
        const clientId = selection.value ? decodeKey(selection.value) : ordered(apps, 'clientId')[0]?.clientId
        if (clientId) {
          if (!capability.identityRead && !isRuntimeApplication(capability, id, clientId)) throw {status: 403}
          app = capability.identityRead ? await api.application(id, clientId) : {clientId, implementationKind: 'kestra-policy'}
          if (app.implementationKind === 'kestra-policy' ? capability.localPolicyRead : capability.identityRead) ps = await collectPermissions((page, size) => api.permissions(id, clientId, page, size), props.context.signal, app.revision)
          if (query.filterSubject !== undefined) {
            if (!capability.identityRead || typeof query.filterSubject !== 'string') throw {status: 403}
            const subject = await api.scopeGrant(id, query.filterSubject)
            const account = await api.user(subject.subject)
            if (account.active !== true) throw {status: 403}
            const keys = (Array.isArray(query.scopeKeys) ? query.scopeKeys : [query.scopeKeys]).filter(key => subject.scopeKeys.includes(key))
            if (!keys.length) ps = []
            else {
              if (current !== generation || disposed) return
              await router.replace({path: route.path, query: {...query, scopeKeys: keys}})
            }
          }
        }
      }
    }
    if (current !== generation || disposed) return
    group.value = g; groups.value = gs; scopes.value = ss; applications.value = apps; subjects.value = subs; users.value = us; permissions.value = ps; application.value = app; selectedScope.value = scope; loaded.value = true
    if (!selection.value && (scope || app)) {
      const key = scope ? encodeURIComponent(scope.key) : encodeKey(app.clientId)
      void props.context.navigate(groupBase.value + '/' + tab.value + '/' + key, {replace: true})
    }
  } catch (problem) {
    if (current === generation && !disposed && problem.name !== 'AbortError') {
      if (preserveView) {loaded.value = false; clearSensitive()}
      failure(problem)
    }
  }
  finally {if (current === generation && !disposed) {busy.value = false; queryRefreshing.value = false}}
}
function clearInvalidQuery() {
  const target = sourcePath.value.split('?')[0]
  void props.context.navigate(target, {replace: true})
}
async function mutate(action, afterSuccess) {
  if (busy.value || conflicted.value || denied.value || reauthenticate.value) return
  let current = generation
  busy.value = true; editor.saving = Boolean(editor.kind); error.value = ''; deletionError.value = ''
  try {
    await action()
    if (disposed || current !== generation) return
    Object.assign(editor, createEditorState()); inline.value = null; deletion.value = null
    const canonicalTarget = afterSuccess?.()
    const reload = load(canonicalTarget ?? sourcePath.value, Boolean(oneTime.value))
    current = generation
    await reload
    if (disposed || current !== generation || !loaded.value || denied.value || reauthenticate.value) return
    // The transaction and authorized reload have finished. The ordinary public
    // navigation guard now permits only this completed operation's canonical URL.
    if (canonicalTarget) await props.context.navigate(canonicalTarget, {replace: true})
    notice.value = t('saved')
  } catch (problem) {
    if (current === generation && !disposed && problem.name !== 'AbortError') {
      failure(problem); deletionError.value = error.value
      if (problem.code === 'scope_in_use' && deletion.value) {
        deletion.value.blocked = true
        deletion.value.references = [...new Set([...(deletion.value.references ?? []), ...scopeReferenceLabels(problem.references)])]
      }
      await nextTick(); main.value?.querySelector('[role="alert"]')?.focus()
    }
  } finally {if (!disposed && current === generation) {busy.value = false; editor.saving = false}}
}
async function newPermission() {
  if (!permissionManage.value || busy.value || editor.kind) return
  const current = generation
  if (!identityRead.value) {
    busy.value = true
    try {
      const revision = await permissionParentRevision(api, groupId.value, application.value.clientId)
      if (disposed || current !== generation) return
      application.value = {...application.value, revision}
    } catch (problem) {if (!disposed && current === generation) failure(problem); return}
    finally {if (!disposed && current === generation) busy.value = false}
  }
  if (permissionManage.value) openEditor(editor, 'permission', permissionDraft(null, application.value.implementationKind))
}
async function openSubject(value) {
  if (!identityManage.value) return
  if (!value) {
    busy.value = true
    try {users.value = await collectPages((page, size) => api.users(page, size), props.context.signal)} catch (problem) {failure(problem); return} finally {busy.value = false}
  }
  openEditor(editor, 'subject', value ?? {issuer: group.value.issuer, subject: '', scopeKeys: []})
}
function saveEditor() {
  const draft = editor.draft
  if (editor.kind === 'permission' && permissionManage.value) {
    let input
    try {
      input = permissionInput(draft, application.value.implementationKind, permissionScopeKeys(draft.scopeKeys, scopes.value, identityRead.value))
      if (isKestra.value) {
        const typed = typedGrantsInput(input)
        input.grants = typed.grants; input.managementGrants = typed.managementGrants
      }
    } catch (problem) {failure(problem); return}
    void mutate(() => draft.revision === undefined ? api.createPermission(groupId.value, application.value.clientId, input, application.value.revision) : api.updatePermission(groupId.value, application.value.clientId, draft.key, input, draft.revision))
  } else if (editor.kind === 'group' && identityManage.value) void mutate(() => api.createApplicationGroup({id: draft.id, tenant: 'main', ...groupMetadataInput(draft)}))
  else if (editor.kind === 'subject' && identityManage.value) {
    const input = {issuer: draft.issuer, subject: draft.subject, scopeKeys: [...draft.scopeKeys]}
    void mutate(() => draft.revision === undefined ? api.createScopeGrant(groupId.value, input, group.value.revision) : api.updateScopeGrant(groupId.value, input, draft.revision))
  } else if (editor.kind === 'user' && identityManage.value) {
    const current = generation
    void mutate(() => draft.revision === undefined
      ? createAccount(api, draft, oneTime, () => !disposed && current === generation)
      : api.updateProfile({subject: draft.subject, name: draft.name, email: draft.email}, draft.revision))
  }
}
function saveInline() {
  if (!identityManage.value || !inline.value) return
  const draft = inline.value
  if (draft._kind === 'scope') {
    let input
    try {input = groupScopeInput(draft)} catch (problem) {failure(problem); return}
    void mutate(() => draft.revision === undefined ? api.createApplicationGroupScope(groupId.value, input, group.value.revision) : api.updateApplicationGroupScope(groupId.value, draft.key, input, draft.revision))
  } else if (draft._kind === 'application') {
    const input = {clientId: draft.clientId, description: draft.description, audiences: splitLines(draft.audiences)}
    void mutate(() => draft.revision === undefined ? api.createApplication(groupId.value, input, group.value.revision) : api.updateApplication(groupId.value, draft.clientId, input, draft.revision))
  } else void mutate(() => api.updateApplicationGroup(groupId.value, {...groupMetadataInput(draft), active: draft.active}, draft.revision))
}
function askDelete(kind, value) {discardGuard(() => {
  const references = kind === 'scope' ? blockingReferences(value.key, applications.value, subjects.value) : []
  deletion.value = {kind, value, references, blocked: references.length > 0}; deletionError.value = ''
})}
function deleteConfirmed() {
  const {kind, value} = deletion.value
  const next = kind === 'scope' ? (selectedScope.value?.key !== value.key ? selectedScope.value?.key : selectionAfterDelete(scopes.value, value.key)) : kind === 'application' ? (application.value?.clientId !== value.clientId ? application.value?.clientId : selectionAfterDelete(applications.value, value.clientId, 'clientId')) : ''
  if (deletion.value.blocked) return
  const operation = kind === 'user' ? () => api.deleteUser(value.subject, value.revision) : kind === 'permission' ? () => api.deletePermission(groupId.value, application.value.clientId, value.key, value.revision) : kind === 'scope' ? () => api.deleteApplicationGroupScope(groupId.value, value.key, value.revision) : kind === 'application' ? () => api.deleteApplication(groupId.value, value.clientId, value.revision) : kind === 'group' ? () => api.deleteApplicationGroup(groupId.value, value.revision) : () => api.deleteScopeGrant(groupId.value, value.subject, value.revision)
  void mutate(operation, () => {
    if (kind === 'scope' || kind === 'application') return groupBase.value + '/' + (kind === 'scope' ? 'scopes' : 'applications') + (next ? '/' + (kind === 'scope' ? encodeURIComponent(next) : encodeKey(next)) : '')
    if (kind === 'group') return 'application-groups'
  })
}
const toScope = key => navigate(groupBase.value + '/scopes/' + encodeURIComponent(key))
const toApplication = (clientId, query = {}) => navigate(groupBase.value + '/applications/' + encodeKey(clientId), query)
const subjectApps = subject => subject.active === false ? [] : applications.value.filter(item => item.active !== false && item.supportedScopeKeys?.some(key => subject.scopeKeys.includes(key)))
function beforeUnload(event) {if (editorDirty(editor) || inlineDirty.value) {event.preventDefault(); event.returnValue = ''}}
const removeRouteGuard = router.beforeEach(createDraftNavigationGuard(
  () => ({saving: !routingFromHost && (busy.value && !queryRefreshing.value || editor.saving || credentialBusy.value || Boolean(oneTime.value)), dirty: !routingFromHost && (editorDirty(editor) || inlineDirty.value)}),
  resolve => {
    pendingRoute.value = resolve
    if (editor.kind) requestEditorClose(editor, () => {pendingRoute.value = null; resolve(true)})
    else {pendingNavigation = () => {pendingRoute.value = null; resolve(true)}; discardInline.value = true}
  }
))
if (navigationHooks) {
  navigationHooks.guard = createDraftNavigationGuard(
    () => ({saving: busy.value && !queryRefreshing.value || editor.saving || credentialBusy.value || Boolean(oneTime.value), dirty: editorDirty(editor) || inlineDirty.value}),
    resolve => {
      pendingRoute.value = resolve
      if (editor.kind) requestEditorClose(editor, () => {pendingRoute.value = null; resolve(true)})
      else {pendingNavigation = () => {pendingRoute.value = null; resolve(true)}; discardInline.value = true}
    }
  )
  navigationHooks.cleanup = () => {cancelPendingRoute(); clearSensitive()}
}
let pendingQueryTarget = null, queryNavigationRunning = false, queryNavigationEpoch = 0
const queryAcknowledgements = new Map()
async function flushQueryNavigation() {
  if (queryNavigationRunning) return
  const epoch = queryNavigationEpoch
  queryNavigationRunning = true
  try {
    while (pendingQueryTarget && !disposed && epoch === queryNavigationEpoch) {
      const target = pendingQueryTarget
      pendingQueryTarget = null
      queryAcknowledgements.set(target, epoch)
      try {
        await props.context.navigate(target, {replace: true})
        await nextTick()
        if (epoch !== queryNavigationEpoch && !disposed) {
          // An external navigation or tenant update cancelled this owned query.
          // Its delayed acknowledgement must not undo the accepted host destination.
          await activeLoad
          if (!disposed && contextFullPath(props.context) !== sourcePath.value) {
            await props.context.navigate(sourcePath.value, {replace: true})
            await nextTick()
          }
        }
      } finally {queryAcknowledgements.delete(target)}
    }
  } catch (problem) {
    if (!disposed && epoch === queryNavigationEpoch) {
      pendingQueryTarget = null
      routingFromHost = true
      try {await router.replace('/' + contextFullPath(props.context))} finally {routingFromHost = false}
      failure(problem)
    }
  } finally {
    queryNavigationRunning = false
    if (pendingQueryTarget && !disposed) void flushQueryNavigation()
  }
}
const removeRouteSync = router.afterEach(async (to, from, navigationFailure) => {
  if (navigationFailure || disposed || routingFromHost || to.fullPath === from.fullPath || to.fullPath.replace(/^\//, '') === contextFullPath(props.context)) return
  if (to.path === from.path && to.path === '/' + sourcePath.value.split('?')[0]) {
    pendingQueryTarget = to.fullPath.replace(/^\//, '')
    void flushQueryNavigation()
    return
  }
  try {await props.context.navigate(to.fullPath.replace(/^\//, ''), {replace: true})}
  catch (problem) {if (!disposed) failure(problem)}
})
function cancelPendingRoute() {pendingRoute.value?.(false); pendingRoute.value = null}
watch(() => editor.discard, value => {if (!value && editor.kind) cancelPendingRoute()})
watch(discardInline, value => {if (!value && inline.value) cancelPendingRoute()})
window.addEventListener('beforeunload', beforeUnload)
watch(() => contextFullPath(props.context), value => {
  if (queryAcknowledgements.has(value) && (queryAcknowledgements.get(value) !== queryNavigationEpoch || router.currentRoute.value.fullPath.replace(/^\//, '') !== value)) return
  if (value.split('?')[0] !== sourcePath.value.split('?')[0]) {queryNavigationEpoch++; pendingQueryTarget = null}
  if (value === sourcePath.value && loaded.value) return
  const preserveView = loaded.value && !denied.value && !reauthenticate.value && value.split('?')[0] === sourcePath.value.split('?')[0]
  discardGuard(() => {void load(value, false, preserveView)}, preserveView)
}, {immediate: true})
watch(() => props.context.tenant, () => {queryNavigationEpoch++; pendingQueryTarget = null; generation++; clearSensitive(); void load()})
onBeforeUnmount(() => {disposed = true; queryNavigationEpoch++; pendingQueryTarget = null; queryAcknowledgements.clear(); generation++; removeRouteGuard(); removeRouteSync(); cancelPendingRoute(); window.removeEventListener('beforeunload', beforeUnload); clearSensitive()})
</script>

<template>
  <main ref="main" tabindex="-1" data-test="access-control-root" :aria-busy="busy">
    <KsTabs v-if="!groupId" :model-value="isUsers ? 'users' : 'application-groups'" @update:model-value="navigate($event)"><KsTabPane name="application-groups" :label="t('applicationGroups')" data-test="tab-application-groups" /><KsTabPane name="users" :label="t('users')" data-test="tab-users" /></KsTabs>
    <template v-else><header class="rbac-detail-title"><KsButton :disabled="busy" :aria-label="t('back')" @click="navigate('application-groups')">←</KsButton><h1><code>{{ groupId }}</code></h1></header><KsTabs :model-value="tab" @update:model-value="navigate(groupBase + ($event === 'overview' ? '' : '/' + $event))"><KsTabPane name="overview" :label="t('overview')" /><KsTabPane name="scopes" :label="t('scopes')" /><KsTabPane name="applications" :label="t('clients')" /><KsTabPane name="scope-grants" label="subject" /></KsTabs></template>
    <div v-if="error" class="message error" role="alert" tabindex="-1" data-test="request-error"><p>{{ error }}</p><KsButton v-if="invalidQuery" :disabled="busy" @click="clearInvalidQuery">{{ t('clearFilters') }}</KsButton><KsButton v-else-if="conflicted" :disabled="busy" data-test="conflict-refresh" @click="discardGuard(() => load())">{{ t('reload') }}</KsButton><KsButton v-else-if="!denied && !reauthenticate" :disabled="busy" @click="load()">{{ t('reload') }}</KsButton><a v-if="denied || reauthenticate" :href="loginHref" data-test="sign-in-again">{{ t('signInAgain') }}</a></div>
    <p v-if="notice" role="status" class="message">{{ notice }}</p><p v-if="busy && !loaded" role="status">{{ t('loading') }}</p>
    <template v-if="loaded && !denied && !reauthenticate">
      <template v-if="!groupId && !isUsers"><header class="management-toolbar"><KsButton v-if="identityManage" :disabled="busy" @click="openEditor(editor, 'group', {id: '', displayName: '', description: ''})">＋ {{ t('create') }}</KsButton></header><ApplicationGroupCards :items="groups" :busy="busy" storage-key="rbac-v3-groups" @open="navigate('application-groups/' + encodeURIComponent($event.id))" @section="(item, section) => navigate('application-groups/' + encodeURIComponent(item.id) + '/' + section)" @refresh="load()" /></template>
      <AccountList v-else-if="isUsers" :items="users" :busy="busy" :can-manage="identityManage" @create="openEditor(editor, 'user', {username:'', name:'', email:''})" @edit="openEditor(editor, 'user', $event)" @delete="askDelete('user', $event)" @state="row => mutate(() => api.updateState(row.subject, !row.active, row.revision))" @refresh="load()" />
      <section v-else-if="tab === 'overview'" class="rbac-overview" data-test="group-detail"><template v-if="group"><dl class="rbac-details"><dt>{{ t('displayName') }}</dt><dd>{{ group.displayName }}</dd><dt>{{ t('id') }}</dt><dd><code>{{ group.id }}</code></dd><dt>{{ t('description') }}</dt><dd>{{ group.description || '—' }}</dd><template v-if="group.builtIn"><dt>{{ t('builtinGroup') }}</dt><dd>◆</dd></template><dt>Issuer</dt><dd>{{ group.issuer }}</dd></dl><div class="rbac-statistics"><KsButton v-for="stat in statistics" :key="stat.field" link :disabled="countValue(group.counts, stat.field) === null" @click="navigate(groupBase + '/' + stat.tab)"><b>{{ countValue(group.counts, stat.field) ?? '—' }}</b> {{ stat.label }}</KsButton></div><div v-if="identityManage && !inline" class="buttons"><KsButton data-test="group-edit" @click="openInline(group, 'group')">{{ t('edit') }}</KsButton><KsButton v-if="!group.builtIn" @click="askDelete('group', group)">{{ t('delete') }}</KsButton></div></template><InlineDetails v-if="inline" :draft="inline" :disabled="busy || conflicted" :busy="busy" @save="saveInline" @cancel="discardGuard(() => {inline = null})" /></section>
      <div v-else-if="tab === 'scopes'" class="rbac-split">
        <AccessSidebar :items="scopes" :selected="selectedScope?.key" :title="t('scopes')" :disabled="busy || credentialBusy" :can-manage="identityManage" kind="scope" @select="toScope($event.key)" @create="discardGuard(() => openInline({key:'',name:'',description:''}, 'scope'))" @delete="askDelete('scope', $event)" />
        <section class="rbac-detail" data-test="scope-detail">
          <InlineDetails v-if="inline" :draft="inline" :disabled="busy || conflicted" :busy="busy" @save="saveInline" @cancel="discardGuard(() => {inline = null})" />
          <template v-else-if="selectedScope">
            <h2><code>{{ selectedScope.key }}</code></h2>
            <dl class="rbac-details"><dt>{{ t('roleName') }}</dt><dd>{{ selectedScope.name }}</dd><dt>{{ t('description') }}</dt><dd>{{ selectedScope.description || '—' }}</dd></dl>
            <KsButton v-if="identityManage" @click="openInline(selectedScope, 'scope')">{{ t('edit') }}</KsButton>
            <h2>{{ t('boundApplications') }}</h2>
            <div v-for="item in selectedApplications" :key="item.clientId" class="rbac-tags">
              <KsButton link @click="toApplication(item.clientId)"><code>{{ item.clientId }}</code></KsButton>
              <KsButton v-for="permission in item.permissions" :key="permission.key" link :data-permission-key="permission.key" @click="toApplication(item.clientId, {permissionKey:permission.key})"><code>{{ permission.key }}</code></KsButton>
            </div>
            <p v-if="!selectedApplications.length">—</p><h2>{{ t('scopeKeys') }}</h2>
            <div class="rbac-tags"><KsButton v-for="item in selectedSubjects" :key="item.subject" link @click="navigate(groupBase + '/scope-grants', {filterSubject:item.subject})"><code>{{ item.subject }}</code></KsButton><p v-if="!selectedSubjects.length">—</p></div>
          </template><p v-else>{{ t('noRows') }}</p>
        </section>
      </div>
      <div v-else-if="tab === 'applications'" class="rbac-split">
        <AccessSidebar v-if="identityRead" :items="applications" field="clientId" :selected="application?.clientId" :title="t('clients')" :disabled="busy || credentialBusy" :can-manage="identityManage" kind="application" @select="toApplication($event.clientId)" @create="discardGuard(() => openInline({clientId:'',description:'',audiences:[]}, 'application'))" @delete="askDelete('application', $event)" />
        <section class="rbac-detail" data-test="application-detail">
          <InlineDetails v-if="inline" :draft="inline" :disabled="busy || conflicted" :busy="busy" @save="saveInline" @cancel="discardGuard(() => {inline = null})" />
          <template v-else-if="application">
            <h2><code>{{ application.clientId }}</code></h2>
            <dl v-if="identityRead" class="rbac-details"><dt>{{ t('clientId') }}</dt><dd><code>{{ application.clientId }}</code></dd><dt>{{ t('description') }}</dt><dd>{{ application.description || '—' }}</dd><dt>Audience</dt><dd>{{ application.audiences?.join(', ') || '—' }}</dd><dt>client secret</dt>
              <dd><CredentialsPanel :api="api" :group-id="groupId" :client-id="application.clientId" :can-manage="identityManage" :one-time="oneTime" @failure="failure" @busy="credentialBusy = $event" /></dd>
              <dt>{{ t('supportedScopes') }}</dt><dd class="rbac-tags" data-test="application-supported-scopes"><KsButton v-for="key in application.supportedScopeKeys" :key="key" link @click="toScope(key)"><code>{{ key }}</code></KsButton><span v-if="!application.supportedScopeKeys?.length">—</span></dd>
            </dl>
            <KsButton v-if="identityManage" :disabled="credentialBusy" @click="openInline(application, 'application')">{{ t('edit') }}</KsButton>
            <PermissionTable v-if="permissionRead" :items="permissions" :scopes="scopes" :group-id="groupId" :implementation-kind="application.implementationKind" :busy="busy || credentialBusy" :can-manage="permissionManage" @create="newPermission" @edit="permissionManage && openEditor(editor, 'permission', permissionDraft($event, application.implementationKind))" @delete="askDelete('permission', $event)" @scope="toScope" @refresh="load()" />
            <p v-else class="message">{{ t('denied') }}</p>
          </template><p v-else>{{ t('noRows') }}</p>
        </section>
      </div>
      <AccessList v-else-if="tab === 'scope-grants'" :items="subjects" kind="subjects" :columns="columnsSubjects" :scopes="scopes" :busy="busy" :can-manage="identityManage" @create="openSubject()" @refresh="load()"><template #subject="{row}"><KsButton link @click="navigate('users', {filterSubject: row.subject})"><code data-test="subject-row" :data-subject="row.subject">{{ row.subject }}</code></KsButton></template><template #scopeKeys="{row}"><div class="rbac-tags"><KsButton v-for="key in row.scopeKeys" :key="key" link @click="toScope(key)"><code>{{ key }}</code></KsButton></div></template><template #applications="{row}"><div class="rbac-tags"><KsButton v-for="item in subjectApps(row)" :key="item.clientId" link @click="toApplication(item.clientId, {filterSubject: row.subject, scopeKeys: row.scopeKeys})"><code>{{ item.clientId }}</code></KsButton></div></template><template #operations="{row}"><div v-if="identityManage" class="buttons"><KsButton :disabled="busy" :aria-label="t('edit')" @click="openSubject(row)">✎</KsButton><KsButton :disabled="busy" :aria-label="t('delete')" @click="askDelete('subject', row)">×</KsButton></div></template></AccessList>
    </template>
    <EditorDrawer :state="editor" :title="editorTitle" :disabled="editor.kind === 'permission' ? !permissionManage : !identityManage" :conflicted="conflicted" @save="saveEditor" @refresh="requestEditorClose(editor, () => load())">
      <template #default="{draft}"><template v-if="draft">
        <PermissionFields v-if="editor.kind === 'permission'" :draft="draft" :scopes="scopes" :known-scope-keys-only="!identityRead" :implementation-kind="application?.implementationKind ?? ''" :editing="draft.revision !== undefined" />
        <template v-else-if="editor.kind === 'group'"><label class="rbac-kv-field"><span>{{ t('id') }}</span><KsInput v-model="draft.id" required maxlength="128" /></label><label class="rbac-kv-field"><span>{{ t('displayName') }}</span><KsInput v-model="draft.displayName" required maxlength="512" /></label><label class="rbac-kv-field"><span>{{ t('description') }}</span><KsInput v-model="draft.description" type="textarea" :rows="3" maxlength="8192" /></label></template>
        <template v-else-if="editor.kind === 'subject'"><label class="rbac-kv-field"><span>subject</span><output v-if="draft.revision !== undefined"><code>{{ draft.subject }}</code></output><KsSelect v-else v-model="draft.subject" filterable><KsOption v-for="item in subjectCandidates" :key="item.subject" :value="item.subject" :label="item.subject" /></KsSelect></label><div class="rbac-kv-field"><span>{{ t('scopeKeys') }}</span><ScopePicker v-model="draft.scopeKeys" :scopes="scopes" /></div></template>
        <UserFields v-else-if="editor.kind === 'user'" :draft="draft" />
      </template></template>
      <template #error><p v-if="error" role="alert" class="error" data-test="request-error">{{ error }}</p></template>
    </EditorDrawer>
    <DeleteConfirmation :open="Boolean(deletion)" :busy="busy" :blocked="deletion?.blocked" :references="deletion?.references ?? []" :error="deletionError" :label="deletion?.value.key ?? deletion?.value.clientId ?? deletion?.value.subject ?? deletion?.value.id" @cancel="deletion = null; deletionError = ''" @confirm="deleteConfirmed" />
    <OneTimeCredential :state="oneTime" />
    <DeleteConfirmation :open="discardInline" :busy="busy" discard :label="t('discardMessage')" @cancel="discardInline = false; pendingNavigation = null" @confirm="discardInlineConfirm" />
  </main>
</template>
