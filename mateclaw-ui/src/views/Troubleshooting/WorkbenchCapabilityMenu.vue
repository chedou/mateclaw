<template>
  <el-dropdown trigger="click" @command="forwardCommand">
    <el-button :size="size" plain>更多能力</el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="action in WORKBENCH_CAPABILITY_ACTIONS"
          :key="action.command"
          :command="action.command"
        >{{ action.label }}</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import {
  WORKBENCH_CAPABILITY_ACTIONS,
  type WorkbenchCapabilityCommand,
} from './workbenchView'

withDefaults(defineProps<{
  size?: 'small' | 'default' | 'large'
}>(), {
  size: 'default',
})

const emit = defineEmits<{
  command: [command: WorkbenchCapabilityCommand]
}>()

function forwardCommand(command: WorkbenchCapabilityCommand) {
  emit('command', command)
}
</script>
