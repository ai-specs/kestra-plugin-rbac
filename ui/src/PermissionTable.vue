<script setup>
import {computed, ref} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import AccessList from './AccessList.vue'
import {permissionSummary} from './access-control-model.js'
const props = defineProps({items: {type: Array, required: true}, scopes: {type: Array, required: true}, groupId: String, implementationKind: String, busy: Boolean, canManage: Boolean})
const emit = defineEmits(['create', 'edit', 'delete', 'scope', 'refresh'])
const {t} = useI18n()
const expanded = ref(new Set())
const columns = computed(() => [{key: 'key', label: t('permissionKey')}, {key: 'name', label: t('permissionName')}, {key: 'scopeKeys', label: t('activationConditions'), width: 200}, {key: 'details', label: t('permissionDetails'), width: 240}, {key: 'operations', label: '', width: 100}])
</script>
<template>
  <section aria-labelledby="permission-table-title" data-test="application-permissions">
    <h2 id="permission-table-title">{{ t('runtimePermissions') }}</h2><p class="muted">{{ t(implementationKind === 'kestra-policy' ? 'kestraActualPermissions' : 'externalDeclaration') }}</p>
    <AccessList :items="items" kind="permissions" :columns="columns" :scopes="scopes" :busy="busy" :can-manage="canManage" @create="emit('create')" @refresh="emit('refresh')">
      <template #key="{row}"><code data-test="permission-row" :data-permission-key="row.key">{{ row.key }}</code></template>
      <template #scopeKeys="{row}"><div class="rbac-tags"><KsButton v-for="key in row.scopeKeys" :key="key" link :disabled="busy" @click="emit('scope', key)"><code>{{ groupId }}: {{ key }}</code></KsButton><span v-if="!row.scopeKeys.length">—</span></div></template>
      <template #details="{row}"><div class="rbac-permission-summary"><p v-if="!permissionSummary(row).length">—</p><template v-else-if="expanded.has(row.key)"><p v-for="(line, index) in permissionSummary(row)" :key="index">{{ line }}</p></template><template v-else><span>{{ permissionSummary(row)[0] }}</span><KsButton v-if="permissionSummary(row).length > 1" link :aria-label="t('permissionDetails')" @click="expanded.add(row.key)">+{{ permissionSummary(row).length - 1 }}</KsButton></template></div></template>
      <template #operations="{row}"><div v-if="canManage" class="buttons"><KsButton :disabled="busy" :aria-label="t('edit')" :title="t('edit')" data-test="permission-edit" @click="emit('edit', row)">✎</KsButton><KsButton :disabled="busy" :aria-label="t('delete')" :title="t('delete')" data-test="permission-delete" @click="emit('delete', row)"><svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M9 3h6l1 2h4v2H4V5h4zm-3 6h12l-1 12H7zm3 2v8h2v-8zm4 0v8h2v-8z" /></svg></KsButton></div></template>
    </AccessList>
  </section>
</template>
