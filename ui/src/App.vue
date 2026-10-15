<script setup>
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import KsTable from '@kestra-io/design-system/components/Data/KsTable/KsTable'
import KsTableColumn from '@kestra-io/design-system/components/Data/KsTable/KsTableColumn'
import {createClient} from './client.js'

const props = defineProps({context: {type: Object, required: true}})
const {t} = useI18n()
const api = createClient(() => props.context)
const policy = ref(null)
const capabilities = ref(null)
const users = ref({items: [], total: 0, page: 1, size: 20})
const role = ref(null)
const user = ref(null)
const password = ref('')
const subject = ref('')
const trustedRole = ref('')
const selected = ref([])
const busy = ref(false)
const error = ref('')
const notice = ref('')
const confirmDelete = ref(false)
const conflicted = ref(false)
let pendingLoad = false
const resources = ['FLOW', 'EXECUTION', 'NAMESPACE', 'LOG', 'METRIC', 'TRIGGER', 'KVSTORE', 'SECRET', 'NAMESPACE_FILE', 'DASHBOARD', 'BLUEPRINT', 'MCP_SERVER', 'AI']
const actions = ['READ', 'LIST', 'CREATE', 'UPDATE', 'DELETE', 'EXECUTE', 'ACCESS_OUTPUTS']
const path = computed(() => props.context.path.replace(/^\/+|\/+$/g, '') || 'roles')
const section = computed(() => path.value.split('/')[0])
const detail = computed(() => path.value.split('/')[1])
const readOnly = computed(() => policy.value?.readOnly !== false || conflicted.value)
const protectedUser = computed(() => policy.value?.protectedSubjects?.includes(user.value?.subject))
const roles = computed(() => policy.value?.policy.roles ?? [])
const bindings = computed(() => [false, true].flatMap(mapping => Object.entries(mapping ? policy.value?.policy.roleMapping ?? {} : policy.value?.policy.subjects ?? {}).map(([key, identifiers]) => ({key, identifiers, mapping}))))
const copy = value => JSON.parse(JSON.stringify(value))
const encodeSubject = value => btoa(String.fromCharCode(...new TextEncoder().encode(value))).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
const decodeSubject = value => new TextDecoder('utf-8', {fatal: true}).decode(Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), character => character.charCodeAt(0)))
const navigate = value => props.context.navigate(value)

async function perform(action) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try { await action() }
  catch (failure) {
    if (failure.status === 412) conflicted.value = true
    error.value = failure.code === 'protected_subject' ? t('protected') : failure.code === 'policy_read_only' ? t('readOnly') : failure.status === 412 || failure.status === 409 || failure.status === 428 ? t('conflict') : failure.status === 403 || failure.status === 401 ? t('denied') : t('error')
  }
  finally {
    busy.value = false
    if (pendingLoad) { pendingLoad = false; queueMicrotask(load) }
  }
}

async function load() {
  if (busy.value) { pendingLoad = true; return }
  await perform(async () => {
    const requestedPath = path.value
    const requestedSection = section.value
    const requestedDetail = detail.value
    const nextCapabilities = await api.capabilities()
    if (!nextCapabilities.allowed) {
      capabilities.value = nextCapabilities
      policy.value = null
      throw {status: 403}
    }
    const nextPolicy = await api.policy()
    let nextUser = null
    let nextUsers = null
    if (requestedSection === 'users') {
      if (requestedDetail === 'new') nextUser = {username: '', name: '', email: '', password: ''}
      else if (requestedDetail) nextUser = await api.user(decodeSubject(requestedDetail))
      else nextUsers = await api.users(users.value.page)
    }
    if (path.value !== requestedPath) { pendingLoad = true; return }
    capabilities.value = nextCapabilities
    policy.value = nextPolicy
    conflicted.value = false
    confirmDelete.value = false
    password.value = ''
    subject.value = ''
    trustedRole.value = ''
    selected.value = []
    if (requestedSection === 'roles') {
      const existing = roles.value.find(item => item.id === requestedDetail)
      role.value = requestedDetail === 'new' ? {id: crypto.randomUUID(), name: '', description: '', grants: []} : existing ? copy(existing) : null
      if (requestedDetail && !role.value) throw {status: 404}
    } else if (requestedSection === 'users') {
      user.value = nextUser
      if (nextUsers) users.value = nextUsers
    }
  })
}

