<script setup>
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {useRoute, useRouter} from 'vue-router'
import {decodeSearchParams} from '@kestra-io/design-system'
import KsFilter from '@kestra-io/design-system/components/Data/KsDataTable/KsFilter'
import KsTable from '@kestra-io/design-system/components/Data/KsTable/KsTable'
import KsTableColumn from '@kestra-io/design-system/components/Data/KsTable/KsTableColumn'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import ManagementTable from './ManagementTable.vue'
import {matchesQuery} from './list-filters.js'
const props = defineProps({items: {type: Array, required: true}, columns: {type: Array, required: true}, storageKey: {type: String, required: true}, busy: Boolean})
const emit = defineEmits(['open', 'refresh'])
const {t} = useI18n()
const route = useRoute()
const router = useRouter()
const search = ref('')
const page = ref(1)
const size = ref(25)
let restoring = true
const configuration = computed(() => ({searchPlaceholder: t('search'), keys: props.columns.map(field => ({key: field, label: t(field), valueType: 'text', comparators: ['*=', '='], searchable: true}))}))
const text = (item, field) => typeof item[field] === 'boolean' ? t(item[field] ? 'active' : 'inactive') : Array.isArray(item[field]) ? item[field].join(', ') : String(item[field] ?? '')
const filtered = computed(() => props.items.filter(item => props.columns.some(field => text(item, field).toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())) && matchesQuery(item, decodeSearchParams(route.query), text)))
const rows = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))
watch([search, () => route.query, size], () => {page.value = 1}, {deep: true})
watch(() => props.items, () => {page.value = 1})
watch(() => props.storageKey, async key => {
  restoring = true
  search.value = ''
  let stored = {}
  try { stored = JSON.parse(sessionStorage.getItem(key) ?? '{}') } catch {}
  if (!stored || typeof stored !== 'object' || Array.isArray(stored)) stored = {}
  await router.replace({path: route.path, query: stored})
  restoring = false
}, {immediate: true})
watch(() => route.query, query => {if (!restoring) {try {sessionStorage.setItem(props.storageKey, JSON.stringify(query))} catch {}}}, {deep: true})
</script>
<template>
  <KsFilter :configuration="configuration" :default-scope="false" :default-time-range="false" :buttons="{savedFilters: {shown: false}, tableOptions: {shown: false}}" :table-options="{refresh: {shown: true}, columns: {shown: false}}" @search="search = $event" @filter="page = 1" @refresh="emit('refresh')" />
  <div class="table-wrap">
    <ManagementTable :data="rows" :total="filtered.length" v-model:current-page="page" v-model:page-size="size" :no-data-text="t('noRows')">
      <template #table><KsTable :data="rows" :empty-text="t('noRows')">
        <template #empty><p>{{ t('noRows') }}</p></template>
        <KsTableColumn v-for="(field, index) in columns" :key="field" :label="t(field)" min-width="160"><template #default="{row}"><KsButton v-if="index === 0" link type="primary" :disabled="busy" @click="emit('open', row)">{{ text(row, field) }}</KsButton><template v-else>{{ text(row, field) }}</template></template></KsTableColumn>
      </KsTable></template>
    </ManagementTable>
  </div>
</template>
