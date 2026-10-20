<script setup>
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {useRoute, useRouter} from 'vue-router'
import {decodeSearchParams} from '@kestra-io/design-system'
import KsFilter from '@kestra-io/design-system/components/Data/KsDataTable/KsFilter'
import KsCard from '@kestra-io/design-system/components/Data/KsCard'
import {ElConfigProvider, ElPagination} from 'element-plus'
import en from 'element-plus/es/locale/lang/en'
import de from 'element-plus/es/locale/lang/de'
import es from 'element-plus/es/locale/lang/es'
import fr from 'element-plus/es/locale/lang/fr'
import hi from 'element-plus/es/locale/lang/hi'
import it from 'element-plus/es/locale/lang/it'
import ja from 'element-plus/es/locale/lang/ja'
import ko from 'element-plus/es/locale/lang/ko'
import pl from 'element-plus/es/locale/lang/pl'
import pt from 'element-plus/es/locale/lang/pt'
import ptBR from 'element-plus/es/locale/lang/pt-br'
import ru from 'element-plus/es/locale/lang/ru'
import zhCN from 'element-plus/es/locale/lang/zh-cn'
import {matchesQuery} from './list-filters.js'

const props = defineProps({items: {type: Array, required: true}, busy: Boolean, storageKey: {type: String, required: true}})
const emit = defineEmits(['open', 'refresh'])
const {t, locale} = useI18n()
const route = useRoute()
const router = useRouter()
const search = ref('')
const page = ref(1)
const size = ref(25)
let restoring = true
const locales = {en, de, es, fr, hi, it, ja, ko, pl, pt, pt_BR: ptBR, ru, zh_CN: zhCN}
const fields = ['id', 'displayName', 'active']
const configuration = computed(() => ({searchPlaceholder: t('search'), keys: fields.map(field => ({key: field, label: t(field), valueType: 'text', comparators: ['*=', '='], searchable: true}))}))
const text = (item, field) => typeof item[field] === 'boolean' ? t(item[field] ? 'active' : 'inactive') : String(item[field] ?? '')
const filtered = computed(() => props.items.filter(item => fields.some(field => text(item, field).toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())) && matchesQuery(item, decodeSearchParams(route.query), text)))
const cards = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))
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
  <div>
    <KsFilter :configuration="configuration" :default-scope="false" :default-time-range="false" :buttons="{savedFilters: {shown: false}, tableOptions: {shown: false}}" :table-options="{refresh: {shown: true}, columns: {shown: false}}" @search="search = $event" @filter="page = 1" @refresh="emit('refresh')" />
    <div class="application-group-cards" data-test="application-group-cards">
      <KsCard v-for="item in cards" :key="item.id" shadow="hover">
        <button class="application-group-card-link" type="button" :disabled="busy" @click="emit('open', item)">
          <span class="application-group-card-title">{{ item.displayName || item.id }}</span>
          <span class="application-group-card-id">{{ item.id }}</span>
        </button>
      </KsCard>
      <p v-if="!cards.length">{{ t('noRows') }}</p>
    </div>
    <ElConfigProvider namespace="kel" :locale="locales[locale] ?? en">
      <ElPagination v-if="filtered.length > 10" :total="filtered.length" :current-page="page" :page-size="size" :page-sizes="[10, 20, 25, 50, 100]" layout="sizes, prev, pager, next, total" size="small" @update:current-page="page = $event" @update:page-size="size = $event; page = 1" />
    </ElConfigProvider>
  </div>
</template>
