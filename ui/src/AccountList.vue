<script setup>
import {computed} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import AccessList from './AccessList.vue'
defineProps({items: {type:Array, required:true}, busy:Boolean, canManage:Boolean})
const emit=defineEmits(['create','edit','delete','state','refresh'])
const {t}=useI18n()
const columns=computed(()=>[{key:'subject',label:'subject'},{key:'name',label:t('name')},{key:'email',label:t('email')},{key:'active',label:t('active')},{key:'operations',label:'',width:150}])
</script>
<template>
  <AccessList :items="items" kind="users" :columns="columns" :busy="busy" :can-manage="canManage" @create="emit('create')" @refresh="emit('refresh')">
    <template #subject="{row}"><code data-test="user-row" :data-subject="row.subject">{{ row.subject }}</code></template>
    <template #active="{row}">{{ t(row.active ? 'active' : 'inactive') }}</template>
    <template #operations="{row}"><div v-if="canManage" class="buttons"><KsButton :disabled="busy" :aria-label="t('edit')" @click="emit('edit',row)">✎</KsButton><KsButton :disabled="busy" :aria-label="t(row.active ? 'inactive' : 'active')" @click="emit('state',row)">{{ row.active ? '⊘' : '✓' }}</KsButton><KsButton :disabled="busy" :aria-label="t('delete')" data-test="user-delete" @click="emit('delete',row)"><svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M9 3h6l1 2h4v2H4V5h4zm-3 6h12l-1 12H7zm3 2v8h2v-8zm4 0v8h2v-8z" /></svg></KsButton></div></template>
  </AccessList>
</template>