async function saveRole() {
  await perform(async () => {
    const value = copy(role.value)
    value.grants.forEach(grant => {
      grant.ids = typeof grant.ids === 'string' ? grant.ids.split(',').map(item => item.trim()).filter(Boolean) : grant.ids
      if (grant.allIds) grant.ids = []
      if (grant.scope === 'TENANT') { grant.namespace = null; grant.descendants = false }
    })
    policy.value = detail.value === 'new' ? await api.createRole(value, policy.value.revision) : await api.updateRole(value.id, value, policy.value.revision)
    notice.value = t('saved')
    if (detail.value === 'new') navigate('roles/' + value.id)
  })
}

function addGrant() {
  role.value.grants.push({scope: 'NAMESPACE', tenant: 'main', namespace: '', descendants: false, resource: 'FLOW', actions: [], ids: [], allIds: true})
}

async function saveUser() {
  await perform(async () => {
    if (detail.value === 'new') {
      const created = await api.createUser(user.value)
      user.value.password = ''
      navigate('users/' + encodeSubject(created.subject))
    } else user.value = await api.updateProfile({subject: user.value.subject, name: user.value.name, email: user.value.email}, user.value.revision)
    notice.value = t('saved')
  })
}

function chooseBinding(key, identifiers, mapping) {
  if (mapping) { trustedRole.value = key; subject.value = '' }
  else { subject.value = key; trustedRole.value = '' }
  selected.value = [...identifiers]
}

async function saveBinding() {
  await perform(async () => {
    if (Boolean(subject.value.trim()) === Boolean(trustedRole.value.trim())) throw {status: 400}
    policy.value = trustedRole.value
      ? await api.roleMappings(trustedRole.value, selected.value, policy.value.revision)
      : await api.bindings(subject.value, selected.value, policy.value.revision)
    notice.value = t('saved')
  })
}

watch(path, () => {role.value = null; user.value = null; password.value = ''; load()}, {immediate: true})
</script>

