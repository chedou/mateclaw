<template>
  <section class="message-send-evidence-card">
    <div class="scenario-head">
      <div>
        <span class="scenario-kicker">已选场景 · 会话消息发送失败</span>
        <h3>{{ waiting ? '等待开始只读取证' : '场景取证已进入详情链路' }}</h3>
        <p>系统按排查指南（Playbook）固定的顺序读取证据；页面不能改查询、判据或根因。</p>
      </div>
      <div class="scenario-authority">
        <span>已冻结排查指南</span>
        <code>{{ playbookRef }}</code>
      </div>
    </div>

    <ol class="scenario-steps">
      <li>
        <b>1. 先找到失败请求</b>
        <span>查找“消息发送失败”样本，取得可用的 PS ID。</span>
      </li>
      <li>
        <b>2. 还原这次调用经过</b>
        <span>沿 PS ID 读取跨服务记录，压缩成可复核的调用链骨架。</span>
      </li>
      <li>
        <b>3. 对比成功和失败样本</b>
        <span>只保留稳定差异，再由确定性判据决定结论或弃权。</span>
      </li>
    </ol>

    <div class="scenario-foot">
      <p v-if="waiting">开始后才会读取证据。如果来源不可用或证据不足，系统会诚实弃权。</p>
      <p v-else>完整过程已记录在下方“七阶段调查轨迹”和“证据关系”中。</p>
      <el-button
        v-if="waiting"
        type="primary"
        :loading="loading"
        :disabled="!canOperate"
        @click="$emit('run')"
      >开始三次只读取证</el-button>
      <el-tag v-else type="success" effect="plain">当前状态：{{ statusLabel }}
      </el-tag>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Diagnosis } from '@/api'

const props = defineProps<{
  diagnosis: Diagnosis
  canOperate: boolean
  loading: boolean
}>()

defineEmits<{ run: [] }>()

const waiting = computed(() => props.diagnosis.status === 'NEEDS_INVESTIGATION')
const playbookRef = computed(() => {
  const ref = props.diagnosis.sourcePlaybookVersionRef
  return ref ? `${ref.playbookId}@${ref.playbookVersion}` : '未记录'
})
const statusLabel = computed(() => ({
  READY_FOR_HUMAN: '待人工确认',
  CONFIRMED: '已确认',
  TRANSFERRED: '已转派',
  CLOSED: '已关闭',
  NEEDS_INVESTIGATION: '等待取证',
}[props.diagnosis.status]))
</script>

<style scoped>
.message-send-evidence-card { width:100%; margin:20px 0 0; padding:22px 24px; border:1px solid var(--mc-primary); border-radius:var(--mc-radius-md); background:color-mix(in srgb,var(--mc-primary) 4%,var(--mc-bg-elevated)); box-shadow:0 8px 28px var(--mc-shadow-soft); }
.scenario-head { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; }
.scenario-kicker { color:var(--mc-primary); font-size:var(--mc-text-xs); font-weight:750; }
.scenario-head h3 { margin:5px 0; font-size:var(--mc-text-lg); }
.scenario-head p,.scenario-foot p { margin:0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.65; }
.scenario-authority { min-width:220px; padding:10px 12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.scenario-authority span { display:block; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); }
.scenario-authority code { display:block; margin-top:4px; color:var(--mc-text-primary); overflow-wrap:anywhere; }
.scenario-steps { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin:18px 0; padding:0; list-style:none; }
.scenario-steps li { padding:13px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.scenario-steps b,.scenario-steps span { display:block; }
.scenario-steps b { font-size:var(--mc-text-sm); }
.scenario-steps span { margin-top:6px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.6; }
.scenario-foot { display:flex; align-items:center; justify-content:space-between; gap:18px; padding-top:14px; border-top:1px solid var(--mc-border); }
@media(max-width:860px){.scenario-head,.scenario-foot{align-items:stretch;flex-direction:column}.scenario-authority{min-width:0}.scenario-steps{grid-template-columns:1fr}}
</style>
