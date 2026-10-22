<script setup>
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import {actions, resources, newGrant, selectGrantResource} from './application-group-model.js'
defineProps({grants: {type: Array, required: true}, managementGrants: {type: Array, required: true}})
const {t} = useI18n()
</script>
<template>
  <section v-for="(grant, index) in grants" :key="index" class="grant">
    <p>{{ t('rangeType') }}: {{ grant.scope }} · main</p>
    <label v-if="grant.scope === 'NAMESPACE'">{{ t('namespace') }}<KsInput v-model="grant.namespace" name="grant-namespace" required /></label>
    <label v-if="grant.scope === 'NAMESPACE'" class="checkbox"><input v-model="grant.descendants" type="checkbox">{{ t('children') }}</label>
    <label>{{ t('resource') }}<select v-model="grant.resource" name="grant-resource" @change="selectGrantResource(grant)"><option v-for="resource in resources" :key="resource">{{ resource }}</option></select></label>
    <fieldset><legend>{{ t('actions') }}</legend><label v-for="action in grant.resource === 'METADATA' ? ['READ', 'LIST'] : grant.resource === 'MCP_SERVER' ? ['READ', 'LIST', 'CREATE', 'UPDATE', 'DELETE'] : actions" :key="action" class="checkbox"><input v-model="grant.actions" type="checkbox" :value="action">{{ action }}</label></fieldset>
    <p v-if="grant.resource === 'METADATA'" class="message">{{ t('metadataRange') }}</p>
    <label v-else class="checkbox"><input v-model="grant.allIds" type="checkbox">{{ t('allIds') }}</label>
    <label v-if="!grant.allIds">{{ t('ids') }}<textarea :value="Array.isArray(grant.ids) ? grant.ids.join('\n') : grant.ids" @input="grant.ids = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
    <KsButton native-type="button" @click="grants.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
  </section>
  <KsButton native-type="button" data-test="add-grant" @click="grants.push(newGrant())">{{ t('addGrant') }}</KsButton>
  <section v-for="(grant, index) in managementGrants" :key="'management-' + index" class="grant">
    <label>{{ t('managementDomain') }}<select v-model="grant.domain" @change="grant.domain !== 'LOCAL_POLICY' && (grant.applicationGroupIds = [])"><option value="IDENTITY">{{ t('applicationGroups') }}</option><option value="LOCAL_POLICY">{{ t('kestraActualPermissions') }}</option><option value="BUSINESS_INSTANCE">{{ t('resource') }}</option></select></label>
    <label v-if="grant.domain === 'LOCAL_POLICY'">{{ t('applicationGroupIds') }}<textarea :value="Array.isArray(grant.applicationGroupIds) ? grant.applicationGroupIds.join('\n') : grant.applicationGroupIds ?? ''" name="management-project-ids" @input="grant.applicationGroupIds = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
    <fieldset><legend>{{ t('actions') }}</legend><label v-for="action in ['READ', 'MANAGE']" :key="action" class="checkbox"><input v-model="grant.actions" type="checkbox" :value="action">{{ action }}</label></fieldset>
    <KsButton native-type="button" @click="managementGrants.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
  </section>
  <KsButton native-type="button" @click="managementGrants.push({domain: 'IDENTITY', actions: [], applicationGroupIds: []})">{{ t('addManagementGrant') }}</KsButton>
</template>
