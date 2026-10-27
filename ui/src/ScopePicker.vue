<script setup>
import {useI18n} from 'vue-i18n'
import {computed} from 'vue'
import {availableScopeOptions} from './access-control-model.js'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
const props = defineProps({modelValue: {type: Array, required: true}, scopes: {type: Array, required: true}, disabled: Boolean, knownKeysOnly: Boolean})
const emit = defineEmits(['update:modelValue'])
const {t} = useI18n()
const options = computed(() => availableScopeOptions(props.scopes))
const knownKeys = value => String(value ?? '').split(/\r?\n/).map(key => key.trim()).filter(Boolean)
</script>
<template>
  <div data-test="scope-picker">
  <template v-if="knownKeysOnly"><KsInput type="textarea" :model-value="modelValue.join('\n')" :disabled="disabled" :aria-label="t('scopeKeys')" data-test="scope-known-keys" @update:model-value="emit('update:modelValue', knownKeys($event))" /><small>{{ t('onePerLine') }}</small></template>
  <KsSelect v-else :model-value="modelValue" multiple filterable :disabled="disabled" :placeholder="t('scopeKeys')" data-test="scope-select" @update:model-value="emit('update:modelValue', $event)">
    <KsOption v-for="scope in options" :key="scope.key" :value="scope.key" :label="scope.key"><code>{{ scope.key }}</code><span class="muted"> · {{ scope.name }}</span></KsOption>
  </KsSelect>
  <div class="rbac-tags" aria-live="polite"><code v-for="key in modelValue" :key="key" class="rbac-tag">{{ key }}</code></div>
  </div>
</template>
