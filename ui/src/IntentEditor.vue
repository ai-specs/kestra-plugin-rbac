<script setup>
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import {actions, resources, newIntent} from './project-model.js'
defineProps({value: {type: Object, required: true}})
const {t} = useI18n()
const text = value => Array.isArray(value) ? value.join('\n') : value
</script>
<template>
  <section v-for="(intent, index) in value.resourceIntents" :key="index" class="grant">
    <label>{{ t('resource') }}<select v-model="intent.resourceType"><option v-for="resource in resources" :key="resource">{{ resource }}</option></select></label>
    <label>{{ t('namespace') }}<select v-model="intent.namespaces.kind"><option value="ALL">{{ t('allNamespaces') }}</option><option value="RESTRICTED">{{ t('restricted') }}</option></select></label>
    <template v-if="intent.namespaces.kind === 'RESTRICTED'">
      <label>{{ t('exactNamespaces') }}<textarea :value="text(intent.namespaces.exact)" @input="intent.namespaces.exact = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
      <label>{{ t('subtrees') }}<textarea :value="text(intent.namespaces.subtrees)" @input="intent.namespaces.subtrees = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
    </template>
    <label>{{ t('ids') }}<select v-model="intent.resourceIds.kind"><option value="ALL">{{ t('allIds') }}</option><option value="RESTRICTED">{{ t('restricted') }}</option></select></label>
    <label v-if="intent.resourceIds.kind === 'RESTRICTED'">{{ t('ids') }}<textarea :value="text(intent.resourceIds.ids)" @input="intent.resourceIds.ids = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
    <fieldset><legend>{{ t('actions') }}</legend><label v-for="action in actions" :key="action" class="checkbox"><input v-model="intent.actions" type="checkbox" :value="action">{{ action }}</label></fieldset>
    <KsButton native-type="button" @click="value.resourceIntents.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
  </section>
  <KsButton native-type="button" @click="value.resourceIntents.push(newIntent())">{{ t('addIntent') }}</KsButton>
  <section v-for="(intent, index) in value.managementIntents" :key="index" class="grant">
    <label>{{ t('managementDomain') }}<select v-model="intent.domain" @change="intent.domain !== 'LOCAL_POLICY' && (intent.projectIds = [])"><option>IDENTITY</option><option>LOCAL_POLICY</option><option>BUSINESS_INSTANCE</option></select></label>
    <label v-if="intent.domain === 'LOCAL_POLICY'">{{ t('managementProjectIds') }}<textarea :value="Array.isArray(intent.projectIds) ? intent.projectIds.join('\n') : intent.projectIds ?? ''" name="management-project-ids" @input="intent.projectIds = $event.target.value" /><small>{{ t('managementProjectIdsHelp') }}</small></label>
    <fieldset><legend>{{ t('actions') }}</legend><label v-for="action in ['READ', 'MANAGE']" :key="action" class="checkbox"><input v-model="intent.actions" type="checkbox" :value="action">{{ action }}</label></fieldset>
    <KsButton native-type="button" @click="value.managementIntents.splice(index, 1)">{{ t('removeGrant') }}</KsButton>
  </section>
  <KsButton native-type="button" @click="value.managementIntents.push({domain: 'IDENTITY', actions: [], projectIds: []})">{{ t('addManagementIntent') }}</KsButton>
</template>
