<script setup>
import {ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
import GrantEditor from './GrantEditor.vue'
const props = defineProps({project:{type:Object,required:true},upper:{type:Object,required:true},revision:{type:Number,required:true},targetRevision:{type:Number,required:true},disabled:Boolean})
const emit = defineEmits(['save'])
const {t} = useI18n()
const approved = ref(false)
const newScope = ref('')
watch(() => props.project, () => {approved.value = false}, {deep:true})
const text = value => Array.isArray(value) ? value.join('\n') : value
function addScope() {
  const name = newScope.value.trim()
  if (!name || Object.hasOwn(props.project.scopes,name)) return
  Object.defineProperty(props.project.scopes,name,{value:{description:'',enabled:false,grants:[],managementGrants:[]},enumerable:true,configurable:true,writable:true})
  newScope.value = ''
  approved.value = false
}
</script>
<template>
  <section aria-labelledby="reconciliation-title" data-test="project-reconciliation-editor">
    <h2 id="reconciliation-title">{{ t('atomicReconciliation') }}</h2>
    <p class="message">{{ t('atomicReconciliationHelp') }}</p>
    <details open><summary>{{ t('sharedIntent') }}</summary><pre data-test="reconciliation-upper">{{ JSON.stringify(upper, null, 2) }}</pre></details>
    <p>{{ t('currentDirectoryRevision') }}: <output data-test="reconciliation-target-revision">{{ targetRevision }}</output></p>
    <p>{{ t('revision') }}: <output data-test="reconciliation-policy-revision">{{ revision }}</output></p>
    <form @submit.prevent="emit('save', approved)"><fieldset :disabled="disabled" @input="approved = false" @change="approved = false">
      <p>{{ t('projectId') }}: {{ project.id }}</p>
      <label>{{ t('issuer') }}<KsInput v-model="project.issuer" name="reconciliation-issuer" required /></label>
      <label>{{ t('audiences') }}<textarea :value="text(project.audiences)" name="reconciliation-audiences" required @input="project.audiences = $event.target.value" /><small>{{ t('onePerLine') }}</small></label>
      <label>{{ t('directoryRevision') }}<input v-model="project.directoryRevision" name="reconciliation-directory-revision" type="number" min="1" required><small>{{ t('reviewDirectoryRevision') }}</small></label>
      <label class="checkbox"><input v-model="project.enabled" type="checkbox" name="reconciliation-enabled">{{ t('enabled') }}</label>
      <section v-for="(scope, name) in project.scopes" :key="name" class="grant" :data-reconciliation-scope="name">
        <h3>{{ name }}</h3>
        <label>{{ t('description') }}<KsInput v-model="scope.description" name="reconciliation-scope-description" /></label>
        <label class="checkbox"><input v-model="scope.enabled" type="checkbox">{{ t('enabled') }}</label>
        <GrantEditor :grants="scope.grants" :management-grants="scope.managementGrants" />
        <KsButton native-type="button" @click="delete project.scopes[name]; approved = false">{{ t('deleteLocal') }}</KsButton>
      </section>
      <label>{{ t('scope') }}<KsInput v-model="newScope" name="reconciliation-new-scope" /></label>
      <KsButton native-type="button" @click="addScope">{{ t('addLocalScope') }}</KsButton>
    </fieldset>
    <fieldset :disabled="disabled">
      <label class="checkbox"><input v-model="approved" type="checkbox" data-test="approve-project-reconciliation">{{ t('reviewedBinding') }}</label>
      <KsButton type="primary" native-type="submit" :disabled="!approved" data-test="save-project-reconciliation">{{ t('saveCompleteProject') }}</KsButton>
    </fieldset></form>
  </section>
</template>
