<script setup>
import {computed} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElConfigProvider, ElPagination} from 'element-plus'
import KsDataTable from '@kestra-io/design-system/components/Data/KsDataTable/KsDataTable'
import {nativeLocale} from './native-locale.js'

defineOptions({inheritAttrs: false})
const props = defineProps({data: {type: Array, required: true}, total: {type: Number, required: true}, currentPage: {type: Number, required: true}, pageSize: {type: Number, required: true}})
const emit = defineEmits(['update:currentPage', 'update:pageSize'])
const {locale} = useI18n()
const paginationLocale = computed(() => nativeLocale(locale.value))
</script>

<template>
  <div class="management-data-table">
    <KsDataTable v-bind="$attrs" :data="data" :total="0" :current-page="currentPage" :page-size="pageSize">
      <template #table><slot name="table" /></template>
    </KsDataTable>
    <ElConfigProvider namespace="kel" :locale="paginationLocale">
      <ElPagination :total="total" :current-page="currentPage" :page-size="pageSize" :page-sizes="[10, 25, 50, 100]" layout="sizes, prev, pager, next, total" size="small" data-test="list-total" @update:current-page="emit('update:currentPage', $event)" @update:page-size="emit('update:pageSize', $event); emit('update:currentPage', 1)" />
    </ElConfigProvider>
  </div>
</template>
