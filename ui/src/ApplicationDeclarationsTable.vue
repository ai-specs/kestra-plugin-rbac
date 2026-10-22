<script setup>
import {computed, reactive, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import ManagementTable from './ManagementTable.vue'
import KsTable from '@kestra-io/design-system/components/Data/KsTable/KsTable'
import KsTableColumn from '@kestra-io/design-system/components/Data/KsTable/KsTableColumn'
import {availableDeclarationScopes} from './application-group-model.js'

const props = defineProps({applications: {type: Array, required: true}, scopes: {type: Array, required: true}, busy: Boolean, disabled: Boolean})
const emit = defineEmits(['create', 'update', 'delete'])
const {t} = useI18n()

const rows = computed(() => props.applications.flatMap(application => (application.declarations ?? []).flatMap(declaration =>
  declaration.permissions.map(permission => ({
    clientId: application.clientId,
    scopeKey: declaration.scopeKey,
    roleName: props.scopes.find(scope => scope.key === declaration.scopeKey)?.name ?? '',
    permissionKey: permission.key,
    permissionName: permission.name,
    revision: declaration.revision,
  })))))

const selectedApplication = ref('')
const selectedScope = ref('')
const permissionKey = ref('')
const permissionName = ref('')
const page = ref(1)
const size = ref(25)
const edits = reactive({})

watch(() => props.applications, () => {selectedApplication.value = '';selectedScope.value = '';permissionKey.value = '';permissionName.value = '';for (const key of Object.keys(edits)) delete edits[key]}, {deep: true})

const scopeName = key => props.scopes.find(scope => scope.key === key)?.name ?? key
const availableScopes = computed(() => {
  const application = props.applications.find(item => item.clientId === selectedApplication.value)
  if (!application) return []
  return availableDeclarationScopes(props.scopes, application.supportedScopeKeys ?? [], application.declarations ?? [])
})

function create() {
  emit('create', selectedApplication.value, selectedScope.value, [{key: permissionKey.value, name: permissionName.value}])
  selectedScope.value = permissionKey.value = permissionName.value = ''
}

function permissionsWithout(row, removedKey) {
  const application = props.applications.find(item => item.clientId === row.clientId)
  const declaration = (application?.declarations ?? []).find(item => item.scopeKey === row.scopeKey)
  return (declaration?.permissions ?? []).filter(permission => permission.key !== removedKey)
}

function rename(row) {
  const application = props.applications.find(item => item.clientId === row.clientId)
  const declaration = (application?.declarations ?? []).find(item => item.scopeKey === row.scopeKey)
  const permissions = (declaration?.permissions ?? []).map(permission => permission.key === row.permissionKey ? {...permission, name: edits[rowKey(row)]} : permission)
  emit('update', row.clientId, row.scopeKey, permissions, row.revision)
  delete edits[rowKey(row)]
}

function remove(row) {
  const remaining = permissionsWithout(row, row.permissionKey)
  if (remaining.length) emit('update', row.clientId, row.scopeKey, remaining, row.revision)
  else emit('delete', row.clientId, row.scopeKey, row.revision)
}

const rowKey = row => row.clientId + '\n' + row.scopeKey + '\n' + row.permissionKey
const editValue = row => edits[rowKey(row)] ?? row.permissionName
</script>

<template>
  <section class="application-declarations-table" data-test="application-declarations-table">
    <form class="application-declaration-create" @submit.prevent="create">
      <label>{{ t('clients') }}<select v-model="selectedApplication" required :disabled="disabled" data-test="declaration-application">
        <option value="" />
        <option v-for="application in applications" :key="application.clientId" :value="application.clientId">{{ application.clientId }}</option>
      </select></label>
      <label>{{ t('scope') }}<select v-model="selectedScope" required :disabled="disabled || !selectedApplication" data-test="declaration-scope">
        <option value="" />
        <option v-for="key in availableScopes" :key="key" :value="key">{{ scopeName(key) }} ({{ key }})</option>
      </select></label>
      <label>{{ t('permissionKey') }}<KsInput v-model="permissionKey" :disabled="disabled" required name="declaration-permission-key" /></label>
      <label>{{ t('permissionName') }}<KsInput v-model="permissionName" :disabled="disabled" required name="declaration-permission-name" /></label>
      <KsButton type="primary" native-type="submit" :disabled="disabled || busy || !selectedApplication || !selectedScope || !permissionKey || !permissionName" data-test="create-declaration">{{ t('create') }}</KsButton>
    </form>
    <div class="table-wrap">
      <ManagementTable :data="rows" :total="rows.length" v-model:current-page="page" v-model:page-size="size" :no-data-text="t('noRows')">
        <template #table><KsTable :data="rows" :empty-text="t('noRows')">
          <template #empty><p>{{ t('noRows') }}</p></template>
          <KsTableColumn :label="t('clients')" min-width="140"><template #default="{row}"><code>{{ row.clientId }}</code></template></KsTableColumn>
          <KsTableColumn :label="t('scope')" min-width="180"><template #default="{row}">{{ row.roleName }} ({{ row.scopeKey }})</template></KsTableColumn>
          <KsTableColumn :label="t('permissionKey')" min-width="160"><template #default="{row}"><code>{{ row.permissionKey }}</code></template></KsTableColumn>
          <KsTableColumn :label="t('permissionName')" min-width="220"><template #default="{row}">
            <input v-if="rowKey(row) in edits" v-model="edits[rowKey(row)]" class="declaration-name-edit" data-test="declaration-name-edit">
            <template v-else>{{ row.permissionName }}</template>
          </template></KsTableColumn>
          <KsTableColumn :label="t('edit')" min-width="200"><template #default="{row}">
            <template v-if="rowKey(row) in edits">
              <KsButton :disabled="disabled || busy" data-test="save-declaration-permission" @click="rename(row)">{{ t('save') }}</KsButton>
              <KsButton :disabled="disabled || busy" @click="delete edits[rowKey(row)]">{{ t('cancel') }}</KsButton>
            </template>
            <template v-else>
              <KsButton v-if="!disabled" :disabled="busy" data-test="edit-declaration-permission" @click="edits[rowKey(row)] = row.permissionName">{{ t('edit') }}</KsButton>
              <KsButton v-if="!disabled" :disabled="busy" data-test="delete-declaration-permission" @click="remove(row)">{{ t('delete') }}</KsButton>
            </template>
          </template></KsTableColumn>
        </KsTable></template>
      </ManagementTable>
    </div>
  </section>
</template>
