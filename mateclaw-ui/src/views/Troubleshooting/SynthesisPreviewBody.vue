<template>
  <div class="synthesis-preview-body" :class="{ embedded }">
    <el-alert type="warning" :closable="false" class="scope-alert">
      <template #title>
        这里使用服务端保存的历史证据验证取证步骤。
        不会访问真实观测云、调用模型、创建排障规则或写入评估台账。
      </template>
    </el-alert>

    <el-form label-position="top" class="preview-form">
      <el-form-item label="system">
        <el-input v-model="form.system" :disabled="loading" placeholder="CSDP" />
      </el-form-item>
      <el-form-item label="service">
        <el-input v-model="form.service" :disabled="loading" placeholder="csdp-session-service" />
      </el-form-item>
      <el-form-item label="场景搜索键">
        <el-input v-model="form.searchTerm" :disabled="loading" placeholder="message_send_failed" />
        <small>只接受已映射的安全标识符，不接受自然语言、DQL 或原始日志。</small>
      </el-form-item>
      <el-form-item label="证据窗口">
        <el-select v-model="form.window" :disabled="loading" style="width: 100%">
          <el-option
            v-for="option in EVIDENCE_WINDOW_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="故障发生时间（可选，ISO-8601）" class="occurred-at">
        <el-input v-model="form.occurredAt" :disabled="loading" placeholder="留空则由服务端取当前时间" />
      </el-form-item>
    </el-form>

    <div v-if="preview" class="preview-result">
      <header class="result-head">
        <div>
          <span>取证结果</span>
          <h3>历史证据回放完成</h3>
          <p>
            {{ preview.system }} / {{ preview.service }} · {{ preview.searchTerm }} · {{ form.window }}；
            仅验证取证链路；没有创建或批准任何排障规则。
          </p>
        </div>
        <div class="result-facts">
          <b>{{ preview.matchCount }}</b><span>条查询结果</span>
          <code>{{ preview.psId }}</code>
        </div>
      </header>

      <section class="evidence-spine" aria-label="取证步骤">
        <article v-for="(step, index) in evidenceSteps" :key="step.signalKind">
          <span class="step-number">{{ index + 1 }}</span>
          <div>
            <code>{{ step.signalKind }}</code>
            <b>{{ step.label }}</b>
            <small>
              {{ step.source || '未取得证据' }} · {{ step.queryId || '无引用' }} ·
              {{ step.collectedAt ? shortTime(step.collectedAt) : '未采集' }}
            </small>
          </div>
          <el-tag
            :type="step.status === 'MISSING' ? 'danger' : step.status === 'ANOMALY' ? 'warning' : 'success'"
            size="small"
            effect="plain"
          >
            {{ step.status }}
          </el-tag>
        </article>
      </section>

      <div class="result-grid">
        <section class="trace-card">
          <div class="section-head">
            <div><span>关联日志摘要</span><h4>PS ID 关联日志轨迹</h4></div>
            <small>{{ preview.skeleton.elapsedMs }} ms · {{ preview.skeleton.sourceEntryCount }} 条关联日志</small>
          </div>
          <ol class="trace-list">
            <li
              v-for="event in preview.skeleton.timeline"
              :key="`${event.sequenceIndex}-${event.service}`"
              :class="{ anomalous: event.anomalous }"
            >
              <time>+{{ event.offsetMs }} ms</time>
              <div><b>{{ event.service }}</b><span>{{ event.level }}</span><p>{{ event.message }}</p></div>
              <small>{{ event.durationMs == null ? '未记录耗时' : `${event.durationMs} ms` }}</small>
            </li>
          </ol>
          <p v-if="preview.skeleton.omittedEntryCount" class="omitted">
            另有 {{ preview.skeleton.omittedEntryCount }} 条事件因确定性预算被省略。
          </p>
        </section>

        <aside class="contrast-card" :class="{ unavailable: !preview.contrastAvailable }">
          <span>请求表现对比</span>
          <h4>故障请求和正常请求有什么不同</h4>
          <template v-if="previewContrastNarrative">
            <strong>{{ previewContrastNarrative.summary }}</strong>
            <p>{{ previewContrastNarrative.interpretation }}</p>
            <small>{{ previewContrastNarrative.scope }}</small>
          </template>
          <p v-else>未取得正常请求用于比较；当前只能保留线索，不能把缺少对照误认为没有异常。</p>
        </aside>
      </div>

      <ul v-if="preview.warnings.length" class="preview-warnings">
        <li v-for="warning in preview.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </div>

    <div class="preview-actions">
      <el-button v-if="!embedded" @click="$emit('close')">关闭</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canPreview" @click="$emit('run')">
        运行只读证据预览
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { SopSynthesisPreview, SopSynthesisPreviewRequest } from '@/api'
import { EVIDENCE_WINDOW_OPTIONS, type SynthesisEvidenceStep } from './synthesisPreview'

