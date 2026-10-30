<script setup>
import {computed, inject, nextTick, onBeforeUnmount, useId, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsForm from '@kestra-io/design-system/components/Form/KsForm/KsForm'
import ContentSave from 'vue-material-design-icons/ContentSave.vue'
import {requestEditorClose, cancelDiscard, confirmDiscard} from './editor-state.js'
import {deepestActiveElement, restoreFocus} from './focus.js'

const props = defineProps({state: {type: Object, required: true}, title: {type: String, required: true}, disabled: Boolean, conflicted: Boolean})
const emit = defineEmits(['save', 'closed', 'refresh'])
const {t} = useI18n()
const formId = 'rbac-editor-' + useId()
const portals = inject('rbac-portals')
const open = computed(() => Boolean(props.state.kind))
let trigger, focusRoot, disposed = false
watch(open, async value => {
  if (value) {
    trigger = deepestActiveElement(document)
    focusRoot = portals?.getRootNode()
    await nextTick()
  } else {
    await nextTick()
    if (disposed || open.value) return
    restoreFocus(trigger, focusRoot)
    trigger = null
    emit('closed')
  }
})
function close(done) {
  if (requestEditorClose(props.state)) done?.()
}
function save() {
  if (!props.state.saving && !props.disabled && !props.conflicted) emit('save')
}
onBeforeUnmount(() => {disposed = true; trigger = null; focusRoot = null})
</script>

<template>
  <KsDrawer v-if="open" :model-value="open" :title="title" :append-to="portals" :append-to-body="false" :before-close="close" :close-on-press-escape="!state.saving" :close-on-click-modal="!state.saving" :show-close="!state.saving" :data-test="`drawer-${state.kind}`" :aria-busy="state.saving" class="rbac-editor-drawer" @update:model-value="value => {if (!value) close()}">
    <KsForm :id="formId" class="ks-horizontal rbac-drawer-form" :model="state.draft" @submit.prevent="save">
      <fieldset :disabled="state.saving || disabled || conflicted"><slot :draft="state.draft" /></fieldset>
      <slot name="error" />
      <div v-if="conflicted" class="message" role="alert"><p>{{ t('conflict') }}</p><KsButton :disabled="state.saving" data-test="conflict-refresh" @click="emit('refresh')">{{ t('reload') }}</KsButton></div>
    </KsForm>
    <template #footer>
      <div class="buttons rbac-drawer-actions">
        <KsButton :disabled="state.saving" @click="close()">{{ t('cancel') }}</KsButton>
        <KsButton type="primary" native-type="submit" :form="formId" :disabled="state.saving || disabled || conflicted" data-test="drawer-save"><ContentSave />{{ t(state.saving ? 'loading' : 'save') }}</KsButton>
      </div>
    </template>
  </KsDrawer>
  <KsDialog :model-value="state.discard" :append-to="portals" :append-to-body="false" :title="t('discardTitle')" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="!state.saving" width="min(460px, calc(100vw - 32px))" data-test="discard-confirm" @update:model-value="value => {if (!value && !state.saving) cancelDiscard(state)}">
    <p>{{ t('discardMessage') }}</p>
    <template #footer><KsButton :disabled="state.saving" @click="cancelDiscard(state)">{{ t('cancel') }}</KsButton><KsButton type="danger" :disabled="state.saving" @click="confirmDiscard(state)">{{ t('discard') }}</KsButton></template>
  </KsDialog>
</template>
