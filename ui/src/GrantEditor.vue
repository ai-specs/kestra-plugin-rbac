<script setup>
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import KsCheckbox from '@kestra-io/design-system/components/Form/KsCheckbox/KsCheckbox'
import KsCheckboxGroup from '@kestra-io/design-system/components/Form/KsCheckbox/KsCheckboxGroup'
import {actions, resources, newGrant, selectGrantResource} from './application-group-model.js'
defineProps({grants: {type: Array, required: true}, managementGrants: {type: Array, required: true}})
const {t} = useI18n()
</script>
<template>
  <section v-for="(grant, index) in grants" :key="index" class="grant">
    <p>{{ t('rangeType') }}: {{ grant.scope }} · main</p>
    <label v-if="grant.scope === 'NAMESPACE'">{{ t('namespace') }}<KsInput v-model="grant.namespace" name="grant-namespace" required /></label>
    <KsCheckbox v-if="grant.scope === 'NAMESPACE'" v-model="grant.descendants">{{ t('children') }}</KsCheckbox>
    <label>{{ t('resource') }}<KsSelect v-model="grant.resource" name="grant-resource" @update:model-value="selectGrantResource(grant)"><KsOption v-for="resource in resources" :key="resource" :value="resource" :label="resource" /></KsSelect></label>
    <fieldset><legend>{{ t('actions') }}</legend><KsCheckboxGroup v-model="grant.actions"><KsCheckbox v-for="action in grant.resource === 'METADATA' ? ['READ', 'LIST'] : grant.resource === 'MCP_SERVER' ? ['READ', 'LIST', 'CREATE', 'UPDATE', 'DELETE'] : actions" :key="action" :value="action">{{ action }}</KsCheckbox></KsCheckboxGroup></fieldset>
    <p v-if="grant.resource === 'METADATA'" class="message">{{ t('metadataRange') }}</p>
    <KsCheckbox v-else v-model="grant.allIds">{{ t('allIds') }}</KsCheckbox>
    <label v-if="!grant.allIds">{{ t('ids') }}<KsInput type="textarea" :rows="3" :model-value="Array.isArray(grant.ids) ? grant.ids.join('\n') : grant.ids" @update:model-value="grant.ids = $event" /><small>{{ t('onePerLine') }}</small></label>
    <KsButton native-type="button" @click="grants.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
  </section>
  <KsButton native-type="button" data-test="add-grant" @click="grants.push(newGrant())">{{ t('addGrant') }}</KsButton>
  <section v-for="(grant, index) in managementGrants" :key="'management-' + index" class="grant">
    <label>{{ t('managementDomain') }}<KsSelect v-model="grant.domain" @update:model-value="grant.domain !== 'LOCAL_POLICY' && (grant.applicationGroupIds = [])"><KsOption value="IDENTITY" :label="t('applicationGroups')" /><KsOption value="LOCAL_POLICY" :label="t('kestraActualPermissions')" /><KsOption value="BUSINESS_INSTANCE" :label="t('resource')" /></KsSelect></label>
    <label v-if="grant.domain === 'LOCAL_POLICY'">{{ t('applicationGroupIds') }}<KsInput type="textarea" :rows="3" :model-value="Array.isArray(grant.applicationGroupIds) ? grant.applicationGroupIds.join('\n') : grant.applicationGroupIds ?? ''" name="management-project-ids" @update:model-value="grant.applicationGroupIds = $event" /><small>{{ t('onePerLine') }}</small></label>
    <fieldset><legend>{{ t('actions') }}</legend><KsCheckboxGroup v-model="grant.actions"><KsCheckbox v-for="action in ['READ', 'MANAGE']" :key="action" :value="action">{{ action }}</KsCheckbox></KsCheckboxGroup></fieldset>
    <KsButton native-type="button" @click="managementGrants.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
  </section>
  <KsButton native-type="button" @click="managementGrants.push({domain: 'IDENTITY', actions: [], applicationGroupIds: []})">{{ t('addManagementGrant') }}</KsButton>
</template>
