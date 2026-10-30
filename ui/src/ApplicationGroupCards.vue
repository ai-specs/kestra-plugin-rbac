<script setup>
import {computed, ref, watch, inject, onMounted, onBeforeUnmount, useId} from 'vue'
import {useI18n} from 'vue-i18n'
import {useRoute, useRouter} from 'vue-router'
import {decodeSearchParams} from '@kestra-io/design-system'
import KsCard from '@kestra-io/design-system/components/Data/KsCard'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import {ElConfigProvider, ElPagination} from 'element-plus'
import {nativeLocale} from './native-locale.js'
import {matchesQuery} from './list-filters.js'
import {ownAndFilter} from './and-filter-owner.js'
import {countValue, ordered} from './access-control-model.js'

const props = defineProps({items: {type: Array, required: true}, busy: Boolean, storageKey: {type: String, required: true}})
const emit = defineEmits(['open', 'refresh', 'section'])
const {t, locale} = useI18n()
const route = useRoute(), router = useRouter()
const area = ref(null), nativeError = ref(false), portal = inject('rbac-portals', null), descriptionId = 'and-filter-' + useId()
let owner
onMounted(() => {try {owner = ownAndFilter({area: area.value, portal, description: () => t('andFilterOnly'), descriptionId, savedFiltersShown: false, savedLabel: () => t('filter.my_filters_saved'), allLabel: () => t('filter.all_filters'), fail: () => {nativeError.value = true}})} catch {nativeError.value = true}})
onBeforeUnmount(() => owner?.dispose())
watch(locale, () => owner?.refresh(), {flush: 'post'})
const decoded = computed(() => {try {const values = decodeSearchParams(route.query); if (values.some(value => value.topLogical === 'OR' || value.wrapperLogical === 'OR' || value.field !== 'q' && (!fields.includes(value.field) || !['EQUALS','CONTAINS'].includes(value.operation)))) return null; return values} catch {return null}})
const clear = () => {owner?.reset(); nativeError.value = false; void router.replace({path: route.path, query: {}})}
const search = ref('')
const page = ref(1)
const size = ref(25)
const fields = ['id', 'displayName', 'active']
const configuration = computed(() => ({searchPlaceholder: t('search'), keys: fields.map(field => ({key: field, label: t(field), valueType: 'text', comparators: ['*=', '='], searchable: true}))}))
const text = (item, field) => typeof item[field] === 'boolean' ? t(item[field] ? 'active' : 'inactive') : String(item[field] ?? '')
const filtered = computed(() => nativeError.value || decoded.value === null ? [] : props.items.filter(item => fields.some(field => text(item, field).toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())) && matchesQuery(item, decoded.value, text)))
const cards = computed(() => ordered(filtered.value, 'id').slice((page.value - 1) * size.value, page.value * size.value))
watch([search, () => route.query, size], () => {page.value = 1}, {deep: true})
watch(() => props.items, () => {page.value = 1})
</script>

<template>
  <div ref="area">
    <KsFilter v-if="!nativeError" :configuration="configuration" :default-scope="false" :default-time-range="false" :buttons="{savedFilters: {shown: false}, tableOptions: {shown: false}}" :table-options="{refresh: {shown: true}, columns: {shown: false}}" @search="search = $event" @filter="page = 1" @refresh="emit('refresh')" />
    <p v-if="nativeError || decoded === null" role="alert" data-test="request-error">{{ t(nativeError ? 'andFilterUnavailable' : 'invalidQuery') }} <KsButton @click="clear">{{ t('clearFilters') }}</KsButton></p>
    <template v-if="!nativeError && decoded !== null">
    <div class="application-group-cards" data-test="application-group-cards">
      <KsCard v-for="item in cards" :key="item.id" shadow="hover" data-test="group-card" :data-group-id="item.id">
        <KsButton link class="application-group-card-link" :disabled="busy" @click="emit('open', item)">
          <span class="rbac-card-heading">
            <code class="application-group-card-title">{{ item.id }}</code>
            <span class="application-group-card-id">{{ item.displayName }}</span>
            <span v-if="item.builtIn" class="builtin-indicator" data-test="builtin-group-card-indicator">{{ t('builtinGroup') }}</span>
          </span>
        </KsButton>
        <div class="rbac-card-counts">
          <KsButton v-for="section in [{field:'scopes', tab:'scopes', label:'scopes'}, {field:'applications', tab:'applications', label:'clients'}, {field:'subjects', tab:'scope-grants', label:'subject'}]" :key="section.field" link :disabled="busy || countValue(item.counts, section.field) === null" :data-test="`group-count-${section.field}`" @click="emit('section', item, section.tab)"><span class="rbac-card-count-label"><b>{{ countValue(item.counts, section.field) ?? '—' }}</b> {{ section.field === 'subjects' ? 'subject' : t(section.label) }}</span></KsButton>
        </div>
      </KsCard>
      <p v-if="!cards.length">{{ t('noRows') }}</p>
    </div>
    <ElConfigProvider namespace="kel" :locale="nativeLocale(locale)">
      <ElPagination :total="filtered.length" :current-page="page" :page-size="size" :page-sizes="[10, 25, 50, 100]" layout="sizes, prev, pager, next, total" size="small" @update:current-page="page = $event" @update:page-size="size = $event; page = 1" />
    </ElConfigProvider>
    </template>
  </div>
</template>
