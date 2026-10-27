<script setup>
import {useI18n} from 'vue-i18n'
import {inject, nextTick, onBeforeUnmount, watch} from 'vue'
import {deepestActiveElement, restoreFocus} from './focus.js'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
const props = defineProps({open: Boolean, busy: Boolean, blocked: Boolean, discard: Boolean, error: String, label: String, references: {type: Array, default: () => []}})
const emit = defineEmits(['cancel', 'confirm'])
const {t} = useI18n()
const portals = inject('rbac-portals')
let trigger, focusRoot, disposed = false
watch(() => props.open, async open => {
  if (open) {trigger = deepestActiveElement(document); focusRoot = portals?.getRootNode()}
  else {await nextTick(); if (disposed || props.open) return; restoreFocus(trigger, focusRoot); trigger = null; focusRoot = null}
})
onBeforeUnmount(() => {disposed = true; trigger = null; focusRoot = null})
</script>
<template>
  <KsDialog :model-value="open" :append-to="portals" :append-to-body="false" :title="t(discard ? 'discardTitle' : 'confirm')" :show-close="false" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :before-close="done => {if (!busy) {emit('cancel'); done()}}" width="min(480px, calc(100vw - 32px))" :data-test="discard ? 'discard-confirm' : 'delete-confirm'" @update:model-value="value => {if (!value && !busy) emit('cancel')}">
    <p><code>{{ label }}</code></p><p v-if="blocked">{{ t('removeReferencesFirst') }}</p>
    <ul v-if="references.length"><li v-for="(reference, index) in references" :key="index"><code>{{ reference }}</code></li></ul>
    <p v-if="error" role="alert" class="error" data-test="request-error">{{ error }}</p>
    <template #footer><KsButton :disabled="busy" @click="emit('cancel')">{{ t('cancel') }}</KsButton><KsButton v-if="!blocked" type="danger" :disabled="busy" @click="emit('confirm')">{{ t(busy ? 'loading' : discard ? 'discard' : 'delete') }}</KsButton></template>
  </KsDialog>
</template>
