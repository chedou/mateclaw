<template>
  <div
    class="view-switch"
    :class="{ compact }"
    role="group"
    aria-label="排障队列显示模式"
  >
    <button
      type="button"
      :class="{ active: mode === 'LIST' }"
      :aria-pressed="mode === 'LIST'"
      @click="select('LIST')"
    >列表</button>
    <button
      type="button"
      :class="{ active: mode === 'QUEUE' }"
      :aria-pressed="mode === 'QUEUE'"
      @click="select('QUEUE')"
    >队列</button>
  </div>
</template>

<script setup lang="ts">
import type { WorkbenchViewSwitchMode } from './workbenchView'

const props = withDefaults(defineProps<{
  mode: WorkbenchViewSwitchMode
  compact?: boolean
}>(), {
  compact: false,
})

const emit = defineEmits<{
  change: [mode: WorkbenchViewSwitchMode]
}>()

function select(mode: WorkbenchViewSwitchMode) {
  if (mode !== props.mode) emit('change', mode)
}
</script>

<style scoped>
.view-switch { display:inline-flex; padding:3px; border:1px solid #d8dee9; border-radius:9px; background:#fff; }
.view-switch button { min-width:56px; padding:7px 12px; border:0; border-radius:6px; color:#667085; background:transparent; font:inherit; font-size:12px; font-weight:650; cursor:pointer; }
.view-switch button:hover { color:var(--blue); background:#f4f6ff; }
.view-switch button.active { color:#fff; background:var(--blue); box-shadow:0 2px 7px rgba(47,92,245,.22); }
.view-switch.compact { margin-top:8px; }
.view-switch.compact button { min-width:42px; padding:4px 7px; font-size:10px; }
</style>
