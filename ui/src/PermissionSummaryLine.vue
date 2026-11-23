<script setup>
import {ref} from 'vue'
defineProps({entry: {type: Object, required: true}})
const hovered = ref(false)
const focused = ref(false)
</script>
<template>
  <span v-if="entry.range">{{ entry.tenant }} · <KsTooltip v-if="entry.explanation" :content="entry.explanation" :visible="hovered || focused" placement="top"><code tabindex="0" data-test="permission-range-expression" @mouseenter="hovered = true" @mouseleave="hovered = false" @focus="focused = true" @blur="focused = false">{{ entry.range }}</code></KsTooltip><code v-else data-test="permission-range-expression">{{ entry.range }}</code> · {{ entry.suffix }}</span>
  <span v-else>{{ entry.text }}</span>
</template>
