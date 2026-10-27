<script setup>
import {useI18n} from 'vue-i18n'
import KsInput from '@kestra-io/design-system/components/Form/KsInput'
defineProps({draft: {type: Object, required: true}})
const {t} = useI18n()
</script>
<template>
  <label class="rbac-kv-field"><span>subject</span><output v-if="draft.revision !== undefined"><code>{{ draft.subject }}</code></output><KsInput v-else v-model="draft.username" required autocomplete="off" name="username" /></label>
  <label class="rbac-kv-field"><span>{{ t('name') }}</span><KsInput v-model="draft.name" name="name" /></label>
  <label class="rbac-kv-field"><span>{{ t('email') }}</span><KsInput v-model="draft.email" type="email" name="email" /></label>
  <p v-if="draft.revision === undefined" class="message">{{ t('forcePasswordChange') }}</p>
</template>
