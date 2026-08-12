<template>
  <el-drawer
    v-model="open"
    :title="TROUBLESHOOTING_UI_LABELS.firstUseTitle"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
  >
    <header class="first-use-intro">
      <span>从一条真实告警开始</span>
      <h2>二线先推进，开发在同一张排障单上接手</h2>
      <p>不需要先学习配置，也不要先找日志。平台会按已经接入的能力只读调查，证据不够就明确停下来。</p>
    </header>

    <ol class="first-use-steps">
      <li>
        <span class="step-number">1</span>
        <div>
          <b>粘贴告警</b>
          <p>填写系统、服务、故障发生时间和现象。有错误码或关联 ID 就补充；不要粘贴密钥和整段日志。</p>
          <small>谁来做：二线、值班人员或最先收到告警的人</small>
        </div>
      </li>
      <li>
        <span class="step-number">2</span>
        <div>
          <b>让平台只读调查</b>
          <p>优先按已审核的标准方法查；没有可用方法或证据不足时会停止并说明缺什么，不会猜结论。</p>
          <small>系统会做：查失败证据、关联线索和正常样本对照</small>
        </div>
      </li>
      <li>
        <span class="step-number">3</span>
        <div>
          <b>在详情确认下一步</b>
          <p>查看调查过程、证据说明和停止原因。二线可以继续处置，需要深入时三线开发直接从这里接手。</p>
          <small>你会得到：同一张可追踪、可复核的排障单</small>
        </div>
      </li>
    </ol>

    <section class="rehearsal-note" aria-label="首次使用建议">
      <span>首次建议</span>
      <div>
        <b>先保留“演练模式”</b>
        <p>演练不会占用正式排障的去重窗口。确认告警、取证和详情流程都符合预期后，再显式切换为正式排障。</p>
      </div>
    </section>

    <p class="admin-boundary">
      日常排障不从配置页开始。只有详情明确提示“缺数据源、查询规则或系统登记”时，才需要联系管理员补接入。
    </p>

    <template #footer>
      <el-button @click="open = false">稍后再看</el-button>
      <el-button type="primary" @click="$emit('start')">
        {{ TROUBLESHOOTING_UI_LABELS.startRehearsal }}
      </el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'

const open = defineModel<boolean>({ required: true })
defineEmits<{ start: [] }>()
</script>

<style scoped>
.first-use-intro { padding-bottom:20px; border-bottom:1px solid var(--mc-border); }
.first-use-intro>span { color:var(--mc-primary); font-size:var(--mc-text-xs); font-weight:750; letter-spacing:.08em; }
.first-use-intro h2 { max-width:460px; margin:8px 0 10px; color:var(--mc-text-primary); font-size:22px; line-height:1.35; letter-spacing:-.025em; }
.first-use-intro p { max-width:520px; margin:0; color:var(--mc-text-secondary); font-size:13px; line-height:1.7; }
.first-use-steps { margin:0; padding:0; list-style:none; }
.first-use-steps li { display:grid; grid-template-columns:30px minmax(0,1fr); gap:13px; padding:20px 0; border-bottom:1px solid var(--mc-border-light); }
.step-number { display:grid; place-items:center; width:28px; height:28px; border-radius:50%; color:var(--mc-primary); background:var(--mc-status-info-bg); font-size:12px; font-weight:800; }
.first-use-steps b,.first-use-steps small { display:block; }
.first-use-steps b { color:var(--mc-text-primary); font-size:14px; }
.first-use-steps p { margin:5px 0 8px; color:var(--mc-text-secondary); font-size:12px; line-height:1.65; }
.first-use-steps small { color:var(--mc-text-tertiary); font-size:11px; line-height:1.5; }
.rehearsal-note { display:grid; grid-template-columns:auto minmax(0,1fr); gap:13px; margin-top:20px; padding:15px 16px; border-left:3px solid var(--mc-success); background:var(--mc-status-success-bg); }
.rehearsal-note>span { align-self:start; padding:2px 7px; border-radius:10px; color:var(--mc-status-success-text); background:var(--mc-bg-elevated); font-size:10px; font-weight:750; white-space:nowrap; }
.rehearsal-note b { color:var(--mc-text-primary); font-size:13px; }
.rehearsal-note p { margin:4px 0 0; color:var(--mc-text-secondary); font-size:11px; line-height:1.6; }
.admin-boundary { margin:16px 0 0; color:var(--mc-text-tertiary); font-size:11px; line-height:1.65; }
@media(max-width:620px){.first-use-intro h2{font-size:19px}.rehearsal-note{grid-template-columns:1fr}.rehearsal-note>span{justify-self:start}}
</style>
