<template>
  <section class="capability-workspace-shell">
    <header class="capability-workspace-head">
      <div class="capability-workspace-heading">
        <span>{{ eyebrow }}</span>
        <h1>{{ title }}</h1>
        <p>{{ description }}</p>
      </div>
      <div class="capability-workspace-actions">
        <el-button text @click="$emit('back')">返回排障工作台</el-button>
        <el-button :loading="refreshLoading" plain @click="$emit('refresh')">刷新</el-button>
      </div>
    </header>

    <div class="capability-workspace-content">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  eyebrow: string
  title: string
  description: string
  refreshLoading?: boolean
}>(), {
  refreshLoading: false,
})

defineEmits<{
  back: []
  refresh: []
}>()
</script>

<style scoped>
.capability-workspace-shell { display:flex; flex-direction:column; width:100%; height:100%; min-width:0; min-height:0; color:var(--mc-text-primary); background:var(--mc-bg); }
.capability-workspace-head { display:flex; align-items:flex-end; justify-content:space-between; gap:24px; flex:0 0 auto; padding:22px 26px 18px; border-bottom:1px solid var(--mc-border-light); background:var(--mc-bg-elevated); }
.capability-workspace-heading { min-width:0; }
.capability-workspace-heading span { color:var(--mc-primary); font-size:10px; font-weight:800; letter-spacing:.12em; text-transform:uppercase; }
.capability-workspace-heading h1 { margin:4px 0 5px; color:var(--mc-text-primary); font-size:22px; letter-spacing:-.03em; }
.capability-workspace-heading p { max-width:720px; margin:0; color:var(--mc-text-secondary); font-size:12px; line-height:1.5; }
.capability-workspace-actions { display:flex; align-items:center; gap:6px; flex:0 0 auto; }
.capability-workspace-content { flex:1; min-height:0; padding:18px 22px 30px; overflow-y:auto; }
@media(max-width:760px){.capability-workspace-head{align-items:flex-start;flex-direction:column;padding:18px}.capability-workspace-actions{width:100%;justify-content:flex-end;flex-wrap:wrap}.capability-workspace-content{padding:14px}}
</style>
