<script setup>
import {computed, inject, nextTick, onBeforeUnmount, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import {editorDirty, requestEditorClose, cancelDiscard, confirmDiscard} from './editor-state.js'
import {deepestActiveElement, restoreFocus} from './focus.js'

const props = defineProps({state: {type: Object, required: true}, title: {type: String, required: true}, disabled: Boolean, conflicted: Boolean})
const emit = defineEmits(['save', 'closed', 'refresh'])
const {t} = useI18n()
const content = ref(null)
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
  <KsDrawer :model-value="open" :append-to="portals" :append-to-body="false" :with-header="false" :size="state.maximized ? '100%' : 'min(760px, 100vw)'" :before-close="close" :close-on-press-escape="!state.saving" :close-on-click-modal="!state.saving" :show-close="false" class="rbac-editor-drawer" @update:model-value="value => {if (!value) close()}">
    <section ref="content" :data-test="`drawer-${state.kind}`" :aria-label="title" class="rbac-drawer-content" :aria-busy="state.saving">
      <header class="rbac-drawer-header">
        <h2>{{ title }}</h2>
        <KsButton :disabled="state.saving" :aria-label="t(state.maximized ? 'restoreDrawer' : 'maximizeDrawer')" :title="t(state.maximized ? 'restoreDrawer' : 'maximizeDrawer')" data-test="drawer-maximize" @click="state.maximized = !state.maximized">⤢</KsButton>
        <KsButton :disabled="state.saving" :aria-label="t('close')" :title="t('close')" data-test="drawer-close" @click="close()">×</KsButton>
      </header>
      <form class="rbac-drawer-form" @submit.prevent="save">
        <fieldset :disabled="state.saving || disabled || conflicted"><slot :draft="state.draft" /></fieldset>
        <slot name="error" />
        <div v-if="conflicted" class="message" role="alert"><p>{{ t('conflict') }}</p><KsButton :disabled="state.saving" data-test="conflict-refresh" @click="emit('refresh')">{{ t('reload') }}</KsButton></div>
        <footer class="buttons">
          <KsButton type="primary" native-type="submit" :disabled="state.saving || disabled || conflicted" data-test="drawer-save">{{ t(state.saving ? 'loading' : 'save') }}</KsButton>
          <KsButton :disabled="state.saving" @click="close()">{{ t('cancel') }}</KsButton>
        </footer>
      </form>
    </section>
  </KsDrawer>
  <KsDialog :model-value="state.discard" :append-to="portals" :append-to-body="false" :title="t('discardTitle')" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="!state.saving" width="min(460px, calc(100vw - 32px))" data-test="discard-confirm" @update:model-value="value => {if (!value && !state.saving) cancelDiscard(state)}">
    <p>{{ t('discardMessage') }}</p>
    <template #footer><KsButton :disabled="state.saving" @click="cancelDiscard(state)">{{ t('cancel') }}</KsButton><KsButton type="danger" :disabled="state.saving" @click="confirmDiscard(state)">{{ t('discard') }}</KsButton></template>
  </KsDialog>
</template>
