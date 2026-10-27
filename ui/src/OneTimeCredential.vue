<script setup>
import {inject, nextTick, onBeforeUnmount, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import {clearOneTimeValue, copyOneTimeValue} from './credential-state.js'
import {deepestActiveElement, restoreFocus} from './focus.js'
const props = defineProps({state: {type: Object, required: true}})
const {t} = useI18n()
const portals = inject('rbac-portals')
const copyValue = () => copyOneTimeValue(props.state, globalThis.navigator?.clipboard)
let trigger, focusRoot, disposed = false
watch(() => Boolean(props.state.value), async open => {
  if (open) {trigger = deepestActiveElement(document); focusRoot = portals?.getRootNode()}
  else {await nextTick(); if (disposed || props.state.value) return; restoreFocus(trigger, focusRoot); trigger = null; focusRoot = null}
})
onBeforeUnmount(() => {disposed = true; trigger = null; focusRoot = null; clearOneTimeValue(props.state)})
</script>
<template>
  <KsDialog :model-value="Boolean(state.value)" :append-to="portals" :append-to-body="false" :title="state.kind === 'password' ? t('temporaryPassword') : 'client secret'" :close-on-click-modal="false" width="min(560px, calc(100vw - 32px))" :data-test="state.kind === 'password' ? 'temporary-password-once' : 'credential-once'" @update:model-value="value => {if (!value) clearOneTimeValue(state)}">
    <p><code>{{ state.subject }}</code></p><p class="message" role="alert">{{ t('oneTimeWarning') }}</p><p v-if="state.kind === 'password'">{{ t('forcePasswordChange') }}</p>
    <pre class="rbac-one-time-value" data-sensitive-credential tabindex="0">{{ state.value }}</pre>
    <p v-if="state.copyFailed" role="alert" class="error">{{ t('copyFailed') }}</p><p v-if="state.copied" role="status">{{ t('copied') }}</p>
    <template #footer><KsButton :disabled="!state.value" @click="copyValue">{{ t('copyValue') }}</KsButton><KsButton type="primary" @click="clearOneTimeValue(state)">{{ t('close') }}</KsButton></template>
  </KsDialog>
</template>
