<script setup>
import {computed, ref, watch, inject, onMounted, onBeforeUnmount, useId} from 'vue'
import {useI18n} from 'vue-i18n'
import {useRoute, useRouter} from 'vue-router'
import {decodeSearchParams} from '@kestra-io/design-system'
import KsTable from '@kestra-io/design-system/components/Data/KsTable/KsTable'
import KsTableColumn from '@kestra-io/design-system/components/Data/KsTable/KsTableColumn'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import ManagementTable from './ManagementTable.vue'
import {ownAndFilter} from './and-filter-owner.js'
import {readListQuery, writeListQuery, clearListQuery, canonicalListPage, nativeChips, matchesChips, ordered, paginate} from './access-control-model.js'
const props = defineProps({items: {type: Array, required: true}, kind: {type: String, required: true}, columns: {type: Array, required: true}, scopes: {type: Array, default: () => []}, busy: Boolean, canManage: Boolean})
const emit = defineEmits(['refresh', 'create'])
const {t, locale} = useI18n()
const route = useRoute(), router = useRouter()
const search = ref('')
const area = ref(null), nativeError = ref(false), portal = inject('rbac-portals', null), descriptionId = 'and-filter-' + useId()
let owner
onMounted(() => {try {owner = ownAndFilter({area: area.value, portal, description: () => t('andFilterOnly'), descriptionId, savedFiltersShown: false, savedLabel: () => t('filter.my_filters_saved'), allLabel: () => t('filter.all_filters'), fail: () => {nativeError.value = true}})} catch {nativeError.value = true}})
onBeforeUnmount(() => owner?.dispose())
watch(locale, () => owner?.refresh(), {flush: 'post'})
const query = computed(() => {
  try {
    const state = readListQuery(route.query, props.kind)
    return {state, chips: [...state.chips, ...nativeChips(decodeSearchParams(route.query), props.kind)], error: nativeError.value}
  } catch {return {state: {page: 1, size: 25, chips: []}, chips: [], error: true}}
})
const configuration = computed(() => {
  const keys = props.kind === 'permissions' ? ['key', 'name', 'scopeKeys'] : props.kind === 'subjects' ? ['subject', 'scopeKeys'] : ['subject']
  return {searchPlaceholder: t('search'), keys: keys.map(field => ({key: field, label: field === 'key' ? t('permissionKey') : field === 'name' ? t('permissionName') : field === 'subject' ? 'subject' : t('scopeKeys'), valueType: field === 'name' ? 'text' : 'select', comparators: [field === 'name' ? '*=' : '='], groupable: true, searchable: true, valueProvider: field === 'name' ? undefined : async () => [...new Set(field === 'scopeKeys' ? props.scopes.map(scope => scope.key) : props.items.map(item => item[field]))].map(value => ({value, label: value}))}))}
})
const rows = computed(() => {
  if (query.value.error) return {items: [], total: 0, page: 1, size: query.value.state.size}
  const field = props.kind === 'permissions' ? 'key' : 'subject'
  const filtered = ordered(props.items.filter(item => matchesChips(item, query.value.chips) && (!search.value || Object.values(item).some(value => typeof value === 'string' && value.includes(search.value)))), field)
  return paginate(filtered, query.value.state.page, query.value.state.size)
})
function update(patch) {void router.replace({path: route.path, query: writeListQuery({...query.value.state, ...patch}, route.query)})}
function removeChip(index) {update({page: 1, chips: query.value.state.chips.filter((_, i) => i !== index)})}
function clear() {owner?.reset(); nativeError.value = false; void router.replace({path: route.path, query: clearListQuery(route.query, props.kind)})}
watch(() => [props.busy, query.value.error, rows.value.total, query.value.state.page, query.value.state.size], () => {
  if (props.busy || query.value.error) return
  const next = canonicalListPage(route.query, props.kind, rows.value.total)
  if (next) void router.replace({path: route.path, query: next})
}, {immediate: true})
watch(search, () => update({page: 1}))
watch(() => route.query, (next, previous) => {
  const strip = query => JSON.stringify(Object.fromEntries(Object.entries(query).filter(([key]) => !['page', 'size'].includes(key))))
  if (previous && strip(next) !== strip(previous) && next.page !== undefined) update({page: 1})
}, {deep: true})
</script>
<template>
  <div ref="area" class="rbac-list" tabindex="-1" data-focus-fallback :data-test="kind === 'permissions' ? 'application-permissions' : kind === 'users' ? 'user-list' : 'subject-list'">
    <KsFilter v-if="!nativeError" :configuration="configuration" :default-scope="false" :default-time-range="false" :buttons="{savedFilters: {shown: false}, tableOptions: {shown: false}}" :table-options="{refresh: {shown: true}, columns: {shown: false}}" @search="search = $event" @refresh="emit('refresh')">
      <template #extra><KsButton v-if="canManage" :disabled="busy" :data-test="kind === 'permissions' ? 'permission-add' : kind === 'users' ? 'user-create' : 'subject-add'" @click="emit('create')">＋ {{ t('create') }}</KsButton></template>
    </KsFilter>
    <div v-if="query.state.chips.length" class="rbac-tags"><span v-for="(chip, index) in query.state.chips" :key="index" class="rbac-tag" data-test="filter-chip"><span v-if="chip.field === 'scopeKeys'">scope ∈ {{ chip.values.join(' · ') }}</span><span v-else>{{ chip.field }} = {{ chip.value }}</span><KsButton :disabled="busy" :aria-label="t('close')" @click="removeChip(index)">×</KsButton></span><KsButton @click="clear">{{ t('clearFilters') }}</KsButton></div>
    <p v-if="query.error" role="alert" class="message error" data-test="request-error">{{ t(nativeError ? 'andFilterUnavailable' : 'invalidQuery') }} <KsButton @click="clear">{{ t('clearFilters') }}</KsButton></p>
    <div v-else class="table-wrap"><ManagementTable :data="rows.items" :total="rows.total" :current-page="rows.page" :page-size="rows.size" @update:current-page="update({page: $event})" @update:page-size="update({size: $event, page: 1})">
      <template #table><KsTable :data="rows.items" :row-key="kind === 'permissions' ? 'key' : 'subject'" :empty-text="t('noRows')">
        <KsTableColumn v-for="column in columns" :key="column.key" :label="column.label" :min-width="column.width ?? 160"><template #default="{row}"><slot :name="column.key" :row="row"><span>{{ row[column.key] ?? '—' }}</span></slot></template></KsTableColumn>
      </KsTable></template>
    </ManagementTable></div>
  </div>
</template>