<template>
  <main :aria-busy="busy">
    <header><h1>{{ t('title') }}</h1><KsButton :disabled="busy" @click="load">{{ t('reload') }}</KsButton></header>
    <nav :aria-label="t('title')">
      <button v-for="tab in ['roles', 'users', 'bindings']" :key="tab" :aria-current="section === tab ? 'page' : undefined" @click="navigate(tab)">{{ t(tab) }}</button>
    </nav>
    <p v-if="busy" role="status">{{ t('loading') }}</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message" role="status">{{ notice }}</p>
    <template v-if="capabilities?.allowed && policy">
      <p class="muted">{{ t('policyMode') }}: {{ policy.mode }} · {{ t('revision') }}: {{ policy.revision }}</p>
      <p v-if="readOnly" class="message">{{ t('readOnly') }}</p>
      <template v-if="section === 'roles' && !detail">
        <KsButton data-test="create-role" :disabled="readOnly || busy" @click="navigate('roles/new')">{{ t('create') }}</KsButton>
        <div class="table-wrap"><KsTable :data="roles" :empty-text="t('noRows')" row-key="id">
          <template #empty><p>{{ t('noRows') }}</p></template>
          <KsTableColumn prop="name" :label="t('name')" min-width="180" />
          <KsTableColumn prop="description" :label="t('description')" min-width="240" />
          <KsTableColumn :label="t('actions')" min-width="120"><template #default="{row}"><KsButton :data-role-id="row.id" @click="navigate('roles/' + row.id)">{{ t('edit') }}</KsButton></template></KsTableColumn>
        </KsTable></div>
      </template>
      <form v-else-if="section === 'roles' && role" @submit.prevent="saveRole">
        <KsButton native-type="button" @click="navigate('roles')">{{ t('back') }}</KsButton>
        <fieldset :disabled="busy || readOnly">
          <label>{{ t('name') }}<KsInput v-model="role.name" name="role-name" required /></label>
          <label>{{ t('description') }}<KsInput v-model="role.description" name="role-description" /></label>
          <section v-for="(grant, index) in role.grants" :key="index" class="grant">
            <label>{{ t('namespace') }}<select v-model="grant.scope" name="grant-scope"><option>NAMESPACE</option><option>TENANT</option></select></label>
            <label v-if="grant.scope === 'NAMESPACE'">{{ t('namespace') }}<KsInput v-model="grant.namespace" name="grant-namespace" required /></label>
            <label v-if="grant.scope === 'NAMESPACE'" class="checkbox"><input v-model="grant.descendants" type="checkbox">{{ t('children') }}</label>
            <label>{{ t('resource') }}<select v-model="grant.resource" name="grant-resource"><option v-for="resource in resources" :key="resource">{{ resource }}</option></select></label>
            <fieldset><legend>{{ t('actions') }}</legend><label v-for="action in actions" :key="action" class="checkbox"><input v-model="grant.actions" name="grant-action" type="checkbox" :value="action">{{ action }}</label></fieldset>
            <label class="checkbox"><input v-model="grant.allIds" type="checkbox">{{ t('allIds') }}</label>
            <label v-if="!grant.allIds">{{ t('ids') }}<input :value="Array.isArray(grant.ids) ? grant.ids.join(', ') : grant.ids" @input="grant.ids = $event.target.value"></label>
            <KsButton native-type="button" @click="role.grants.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
          </section>
          <div class="buttons"><KsButton data-test="add-grant" native-type="button" @click="addGrant">{{ t('addGrant') }}</KsButton><KsButton data-test="save-role" native-type="submit">{{ t('save') }}</KsButton><KsButton v-if="detail !== 'new'" native-type="button" @click="confirmDelete = true">{{ t('delete') }}</KsButton></div>
          <div v-if="confirmDelete" class="message"><p>{{ t('confirm') }}</p><KsButton native-type="button" @click="perform(async () => { policy = await api.deleteRole(role.id, policy.revision); navigate('roles') })">{{ t('delete') }}</KsButton><KsButton native-type="button" @click="confirmDelete = false">{{ t('cancel') }}</KsButton></div>
        </fieldset>
      </form>
      <template v-else-if="section === 'users' && !detail">
        <KsButton data-test="create-user" :disabled="busy || readOnly || !capabilities.userDirectory" @click="navigate('users/new')">{{ t('create') }}</KsButton>
        <div class="table-wrap"><KsTable :data="users.items" :empty-text="t('noRows')" row-key="subject">
          <template #empty><p>{{ t('noRows') }}</p></template>
          <KsTableColumn prop="subject" :label="t('subject')" min-width="180" /><KsTableColumn prop="name" :label="t('name')" min-width="180" /><KsTableColumn prop="email" :label="t('email')" min-width="220" />
          <KsTableColumn :label="t('active')" min-width="100"><template #default="{row}">{{ t(row.active ? 'active' : 'inactive') }}</template></KsTableColumn>
          <KsTableColumn :label="t('actions')" min-width="120"><template #default="{row}"><KsButton @click="navigate('users/' + encodeSubject(row.subject))">{{ t('edit') }}</KsButton></template></KsTableColumn>
        </KsTable></div>
        <div class="buttons"><KsButton :disabled="busy || users.page <= 1" @click="perform(async () => users = await api.users(users.page - 1))">{{ t('previous') }}</KsButton><span>{{ users.page }} · {{ users.total }}</span><KsButton :disabled="busy || users.page * users.size >= users.total" @click="perform(async () => users = await api.users(users.page + 1))">{{ t('next') }}</KsButton></div>
      </template>
      <template v-else-if="section === 'users' && user">
        <KsButton @click="navigate('users')">{{ t('back') }}</KsButton>
        <form @submit.prevent="saveUser"><fieldset :disabled="busy || readOnly">
          <label>{{ t('subject') }}<KsInput v-if="detail === 'new'" v-model="user.username" name="user-subject" required autocomplete="off" /><output v-else>{{ user.subject }}</output></label>
          <label>{{ t('name') }}<KsInput v-model="user.name" name="user-name" required /></label><label>{{ t('email') }}<KsInput v-model="user.email" name="user-email" type="email" /></label>
          <label v-if="detail === 'new'">{{ t('password') }}<KsInput v-model="user.password" name="user-password" type="password" required autocomplete="new-password" /></label>
          <KsButton data-test="save-user" native-type="submit">{{ t('save') }}</KsButton>
        </fieldset></form>
        <template v-if="detail !== 'new'">
          <p>{{ t(user.active ? 'active' : 'inactive') }} · {{ t('revision') }}: {{ user.revision }}</p>
          <p v-if="protectedUser">{{ t('protected') }}</p>
          <KsButton :disabled="busy || readOnly || protectedUser" @click="perform(async () => { user = await api.updateState(user.subject, !user.active, user.revision); notice = t('saved') })">{{ t(user.active ? 'inactive' : 'active') }}</KsButton>
          <form @submit.prevent="perform(async () => { user = await api.resetPassword(user.subject, password, user.revision); password = ''; notice = t('saved') })"><fieldset :disabled="busy || readOnly || protectedUser"><label>{{ t('password') }}<KsInput v-model="password" type="password" required autocomplete="new-password" /></label><KsButton native-type="submit">{{ t('reset') }}</KsButton></fieldset></form>
        </template>
      </template>
      <template v-else-if="section === 'bindings'">
        <div class="table-wrap"><KsTable :data="bindings" :empty-text="t('noRows')">
          <template #empty><p>{{ t('noRows') }}</p></template>
          <KsTableColumn :label="t('subject') + ' / ' + t('trustedRole')" min-width="220"><template #default="{row}">{{ t(row.mapping ? 'trustedRole' : 'subject') }}: {{ row.key }}</template></KsTableColumn>
          <KsTableColumn :label="t('roleIds')" min-width="220"><template #default="{row}">{{ row.identifiers.join(', ') }}</template></KsTableColumn>
          <KsTableColumn :label="t('actions')" min-width="120"><template #default="{row}"><KsButton @click="chooseBinding(row.key, row.identifiers, row.mapping)">{{ t('edit') }}</KsButton></template></KsTableColumn>
        </KsTable></div>
        <form @submit.prevent="saveBinding"><fieldset :disabled="busy || readOnly">
          <label>{{ t('subject') }}<KsInput v-model="subject" name="binding-subject" :disabled="Boolean(trustedRole)" /></label><label>{{ t('trustedRole') }}<KsInput v-model="trustedRole" name="binding-role" :disabled="Boolean(subject)" /></label>
          <fieldset><legend>{{ t('roleIds') }}</legend><label v-for="item in roles" :key="item.id" class="checkbox"><input v-model="selected" name="binding-selection" type="checkbox" :value="item.id">{{ item.name }}</label></fieldset>
          <div class="buttons"><KsButton data-test="save-binding" native-type="submit" :disabled="!subject && !trustedRole">{{ t('save') }}</KsButton><KsButton native-type="button" @click="subject = ''; trustedRole = ''; selected = []">{{ t('cancel') }}</KsButton></div>
        </fieldset></form>
      </template>
    </template>
  </main>
</template>
