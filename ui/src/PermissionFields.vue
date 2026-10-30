<script setup>
import {useI18n} from 'vue-i18n'
import KsFormItem from '@kestra-io/design-system/components/Form/KsForm/KsFormItem'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import KsCheckbox from '@kestra-io/design-system/components/Form/KsCheckbox/KsCheckbox'
import GrantEditor from './GrantEditor.vue'
import ScopePicker from './ScopePicker.vue'
defineProps({draft: {type: Object, required: true}, scopes: {type: Array, required: true}, implementationKind: {type: String, required: true}, editing: Boolean, knownScopeKeysOnly: Boolean})
const {t} = useI18n()
</script>
<template>
  <KsFormItem :label="t('permissionKey')"><KsInput v-model="draft.key" required :disabled="editing" maxlength="128" name="permission-key" /></KsFormItem>
  <KsFormItem :label="t('permissionName')"><KsInput v-model="draft.name" required maxlength="512" name="permission-name" /></KsFormItem>
  <KsFormItem :label="t('activationConditions')"><div><ScopePicker v-model="draft.scopeKeys" :scopes="scopes" :known-keys-only="knownScopeKeysOnly" /><small>{{ t('anyScope') }}</small></div></KsFormItem>
  <template v-if="implementationKind === 'kestra-policy'">
    <p class="message">{{ t('kestraActualPermissions') }} · {{ t('completeScopeRule') }}</p>
    <KsFormItem :label="t('description')"><KsInput v-model="draft.description" type="textarea" :rows="3" maxlength="8192" name="permission-description" /></KsFormItem>
    <KsCheckbox v-model="draft.enabled">{{ t('enabled') }}</KsCheckbox>
    <GrantEditor :grants="draft.grants" :management-grants="draft.managementGrants" />
  </template>
  <KsFormItem v-else-if="implementationKind === 'external-declaration'" :label="t('permissionDetails')"><KsInput v-model="draft.details" type="textarea" :rows="5" maxlength="32768" name="permission-details" /></KsFormItem>
  <p v-else role="alert" class="error">{{ t('unavailable') }}</p>
</template>
