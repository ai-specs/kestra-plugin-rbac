<script setup>
import KsCard from '@kestra-io/design-system/components/Data/KsCard'

defineProps({items: {type: Array, required: true}, busy: Boolean})
const emit = defineEmits(['open'])
</script>

<template>
  <div class="application-group-cards" data-test="application-group-cards">
    <KsCard v-for="item in items" :key="item.id" shadow="hover">
      <button class="application-group-card-link" type="button" :disabled="busy" @click="emit('open', item)">
        <span class="application-group-card-title">{{ item.displayName || item.id }}</span>
        <span class="application-group-card-id">{{ item.id }}</span>
      </button>
    </KsCard>
  </div>
</template>
