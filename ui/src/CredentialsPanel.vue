<script setup>
import {computed, inject, onBeforeUnmount, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import {credentialDisplay} from './credential-state.js'
import {rotateCredential} from './account-actions.js'
const props = defineProps({api: {type: Object, required: true}, groupId: String, clientId: String, canManage: Boolean, oneTime: {type: Object, required: true}})
const emit = defineEmits(['failure', 'busy'])
const {t} = useI18n()
const portals = inject('rbac-portals', null)
const credential = ref(null), busy = ref(false), failed = ref(false), conflict = ref(false), maintenance = ref(false), confirmRotate = ref(false)
const display = computed(() => credentialDisplay(credential.value))
const maintenanceVisible = computed(() => credential.value?.rotationMode === 'deployment-maintenance' || maintenance.value)
let generation = 0, disposed = false
async function load() {
  const current = ++generation
  credential.value = null; busy.value = true; failed.value = false; conflict.value = false; maintenance.value = false
  try {
    const value = await props.api.credentials(props.groupId, props.clientId)
    if (current !== generation || disposed) return
    if (value?.clientId !== props.clientId) throw new Error('invalid_credential_target')
    credential.value = value
  } catch (error) {if (current === generation && !disposed) {failed.value = true; if ([401, 403].includes(error.status)) emit('failure', error)}}
  finally {if (current === generation && !disposed) busy.value = false}
}
async function rotate() {
  if (busy.value || conflict.value || !props.canManage || !display.value.canRotate) return
  confirmRotate.value = false
  const current = generation
  busy.value = true; failed.value = false; emit('busy', true)
  try {
    await rotateCredential(props.api, props.groupId, credential.value, props.oneTime, () => !disposed && current === generation)
    if (!disposed && current === generation) {
      credential.value = null
      await load()
    }
  } catch (error) {
    if (current === generation && !disposed) {
      failed.value = true; conflict.value = [409, 412, 428].includes(error.status)
      maintenance.value = error.code === 'maintenance_required'
      if ([401, 403].includes(error.status)) emit('failure', error)
    }
  } finally {if (!disposed) {busy.value = false; emit('busy', false)}}
}
function requestRotate() {
  if (busy.value || conflict.value || maintenanceVisible.value || !props.canManage || !display.value.canRotate) return
  confirmRotate.value = true
}
watch(() => [props.groupId, props.clientId], load, {immediate: true})
onBeforeUnmount(() => {disposed = true; generation++; credential.value = null; emit('busy', false)})
</script>
<template>
  <div data-test="application-credentials" :aria-busy="busy">
    <span v-if="busy">{{ t('loading') }}</span>
    <template v-else>
      <div class="rbac-credential-row">
        <code v-if="display.hint" class="rbac-credential-hint">{{ display.hint }}</code><span v-else class="rbac-credential-hint">{{ t(display.key) }}</span>
        <KsButton v-if="canManage && maintenanceVisible" class="rbac-credential-help" :aria-label="t('maintenanceRotation')" :title="t('maintenanceRotation')" data-test="credential-maintenance-help"><svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 1.1-.9 2-2 2h-1v3h2v-1.27c1.17-.42 2-1.52 2-2.73 0-2.21-1.79-4-4-4z" /></svg></KsButton>
        <KsButton v-else-if="canManage && display.canRotate" class="rbac-credential-rotate" :disabled="busy || conflict" :aria-label="t('rotateSecret')" :title="t('rotateSecret')" data-test="credential-rotate" @click="requestRotate"><svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.57 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg></KsButton>
      </div>
      <p v-if="failed" role="alert" class="error" data-test="request-error">{{ t(conflict ? 'conflict' : 'error') }}</p>
      <div v-if="failed" class="buttons"><KsButton :disabled="busy" :data-test="conflict ? 'conflict-refresh' : undefined" @click="load">{{ t('reload') }}</KsButton></div>
    </template>
    <KsDialog :model-value="confirmRotate" :append-to="portals" :append-to-body="false" :title="t('rotationConfirmTitle')" :show-close="false" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :before-close="done => {if (!busy) {confirmRotate = false; done()}}" width="min(520px, calc(100vw - 32px))" data-test="credential-rotate-confirm-dialog" @update:model-value="value => {if (!value && !busy) confirmRotate = false}">
      <p>{{ t('rotationConfirmMessage') }}</p>
      <template #footer><KsButton :disabled="busy" @click="confirmRotate = false">{{ t('cancel') }}</KsButton><KsButton type="danger" :disabled="busy" data-test="credential-rotate-confirm" @click="rotate">{{ t('rotationConfirmAction') }}</KsButton></template>
    </KsDialog>
  </div>
</template>
