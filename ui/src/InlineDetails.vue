<script setup>
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import {splitLines} from './application-group-model.js'
defineProps({draft: {type: Object, required: true}, disabled: Boolean})
const emit = defineEmits(['save', 'cancel'])
const {t} = useI18n()
</script>
<template>
  <form class="rbac-inline-form" @submit.prevent="emit('save')"><fieldset :disabled="disabled"><h2>{{ t(draft.revision === undefined ? 'create' : 'edit') }}</h2>
    <label v-if="draft._kind === 'group'">{{ t('displayName') }}<KsInput v-model="draft.displayName" :disabled="draft.builtIn" required maxlength="512" /></label>
    <template v-else-if="draft._kind === 'scope'"><label>{{ t('key') }}<KsInput v-model="draft.key" :disabled="draft.revision !== undefined" required maxlength="64" /></label><label>{{ t('roleName') }}<KsInput v-model="draft.name" required maxlength="512" /></label></template>
    <template v-else><label>{{ t('clientId') }}<KsInput v-model="draft.clientId" :disabled="draft.revision !== undefined" required /></label><label v-if="draft.revision === undefined">Audience<KsInput type="textarea" :rows="3" :model-value="splitLines(draft.audiences).join('\n')" @update:model-value="draft.audiences = splitLines($event)" /></label></template>
    <label>{{ t('description') }}<KsInput v-model="draft.description" type="textarea" :rows="3" maxlength="8192" /></label><div class="buttons"><KsButton type="primary" native-type="submit" :data-test="draft._kind === 'scope' ? 'scope-save' : 'application-save'">{{ t('save') }}</KsButton><KsButton @click="emit('cancel')">{{ t('cancel') }}</KsButton></div>
  </fieldset></form>
</template>
