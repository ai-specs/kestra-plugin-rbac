<script setup>
import {computed} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElConfigProvider, ElPagination} from 'element-plus'
import KsDataTable from '@kestra-io/design-system/components/Data/KsDataTable/KsDataTable'
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

defineOptions({inheritAttrs: false})
const props = defineProps({data: {type: Array, required: true}, total: {type: Number, required: true}, currentPage: {type: Number, required: true}, pageSize: {type: Number, required: true}})
const emit = defineEmits(['update:currentPage', 'update:pageSize'])
const {locale} = useI18n()
const locales = {en, de, es, fr, hi, it, ja, ko, pl, pt, pt_BR: ptBR, ru, zh_CN: zhCN}
const paginationLocale = computed(() => locales[locale.value] ?? en)
</script>

<template>
  <div class="management-data-table">
    <KsDataTable v-bind="$attrs" :data="data" :total="0" :current-page="currentPage" :page-size="pageSize">
      <template #table><slot name="table" /></template>
    </KsDataTable>
    <ElConfigProvider namespace="kel" :locale="paginationLocale">
      <ElPagination v-if="total > 10" :total="total" :current-page="currentPage" :page-size="pageSize" :page-sizes="[10, 20, 25, 50, 100]" layout="sizes, prev, pager, next, total" size="small" @update:current-page="emit('update:currentPage', $event)" @update:page-size="emit('update:pageSize', $event); emit('update:currentPage', 1)" />
    </ElConfigProvider>
  </div>
</template>
