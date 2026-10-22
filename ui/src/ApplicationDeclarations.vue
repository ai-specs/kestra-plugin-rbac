<script setup>
import {computed, reactive, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import {availableDeclarationScopes} from './application-group-model.js'

const props = defineProps({items: {type: Array, required: true}, scopes: {type: Array, required: true},
  supported: {type: Array, required: true}, disabled: Boolean})
const emit = defineEmits(['create', 'update', 'delete'])
const {t} = useI18n()
const selectedScope = ref('')
const permissionKey = ref('')
const permissionName = ref('')
const drafts = reactive({})
const additional = reactive({})
const deleting = ref('')
const available = computed(() => availableDeclarationScopes(props.scopes,props.supported,props.items))

watch(() => props.items, items => {
  for (const key of Object.keys(drafts)) delete drafts[key]
  for (const item of items) {
    drafts[item.scopeKey] = item.permissions.map(permission => ({...permission}))
    additional[item.scopeKey] = {key: '', name: ''}
  }
}, {immediate: true})

function create() {
  emit('create', selectedScope.value, [{key: permissionKey.value, name: permissionName.value}])
  selectedScope.value = permissionKey.value = permissionName.value = ''
}

function add(scope) {
  if (!additional[scope]?.key || !additional[scope]?.name) return
  drafts[scope].push({...additional[scope]})
  additional[scope] = {key: '', name: ''}
}

function removePermission(scope, key) {
  drafts[scope] = drafts[scope].filter(permission => permission.key !== key)
}
</script>

<template>
  <section class="application-declarations" data-test="application-declarations">
    <h2>{{ t('applicationPermissions') }}</h2>
    <p v-if="!items.length" class="muted">{{ t('noRows') }}</p>
    <section v-for="item in items" :key="item.scopeKey" class="application-declaration" :data-scope="item.scopeKey">
      <h3>{{ scopes.find(scope => scope.key === item.scopeKey)?.name || item.scopeKey }} ({{ item.scopeKey }})</h3>
      <div v-for="permission in drafts[item.scopeKey] || []" :key="permission.key" class="application-permission-row">
        <code>{{ permission.key }}</code>
        <label>{{ t('permissionName') }}<KsInput v-model="permission.name" :disabled="disabled" required /></label>
        <KsButton :disabled="disabled" @click="removePermission(item.scopeKey, permission.key)">{{ t('delete') }}</KsButton>
      </div>
      <div class="application-permission-row">
        <label>{{ t('permissionKey') }}<KsInput v-model="additional[item.scopeKey].key" :disabled="disabled" /></label>
        <label>{{ t('permissionName') }}<KsInput v-model="additional[item.scopeKey].name" :disabled="disabled" /></label>
        <KsButton :disabled="disabled || !additional[item.scopeKey]?.key || !additional[item.scopeKey]?.name" @click="add(item.scopeKey)">{{ t('create') }}</KsButton>
      </div>
      <div class="buttons">
        <KsButton type="primary" :disabled="disabled || !drafts[item.scopeKey]?.length" @click="emit('update', item.scopeKey, drafts[item.scopeKey], item.revision)">{{ t('save') }}</KsButton>
        <KsButton :disabled="disabled" @click="deleting = item.scopeKey">{{ t('delete') }}</KsButton>
      </div>
      <div v-if="deleting === item.scopeKey" class="message" role="alertdialog" :aria-label="t('confirm')">
        <p>{{ t('confirm') }}</p>
        <KsButton :disabled="disabled" @click="emit('delete', item.scopeKey, item.revision); deleting = ''">{{ t('delete') }}</KsButton>
        <KsButton @click="deleting = ''">{{ t('cancel') }}</KsButton>
      </div>
    </section>
    <form v-if="available.length" @submit.prevent="create">
      <fieldset :disabled="disabled">
        <h3>{{ t('registerScope') }}</h3>
        <label>{{ t('scope') }}<select v-model="selectedScope" required><option disabled value="">{{ t('scope') }}</option><option v-for="scope in available" :key="scope.key" :value="scope.key">{{ scope.name }} ({{ scope.key }})</option></select></label>
        <label>{{ t('permissionKey') }}<KsInput v-model="permissionKey" required /></label>
        <label>{{ t('permissionName') }}<KsInput v-model="permissionName" required /></label>
        <KsButton type="primary" native-type="submit">{{ t('create') }}</KsButton>
      </fieldset>
    </form>
  </section>
</template>
