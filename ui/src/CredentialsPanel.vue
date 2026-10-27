<script setup>
import {computed, onBeforeUnmount, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import {credentialDisplay} from './credential-state.js'
import {rotateCredential} from './account-actions.js'
const props = defineProps({api: {type: Object, required: true}, groupId: String, clientId: String, canManage: Boolean, oneTime: {type: Object, required: true}})
const emit = defineEmits(['failure', 'busy'])
const {t} = useI18n()
const credential = ref(null), busy = ref(false), failed = ref(false), conflict = ref(false), maintenance = ref(false)
const display = computed(() => credentialDisplay(credential.value))
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
watch(() => [props.groupId, props.clientId], load, {immediate: true})
onBeforeUnmount(() => {disposed = true; generation++; credential.value = null; emit('busy', false)})
</script>
<template>
  <div data-test="application-credentials" :aria-busy="busy">
    <span v-if="busy">{{ t('loading') }}</span><template v-else><code v-if="display.hint">{{ display.hint }}</code><span v-else>{{ t(display.key) }}</span></template>
    <p v-if="credential?.rotationMode === 'deployment-maintenance' || maintenance" class="message">{{ t('maintenanceRotation') }}</p>
    <p v-if="failed" role="alert" class="error" data-test="request-error">{{ t(conflict ? 'conflict' : 'error') }}</p>
    <div class="buttons"><KsButton v-if="canManage && display.canRotate && !maintenance" :disabled="busy || conflict" data-test="credential-rotate" @click="rotate">{{ t('rotateSecret') }}</KsButton><KsButton v-if="failed" :disabled="busy" :data-test="conflict ? 'conflict-refresh' : undefined" @click="load">{{ t('reload') }}</KsButton></div>
  </div>
</template>
