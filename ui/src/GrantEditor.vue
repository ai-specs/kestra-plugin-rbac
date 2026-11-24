<script setup>
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import KsCheckbox from '@kestra-io/design-system/components/Form/KsCheckbox/KsCheckbox'
import KsCheckboxGroup from '@kestra-io/design-system/components/Form/KsCheckbox/KsCheckboxGroup'
import {grantActions, resources, newGrant, selectGrantResource, namespaceRange, namespaceExpression} from './application-group-model.js'
defineProps({grants: {type: Array, required: true}, managementGrants: {type: Array, required: true}})
const {t} = useI18n()
</script>
<template>
  <p class="message" data-test="namespace-limits">{{ t('namespaceLimits') }}</p>
  <section v-for="(grant, index) in grants" :key="index" class="grant">
    <p>{{ t('rangeType') }}: {{ grant.scope }} · main</p>
    <label v-if="grant.scope === 'NAMESPACE'">{{ t('namespace') }}<KsInput :model-value="namespaceExpression(grant)" name="grant-namespace" required @update:model-value="grant.namespace = $event; delete grant.descendants" /></label>
    <p v-if="grant.scope === 'NAMESPACE'" data-test="namespace-range">{{ namespaceRange(grant, t) }}</p>
    <small v-if="grant.scope === 'NAMESPACE'">{{ t('namespaceSyntax') }}</small>
    <p v-if="grant.namespace?.startsWith('!')" data-test="namespace-complement-help">{{ t('namespaceComplementHelp') }}</p>
    <label>{{ t('resource') }}<KsSelect v-model="grant.resource" name="grant-resource" @update:model-value="selectGrantResource(grant)"><KsOption v-for="resource in resources" :key="resource" :value="resource" :label="resource" /></KsSelect></label>
    <fieldset><legend>{{ t('actions') }}</legend><KsCheckboxGroup v-model="grant.actions"><KsCheckbox v-for="action in grantActions(grant)" :key="action" :value="action">{{ action }}</KsCheckbox></KsCheckboxGroup></fieldset>
    <p v-if="grant.resource === 'METADATA'" class="message">{{ t('metadataRange') }}</p>
    <p v-else-if="grant.resource === 'AI' && grant.scope === 'TENANT'" class="message">{{ t('aiTenantRange') }}</p>
    <KsCheckbox v-else v-model="grant.allIds">{{ t('allIds') }}</KsCheckbox>
    <p v-if="grant.resource === 'AI' && grant.scope === 'NAMESPACE'" class="message">{{ t('aiLegacyRange') }}</p>
    <label v-if="!grant.allIds">{{ t('ids') }}<KsInput type="textarea" :rows="3" :model-value="Array.isArray(grant.ids) ? grant.ids.join('\n') : grant.ids" @update:model-value="grant.ids = $event" /><small>{{ t('onePerLine') }}</small></label>
    <KsButton native-type="button" @click="grants.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
  </section>
  <KsButton native-type="button" data-test="add-grant" @click="grants.push(newGrant())">{{ t('addGrant') }}</KsButton>
  <section v-for="(grant, index) in managementGrants" :key="'management-' + index" class="grant">
    <label>{{ t('managementDomain') }}<KsSelect v-model="grant.domain" @update:model-value="grant.domain !== 'LOCAL_POLICY' && (grant.applicationGroupIds = [])"><KsOption value="IDENTITY" :label="t('applicationGroups')" /><KsOption value="LOCAL_POLICY" :label="t('kestraActualPermissions')" /><KsOption v-if="grant.domain === 'BUSINESS_INSTANCE'" value="BUSINESS_INSTANCE" :label="t('legacyBusinessDomain')" /></KsSelect></label>
    <p v-if="grant.domain === 'BUSINESS_INSTANCE'" data-test="legacy-business-warning">{{ t('legacyBusinessWarning') }}</p>
    <label v-if="grant.domain === 'LOCAL_POLICY'">{{ t('applicationGroupIds') }}<KsInput type="textarea" :rows="3" :model-value="Array.isArray(grant.applicationGroupIds) ? grant.applicationGroupIds.join('\n') : grant.applicationGroupIds ?? ''" name="management-project-ids" @update:model-value="grant.applicationGroupIds = $event" /><small>{{ t('onePerLine') }}</small></label>
    <fieldset><legend>{{ t('actions') }}</legend><KsCheckboxGroup v-model="grant.actions"><KsCheckbox v-for="action in ['READ', 'MANAGE']" :key="action" :value="action">{{ action }}</KsCheckbox></KsCheckboxGroup></fieldset>
    <KsButton native-type="button" @click="managementGrants.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
  </section>
  <KsButton native-type="button" @click="managementGrants.push({domain: 'IDENTITY', actions: [], applicationGroupIds: []})">{{ t('addManagementGrant') }}</KsButton>
</template>
