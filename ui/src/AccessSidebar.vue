<script setup>
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import {ordered, paginate} from './access-control-model.js'
const props = defineProps({items: {type: Array, required: true}, field: {type: String, default: 'key'}, selected: String, title: String, disabled: Boolean, canManage: Boolean, kind: String})
const emit = defineEmits(['select', 'create', 'delete'])
const {t} = useI18n()
const search = ref(''), page = ref(1)
const filtered = computed(() => ordered(props.items.filter(item => item[props.field].includes(search.value)), props.field))
const result = computed(() => paginate(filtered.value, page.value))
// Synchronize navigation inputs, not renders or manual page changes. Search owns its reset.
watch([() => props.selected, () => props.field, () => props.items.map(item => item[props.field])], () => {
  const index = filtered.value.findIndex(item => item[props.field] === props.selected)
  page.value = index < 0 ? paginate(filtered.value, page.value).page : Math.floor(index / 25) + 1
}, {immediate: true})
</script>
<template>
  <aside class="rbac-sidebar" :data-test="`${kind}-sidebar`">
    <header><h2 tabindex="-1" data-focus-fallback>{{ title }}</h2><KsButton v-if="canManage" :disabled="disabled" :aria-label="t('create')" :data-test="kind === 'scope' ? 'scope-add' : 'application-register'" @click="emit('create')">＋</KsButton></header>
    <KsInput v-model="search" :placeholder="t('search')" :aria-label="t('search')" @update:model-value="page = 1" />
    <ul><li v-for="item in result.items" :key="item[field]" :class="{selected: selected === item[field]}">
      <KsButton link :disabled="disabled" :aria-current="selected === item[field] ? 'true' : undefined" :data-test="`${kind}-item`" :data-scope-key="kind === 'scope' ? item[field] : undefined" :data-client-key="kind === 'application' ? item[field] : undefined" @click="emit('select', item)"><code>{{ item[field] }}</code></KsButton>
      <KsButton v-if="canManage" class="sidebar-delete" :disabled="disabled" :aria-label="`${t('delete')} ${item[field]}`" :title="t('delete')" :data-test="`${kind}-delete`" @click="emit('delete', item)"><svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M9 3h6l1 2h4v2H4V5h4zm-3 6h12l-1 12H7zm3 2v8h2v-8zm4 0v8h2v-8z" /></svg></KsButton>
    </li></ul>
    <p v-if="!result.total">{{ t('noRows') }}</p>
    <footer v-if="result.total > 25" class="buttons"><KsButton :disabled="result.page === 1" :aria-label="t('previous')" @click="page = result.page - 1">‹</KsButton><span>{{ result.page }} / {{ Math.ceil(result.total / 25) }}</span><KsButton :disabled="result.page * 25 >= result.total" :aria-label="t('next')" @click="page = result.page + 1">›</KsButton></footer>
  </aside>
</template>