defineProps<{
  form: SopSynthesisPreviewRequest
  preview: SopSynthesisPreview | null
  loading: boolean
  canPreview: boolean
  evidenceSteps: SynthesisEvidenceStep[]
  previewContrastNarrative: {
    summary: string
    interpretation: string
    scope: string
  } | null
  embedded?: boolean
}>()

defineEmits<{
  run: []
  close: []
}>()

function shortTime(value: string) {
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}
</script>

<style scoped>
.synthesis-preview-body.embedded { padding-bottom: 8px; }
.scope-alert { margin-bottom: 16px; }
.preview-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.preview-form :deep(.el-form-item) { margin-bottom: 13px; }
.preview-form small { display: block; margin-top: 4px; color: var(--mc-text-secondary); font-size: 10px; }
.occurred-at { grid-column: 1 / -1; }
.preview-result { margin-top: 5px; padding-top: 18px; border-top: 1px solid var(--mc-border); }
.result-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.result-head span,.section-head span,.contrast-card>span {
  color: var(--mc-text-tertiary);
  font-size: 9.5px;
  font-weight: 750;
  letter-spacing: .1em;
  text-transform: uppercase;
}
.result-head h3 { margin: 5px 0 4px; font-size: 17px; color: var(--mc-text-primary); }
.result-head p { margin: 0; color: var(--mc-text-secondary); font-size: 10.5px; }
.result-facts { display: grid; grid-template-columns: auto auto; align-items: baseline; gap: 2px 7px; text-align: right; }
.result-facts b { color: var(--mc-primary); font-size: 21px; }
.result-facts span { color: var(--mc-text-secondary); font-size: 10px; }
.result-facts code { grid-column: 1 / -1; color: var(--mc-text-secondary); font-size: 10px; }
.evidence-spine { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; margin-top: 16px; }
.evidence-spine article {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: start;
  padding: 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-muted);
}
.step-number {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: white;
  background: var(--mc-primary);
  font-size: 10px;
  font-weight: 700;
}
.evidence-spine code,.evidence-spine b,.evidence-spine small { display: block; }
.evidence-spine code { color: var(--mc-primary); font-size: 9.5px; }
.evidence-spine b { margin-top: 4px; font-size: 11px; color: var(--mc-text-primary); }
.evidence-spine small {
  margin-top: 5px;
  color: var(--mc-text-secondary);
  font-size: 8.5px;
  line-height: 1.45;
  word-break: break-all;
}
.result-grid { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(220px, .7fr); gap: 11px; margin-top: 11px; }
.trace-card,.contrast-card { padding: 14px; border: 1px solid var(--mc-border); border-radius: 9px; background: var(--mc-bg); }
.section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.section-head h4,.contrast-card h4 { margin: 5px 0 0; font-size: 13px; color: var(--mc-text-primary); }
.section-head>small { color: var(--mc-text-secondary); font-size: 9.5px; }
.trace-list { margin: 13px 0 0; padding: 0; list-style: none; }
.trace-list li {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr) auto;
  gap: 10px;
  padding: 9px 0;
  border-top: 1px solid var(--mc-border);
}
.trace-list time,.trace-list small { color: var(--mc-text-secondary); font-size: 9px; }
.trace-list b { font-size: 11px; color: var(--mc-text-primary); }
.trace-list span { margin-left: 7px; color: var(--mc-text-secondary); font-size: 9px; }
.trace-list p { margin: 3px 0 0; color: var(--mc-text-secondary); font-size: 10px; }
.trace-list li.anomalous b,.trace-list li.anomalous span { color: var(--mc-danger); }
.omitted { margin: 8px 0 0; color: var(--mc-warning); font-size: 9.5px; }
.contrast-card { background: color-mix(in srgb, var(--mc-success) 8%, var(--mc-bg)); }
.contrast-card.unavailable { background: color-mix(in srgb, var(--mc-warning) 10%, var(--mc-bg)); }
.contrast-card>strong,.contrast-card>code { display: block; margin-top: 10px; }
.contrast-card>strong { color: var(--mc-primary); font-size: 12px; }
.contrast-card>code { color: var(--mc-text-secondary); font-size: 9.5px; }
.contrast-card>p { color: var(--mc-warning); font-size: 10px; line-height: 1.55; }
.preview-warnings {
  margin: 11px 0 0;
  padding: 10px 12px 10px 28px;
  border-radius: 7px;
  color: var(--mc-warning);
  background: color-mix(in srgb, var(--mc-warning) 12%, var(--mc-bg));
  font-size: 9.5px;
  line-height: 1.55;
}
.preview-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--mc-border);
}
@media(max-width: 820px) {
  .preview-form,.evidence-spine,.result-grid { grid-template-columns: 1fr; }
  .occurred-at { grid-column: auto; }
}
</style>
