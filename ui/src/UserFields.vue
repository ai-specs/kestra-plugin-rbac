<script setup>
import {useI18n} from 'vue-i18n'
import KsFormItem from '@kestra-io/design-system/components/Form/KsForm/KsFormItem'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
defineProps({draft: {type: Object, required: true}})
const {t} = useI18n()
</script>
<template>
  <KsFormItem label="subject"><output v-if="draft.revision !== undefined"><code>{{ draft.subject }}</code></output><KsInput v-else v-model="draft.username" required autocomplete="off" name="username" /></KsFormItem>
  <KsFormItem :label="t('name')"><KsInput v-model="draft.name" name="name" /></KsFormItem>
  <KsFormItem :label="t('email')"><KsInput v-model="draft.email" type="email" name="email" /></KsFormItem>
  <p v-if="draft.revision === undefined" class="message">{{ t('forcePasswordChange') }}</p>
</template>
