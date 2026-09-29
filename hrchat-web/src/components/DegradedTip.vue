<template>
  <a-alert type="info" show-icon class="degraded-tip">
    <template #message>
      {{ message }}
    </template>
  </a-alert>
</template>

<script setup lang="ts">
defineProps<{
  message?: string
}>()
</script>

<style scoped>
.degraded-tip {
  margin-bottom: 8px;
}
</style>
