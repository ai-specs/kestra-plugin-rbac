<script setup>
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import {splitLines} from './application-group-model.js'
const props = defineProps({draft: {type: Object, required: true}, disabled: Boolean, busy: Boolean})
const emit = defineEmits(['save', 'cancel'])
const {t} = useI18n()
function save() {if (!props.busy && !props.disabled) emit('save')}
function cancel() {if (!props.busy) emit('cancel')}
</script>
<template>
  <form class="rbac-inline-form" @submit.prevent="save" :aria-busy="busy"><fieldset :disabled="disabled || busy"><h2>{{ t(draft.revision === undefined ? 'create' : 'edit') }}</h2>
    <label v-if="draft._kind === 'group'">{{ t('displayName') }}<KsInput v-model="draft.displayName" :disabled="draft.builtIn" required maxlength="512" /></label>
    <template v-else-if="draft._kind === 'scope'"><label>{{ t('key') }}<KsInput v-model="draft.key" :disabled="draft.revision !== undefined" required maxlength="64" /></label><label>{{ t('roleName') }}<KsInput v-model="draft.name" required maxlength="512" /></label></template>
    <template v-else><label>{{ t('clientId') }}<KsInput v-model="draft.clientId" :disabled="draft.revision !== undefined" required /></label><label v-if="draft.revision === undefined">Audience<KsInput type="textarea" :rows="3" :model-value="splitLines(draft.audiences).join('\n')" @update:model-value="draft.audiences = splitLines($event)" /></label></template>
    <label>{{ t('description') }}<KsInput v-model="draft.description" type="textarea" :rows="3" maxlength="8192" /></label></fieldset><div class="buttons"><KsButton type="primary" native-type="submit" :disabled="disabled || busy" :data-test="draft._kind === 'scope' ? 'scope-save' : 'application-save'">{{ t('save') }}</KsButton><KsButton :disabled="busy" native-type="button" @click="cancel">{{ t('cancel') }}</KsButton></div>
  </form>
</template>
