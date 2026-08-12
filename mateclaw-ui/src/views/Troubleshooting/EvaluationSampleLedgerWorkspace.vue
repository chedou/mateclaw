<template>
  <CapabilityWorkspaceShell
    eyebrow="复盘与沉淀"
    :title="TROUBLESHOOTING_UI_LABELS.evaluation"
    description="用真实故障和历史样本核对取证是否稳定、结论是否靠谱。"
    :refresh-loading="loading"
    @back="$emit('back')"
    @refresh="loadLedger"
  >
    <div class="evaluation-ledger-workspace">
      <el-alert type="warning" :closable="false" show-icon class="ledger-alert">
        这里只积累脱敏样本与人工确认的标准答案。样本够多不等于验收通过，也不会自动得出“已经省时”的结论。
      </el-alert>

      <section class="pilot-relay" aria-labelledby="pilot-relay-title">
        <header class="pilot-relay-head">
          <div>
            <span>试点闭环</span>
            <h2 id="pilot-relay-title">试点接力队列</h2>
            <p>系统只根据已保存的正式排障单、真源样本和影子运行，告诉下一位负责人现在只补哪一步。</p>
          </div>
          <div class="pilot-relay-metrics" aria-label="试点接力队列统计">
            <div><b>{{ pilotSummary.formal }}</b><span>正式排障单</span></div>
            <div><b>{{ pilotSummary.closed }}</b><span>已登记结果</span></div>
            <div><b>{{ pilotSummary.ready }}</b><span>可进入周复盘</span></div>
          </div>
        </header>

        <EvaluationPilotPlanPanel
          :plan="pilotPlan"
          :start-open="startPilotSetup"
          @updated="pilotPlan = $event"
        />

        <div v-if="pilotVisibleRows.length" class="pilot-relay-list">
          <button
            v-for="row in pilotVisibleRows"
            :key="row.diagnosisId"
            type="button"
            class="pilot-relay-row"
            :class="[{ active: row.diagnosisId === currentDiagnosisId }, `stage-${row.stage.toLowerCase()}`]"
            @click="openDiagnosis(row.diagnosisId)"
          >
            <span class="pilot-relay-stage"><i></i><b>{{ row.stageLabel }}</b></span>
            <span class="pilot-relay-case">
              <strong>{{ row.system }} / {{ row.service }}</strong>
              <small>Diagnosis {{ row.diagnosisId }}<template v-if="row.errorCode"> · {{ row.errorCode }}</template></small>
            </span>
            <span class="pilot-relay-owner"><small>当前接力人</small><b>{{ row.ownerLabel }}</b></span>
            <span class="pilot-relay-action"><small>现在只做这一件事</small><b>{{ row.nextAction }}</b></span>
            <span class="pilot-relay-open">打开排障单 →</span>
          </button>
          <p v-if="pilotQueue.length > pilotVisibleRows.length" class="pilot-relay-overflow">
            先展示最需要接力的 {{ pilotVisibleRows.length }} 张；其余 {{ pilotQueue.length - pilotVisibleRows.length }} 张继续保留在队列中。
          </p>
        </div>
        <div v-else-if="!loading" class="pilot-relay-empty">
          <div v-if="pilotConfigured"><b>当前试点范围还没有正式排障单</b><span>只有命中上述系统 / 服务的真实记录才会进入；演练记录不计入。</span></div>
          <div v-else><b>试点交接队列尚未启用</b><span>先配置精确范围和三位工作区负责人；系统不会用全量排障单伪装试点进度。</span></div>
          <el-button plain @click="$emit('back')">返回排障工作台</el-button>
        </div>

        <p class="pilot-relay-boundary">
          最近最多读取 100 张排障单。演练、Recorded Replay 和 fixture 不计入真实效果；这里也不替代 T7 正式录制批次验收。
        </p>
      </section>

      <section v-if="currentDiagnosisId" class="current-pilot-card">
        <header>
          <div>
            <span>当前排障单怎样进入试点评估</span>
            <strong>Diagnosis {{ currentDiagnosisId }}</strong>
          </div>
          <el-tag v-if="currentDiagnosisRehearsal" type="warning" effect="plain" size="small">演练记录</el-tag>
          <el-tag v-else type="success" effect="plain" size="small">正式记录</el-tag>
        </header>

        <ol class="pilot-steps">
          <li v-for="step in currentPilotSteps" :key="step.key" :class="step.state">
            <i>{{ step.index }}</i>
            <div><b>{{ step.label }}</b><small>{{ step.detail }}</small></div>
            <span>{{ step.stateLabel }}</span>
          </li>
        </ol>

        <div class="pilot-next-action">
          <div>
            <b>{{ currentPilotAction.title }}</b>
            <p>{{ currentPilotAction.detail }}</p>
            <small v-if="currentDiagnosisRehearsal">演练记录可用于操作复盘，但不计入正式投产效果样本。</small>
          </div>
          <el-button
            v-if="currentPilotAction.kind === 'VALIDATE'"
            type="primary"
            plain
            @click="$emit('open-validation')"
          >检查当前数据连接</el-button>
          <el-button
            v-else-if="currentPilotAction.kind === 'CAPTURE'"
            type="primary"
            @click="openCaptureDrawer"
          >采集当前排障样本</el-button>
          <el-button
            v-else-if="currentPilotAction.kind === 'CLOSE'"
            type="primary"
            plain
            @click="$emit('back')"
          >返回详情登记结果</el-button>
          <el-button
            v-else-if="currentPilotAction.kind === 'REFERENCE' && currentPrimarySample"
            type="primary"
            @click="openReference(currentPrimarySample)"
          >填写人工标准答案</el-button>
          <el-button
            v-else-if="currentPilotAction.kind === 'DETAIL' && currentPrimarySample"
            plain
            @click="selectSample(currentPrimarySample)"
          >查看当前样本</el-button>
        </div>
      </section>

      <div class="status-bar">
        <div class="status-metrics">
          <span>累计 {{ ledger?.summary.total ?? 0 }}</span>
          <span>可评估 {{ ledger?.summary.readyForEvaluation ?? 0 }}</span>
          <span>待参考解 {{ ledger?.summary.evidenceCaptured ?? 0 }}</span>
          <div class="progress-inline">
            <b>{{ progress.label }}</b>
            <el-progress :percentage="progress.percent" :stroke-width="6" :show-text="false" />
          </div>
          <small class="progress-note">{{ progress.note }}</small>
        </div>
        <div class="status-actions">
          <el-switch
            v-model="onlyCurrent"
            size="small"
            :disabled="!currentDiagnosisId"
            active-text="仅当前 Diagnosis"
            @change="loadLedger"
          />
          <el-button type="primary" plain @click="openReplayDrawer">回放一条历史样本</el-button>
          <el-button plain @click="openCaptureDrawer">采集样本</el-button>
          <el-button plain @click="openMetricsDrawer">查看指标</el-button>
        </div>
      </div>

      <div v-loading="loading" class="ledger-body">
        <el-table
          v-if="ledger?.samples.length"
          :data="ledger.samples"
          row-key="sampleId"
          height="100%"
          :row-class-name="sampleRowClassName"
          @row-click="selectSample"
        >
          <el-table-column label="来源" width="120">
            <template #default="{ row }">
              <el-tag
                size="small"
                effect="plain"
                :type="row.sourcePlatform === 'GUANCE' ? 'success' : 'info'"
              >{{ evaluationSourceLabel(row.sourcePlatform) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="系统 / 服务" min-width="160">
            <template #default="{ row }">
              <div class="cell-stack">
                <strong>{{ row.system }} / {{ row.service }}</strong>
                <small>Diagnosis {{ row.diagnosisId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="场景键" min-width="140">
            <template #default="{ row }">
              <code class="scenario-key">{{ row.scenarioKey }}</code>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag
                size="small"
                effect="plain"
                :type="row.referenceStatus === 'READY_FOR_EVALUATION' ? 'primary' : 'warning'"
              >{{ evaluationReferenceStatusLabel(row.referenceStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="取证摘要" min-width="220">
            <template #default="{ row }">
              <span class="muted">
                {{ stageLabel(row.evidence.stage) }} ·
                取得 {{ row.evidence.traceEntries }} 条关联日志
              </span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="168">
            <template #default="{ row }">
              <span class="mono muted">{{ shortTime(row.capturedAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="当前还没有评估样本。先从一张真实排障单采集脱敏证据；历史回放只验证链路，不替代真实效果样本。" :image-size="64" />
      </div>

      <el-drawer
        :model-value="drawerOpen"
        :size="'var(--mc-ts-drawer-width)'"
        destroy-on-close
        class="evaluation-detail-drawer"
        :title="drawerTitle"
        @update:model-value="onDrawerOpenChange"
      >
        <div v-if="drawerPanel === 'replay'" class="drawer-panel">
          <el-button text type="primary" class="back-link" @click="backToDetailOrClose">← 返回</el-button>
          <p class="panel-lead">
            只验证取证链路；不会写入评估台账。要进列表请改用「采集样本」。
          </p>
          <SynthesisPreviewDialog embedded />
        </div>

        <div v-else-if="drawerPanel === 'capture'" class="drawer-panel">
          <el-button text type="primary" class="back-link" @click="backToDetailOrClose">← 返回</el-button>
          <p class="panel-lead">
            每次都重新走服务端只读取证；输入没变会复用已有样本，变了会新增版本，不会覆盖旧参考解。
          </p>
          <div v-if="displayCaptureContext" class="scope-chip">
            {{ displayCaptureContext.system }} / {{ displayCaptureContext.service }}
          </div>

          <section v-if="captureContext" class="capture-block">
            <h3>观测云样本</h3>
            <div class="capture-form">
              <label>
                <span>场景键</span>
                <el-input v-model="captureForm.scenarioKey" placeholder="message_send_failed" />
              </label>
              <label>
                <span>搜索键</span>
                <el-input :model-value="captureContext.searchTerm" disabled />
              </label>
              <label>
                <span>时间窗口</span>
                <el-input :model-value="captureContext.window" disabled />
              </label>
            </div>
            <el-button
              type="primary"
              plain
              :loading="captureLoading"
              :disabled="!captureEnabled || !captureFormValid"
              @click="captureSample('GUANCE')"
            >采集观测云样本</el-button>
            <small v-if="!captureEnabled" class="disabled-reason">{{ captureDisabledReason }}</small>
            <small v-else-if="!captureFormValid" class="disabled-reason">场景键须为 2–64 位小写结构化 key。</small>
          </section>

          <section v-if="replayCaptureContext" class="capture-block">
            <h3>回放对照</h3>
            <div class="capture-form">
              <label>
                <span>场景键</span>
                <el-input :model-value="replayCaptureContext.scenarioKey" disabled />
              </label>
              <label>
                <span>搜索键</span>
                <el-input :model-value="replayCaptureContext.searchTerm" disabled />
              </label>
              <label>
                <span>时间窗口</span>
                <el-input :model-value="replayCaptureContext.window" disabled />
              </label>
            </div>
            <el-button
              type="info"
              plain
              :loading="replayCaptureLoading"
              :disabled="!replayCaptureEnabled || !replayCaptureFormValid"
              @click="captureSample('RECORDED_REPLAY')"
            >采集回放对照</el-button>
            <small v-if="!replayCaptureEnabled" class="disabled-reason">{{ replayCaptureDisabledReason }}</small>
            <small v-else-if="!replayCaptureFormValid" class="disabled-reason">服务端回放目标不是合法场景键。</small>
          </section>

          <el-empty
            v-if="!captureContext && !replayCaptureContext"
            description="先打开一条 Diagnosis，并完成可用的取证预览后再采集"
            :image-size="56"
          />
        </div>

        <div v-else-if="drawerPanel === 'metrics'" class="drawer-panel">
          <el-button text type="primary" class="back-link" @click="backToDetailOrClose">← 返回</el-button>
          <p class="panel-lead">
            时延与基线只是描述性统计，不代表验收通过。观测云与回放样本分开算，不混在一起。
          </p>

          <section v-if="ledger" class="metrics-block">
            <div class="section-title">
              <span>分来源应用侧时延</span>
              <el-tag size="small" type="info" effect="plain">{{ ledger.summary.timingMeasuredSamples }} 条完整计时</el-tag>
            </div>
            <div class="metric-list">
              <article v-for="card in latencyCards" :key="card.key">
                <header><b>{{ card.source }}</b><small>{{ card.sampleCount }} 条可测</small></header>
                <dl>
                  <div><dt>证据源往返</dt><dd>{{ card.evidence }}</dd></div>
                  <div><dt>确定性压缩</dt><dd>{{ card.compression }}</dd></div>
                  <div><dt>端到端预览</dt><dd>{{ card.total }}</dd></div>
                </dl>
              </article>
            </div>
          </section>

          <section v-if="northStar" class="metrics-block north-star-comparison">
            <div class="section-title">
              <span>试点是否真的省时间</span>
              <el-tag size="small" type="info" effect="plain">
                {{ northStar.withHumanBaseline }} / {{ northStar.sampleCount }} 条有人工基线
              </el-tag>
            </div>
            <p class="metric-explanation">
              这里只统计真实 Guance、非演练样本；人工实测、人工估算和影子机器耗时分开呈现，
              不直接相减，也不省略人的复核成本。
            </p>
            <div class="metric-list north-star-list">
              <article v-for="card in northStarCards" :key="card.key">
                <header><b>{{ card.label }}</b><small>{{ card.count }} 条</small></header>
                <dl>
                  <div><dt>中位数</dt><dd>{{ card.p50 }}</dd></div>
                  <div><dt>较慢样本</dt><dd>{{ card.p95 }}</dd></div>
                </dl>
                <p>{{ card.note }}</p>
              </article>
            </div>
            <ul class="metric-caveats">
              <li v-for="caveat in northStar.caveats" :key="caveat">{{ caveat }}</li>
            </ul>
          </section>

          <section v-if="baselineLedger" class="metrics-block">
            <div class="section-title">
              <span>单模型基线摘要</span>
              <el-tag size="small" type="info" effect="plain">{{ baselineLedger.summary.total }} 次运行</el-tag>
            </div>
            <div class="metric-list">
              <article v-for="card in baselineCards" :key="card.key">
                <header><b>{{ card.source }} · {{ card.cohort }}</b><small>{{ card.runCount }} 次</small></header>
                <p>{{ card.evidenceMode }} · {{ card.classifications }}</p>
                <dl>
                  <div><dt>模型调用</dt><dd>{{ card.modelLatency }}</dd></div>
                  <div><dt>证据 + 模型</dt><dd>{{ card.composedLatency }}</dd></div>
                  <div><dt>Token</dt><dd>{{ card.tokens }}</dd></div>
                  <div><dt>系统置信度</dt><dd>{{ card.systemConfidence }}</dd></div>
                </dl>
              </article>
            </div>
          </section>
        </div>

        <div v-else-if="drawerPanel === 'reference' && referenceSample" class="drawer-panel">
          <el-button text type="primary" class="back-link" @click="drawerPanel = 'detail'">← 回到样本详情</el-button>
          <p class="panel-lead">
            请把人工认可的判断步骤写成可检查的步骤标识。最终结果、恢复验证和关闭时间由服务端读取，浏览器不能改。
          </p>
          <div class="reference-form">
            <label class="disposition-field">
              <span>期望模型行为</span>
              <el-select v-model="referenceForm.expectedDisposition" placeholder="选择期望行为">
                <el-option label="生成结构化排障草案" value="DRAFT" />
                <el-option label="证据不足时安全拒答" value="ABSTAIN" />
              </el-select>
            </label>
            <section
              v-if="sampleCountsTowardEffect(referenceSample)"
              class="human-baseline-form"
            >
              <header>
                <div>
                  <b>记录原来人工定位要多久</b>
                  <small>不填仍可评估“准不准”，但不能据此宣称“省时间”。标准答案冻结后不能补填。</small>
                </div>
                <el-switch v-model="referenceForm.includeHumanBaseline" />
              </header>
              <div v-if="referenceForm.includeHumanBaseline" class="human-baseline-fields">
                <label>
                  <span>从收到告警到定位问题（分钟）</span>
                  <el-input-number
                    v-model="referenceForm.minutesToLocate"
                    :min="1"
                    :max="43200"
                    controls-position="right"
                  />
                </label>
                <label>
                  <span>这个时间从哪里来</span>
                  <el-select v-model="referenceForm.baselineBasis">
                    <el-option label="工单 / 群聊时间戳（实测）" value="MEASURED" />
                    <el-option label="处置人回忆（估算）" value="ESTIMATED" />
                  </el-select>
                </label>
                <label class="baseline-note-field">
                  <span>依据说明（不要粘贴原始日志）</span>
                  <el-input
                    v-model="referenceForm.baselineNote"
                    maxlength="500"
                    show-word-limit
                    placeholder="例如：工单创建 17:12，开发首次定位 17:46"
                  />
                </label>
              </div>
            </section>
            <section v-else class="human-baseline-excluded">
              <b>本样本只验证“准不准”</b>
              <p>历史回放和演练样本不登记人工耗时，也不会进入真实效果对照。</p>
            </section>
            <label>
              <span>标准答案必须包含哪些步骤（按顺序，每行一个步骤标识）</span>
              <el-input
                v-model="referenceForm.required"
                type="textarea"
                :rows="5"
                placeholder="locate_failed_request&#10;trace_ps_id&#10;verify_recovery"
              />
            </label>
            <label>
              <span>明确禁止哪些步骤（每行一个步骤标识）</span>
              <el-input
                v-model="referenceForm.forbidden"
                type="textarea"
                :rows="5"
                placeholder="restart_production"
              />
            </label>
          </div>
          <p v-if="referenceError" class="reference-error">{{ referenceError }}</p>
          <div class="drawer-actions">
            <el-button
              type="primary"
              :loading="referenceLoading"
              :disabled="Boolean(referenceError)"
              @click="finalizeReference"
            >保存并冻结人工标准答案</el-button>
          </div>
        </div>

        <div v-else-if="selectedSample" class="drawer-panel">
          <template v-for="sample in [selectedSample]" :key="sample.sampleId">
          <header class="detail-head">
            <div>
              <span class="eyebrow">采集 r{{ sample.captureRevision }} · v{{ sample.version }}</span>
              <h2>{{ sample.scenarioKey }}</h2>
              <p>{{ sample.system }} / {{ sample.service }}</p>
            </div>
            <el-tag
              size="small"
              effect="plain"
              :type="sample.referenceStatus === 'READY_FOR_EVALUATION' ? 'primary' : 'warning'"
            >{{ evaluationReferenceStatusLabel(sample.referenceStatus) }}</el-tag>
          </header>

          <dl class="meta-grid">
            <div><dt>来源</dt><dd>{{ evaluationSourceLabel(sample.sourcePlatform) }}</dd></div>
            <div><dt>Diagnosis</dt><dd class="mono">{{ sample.diagnosisId }}</dd></div>
            <div><dt>取证阶段</dt><dd>{{ stageLabel(sample.evidence.stage) }}</dd></div>
            <div><dt>关联日志</dt><dd>{{ sample.evidence.traceEntries }} 条关联日志</dd></div>
            <div v-if="sample.expectedDisposition">
              <dt>期望行为</dt>
              <dd>{{ evaluationExpectedDispositionLabel(sample.expectedDisposition) }}</dd>
            </div>
            <div v-if="sample.humanBaseline">
              <dt>人工定位耗时</dt>
              <dd>{{ evaluationHumanBaselineLabel(sample.humanBaseline) }}</dd>
            </div>
            <div><dt>采集时间</dt><dd class="mono">{{ shortTime(sample.capturedAt) }}</dd></div>
          </dl>

          <div v-if="sampleContrastNarrative(sample)" class="contrast-box">
            <strong>{{ sampleContrastNarrative(sample)?.summary }}</strong>
            <small>{{ sampleContrastNarrative(sample)?.interpretation }}</small>
          </div>

          <p v-if="sample.outcome" class="outcome-line">
            权威结果 {{ sample.outcome.outcome }} · {{ sample.outcome.summary }}
          </p>

          <template v-if="selectedBaselineRun">
            <div class="section-title"><span>基线结果</span></div>
            <div class="baseline-result">
              <el-tag
                size="small"
                :type="baselineClassificationTagType(selectedBaselineRun.quality.classification)"
              >{{ baselineClassificationLabel(selectedBaselineRun.quality.classification) }}</el-tag>
              <small>{{ baselineStatusLabel(selectedBaselineRun.status) }}</small>
              <small>
                {{ selectedBaselineRun.model.provider }} / {{ selectedBaselineRun.model.modelName }} ·
                {{ selectedBaselineRun.composedTotalDurationMs }} ms
              </small>
            </div>
          </template>

          <div class="drawer-actions">
            <el-button
              v-if="sample.diagnosisId !== currentDiagnosisId"
              @click="openDiagnosis(sample.diagnosisId)"
            >打开 Diagnosis</el-button>
            <el-button
              v-else-if="sample.referenceStatus === 'EVIDENCE_CAPTURED'"
              type="primary"
              plain
              :disabled="currentDiagnosisStatus !== 'CLOSED'"
              @click="openReference(sample)"
            >{{ currentDiagnosisStatus === 'CLOSED' ? '填写人工标准答案' : '登记结果并关闭后填写' }}</el-button>
            <el-button
              v-if="sample.referenceStatus === 'READY_FOR_EVALUATION' && baselineRunnable(sample)"
              type="primary"
              plain
              :loading="baselineRunningSampleId === sample.sampleId"
              @click="runBaseline(sample)"
            >{{ selectedBaselineRun ? '运行 / 读取当前模型版本' : '运行基线核对' }}</el-button>
            <span
              v-else-if="sample.referenceStatus === 'READY_FOR_EVALUATION'"
              class="immutable-mark"
            >{{ baselineUnavailableReason(sample) }}</span>
          </div>
          <p v-if="sample.referenceStatus === 'READY_FOR_EVALUATION'" class="immutable-mark">
            人工标准答案已冻结 · v{{ sample.version }}
          </p>
          </template>
        </div>

        <div v-else class="drawer-empty">
          <strong>选择一条样本查看详情</strong>
          <p>也可先采集样本，或查看时延与基线摘要。</p>
        </div>
      </el-drawer>
    </div>
  </CapabilityWorkspaceShell>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { vLoading } from 'element-plus/es/components/loading/index'
import CapabilityWorkspaceShell from './CapabilityWorkspaceShell.vue'
import EvaluationPilotPlanPanel from './EvaluationPilotPlanPanel.vue'
import SynthesisPreviewDialog from './SynthesisPreviewDialog.vue'
import {
  troubleshootingApi,
  type BaselineClassification,
  type BaselineEvaluationLedger,
  type BaselineEvaluationRun,
  type DiagnosisSummary,
  type DiagnosisStatus,
  type EvidenceEvaluationSample,
  type EvidenceEvaluationSampleLedger,
  type EvaluationExpectedDisposition,
  type EvaluationHumanBaselineBasis,
  type EvaluationNorthStarComparison,
  type TroubleshootingPilotPlan,
} from '@/api'
import {
  type EvaluationSampleCaptureContext,
  baselineClassificationLabel,
  baselineStatusLabel,
  evaluationBaselineCards,
  evaluationExpectedDispositionLabel,
  evaluationHumanBaselineLabel,
  evaluationLatencyCards,
  evaluationNorthStarCards,
  evaluationReferenceStatusLabel,
  evaluationSampleProgress,
  evaluationSourceCaptureContext,
  evaluationSourceLabel,
  parseEvaluationIntentKeys,
} from './evaluationSamples'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'
import { evidenceComparisonNarrative } from './evidencePlainLanguage'
import {
  buildEvaluationPilotQueue,
  matchesPilotScope,
  pilotPlanReady,
} from './evaluationPilot'

type DrawerPanel = 'detail' | 'reference' | 'capture' | 'metrics' | 'replay'

const props = withDefaults(defineProps<{
  currentDiagnosisId?: string | null
  currentDiagnosisStatus?: DiagnosisStatus | null
  currentDiagnosisRehearsal?: boolean
  captureContext?: EvaluationSampleCaptureContext | null
  replayCaptureContext?: EvaluationSampleCaptureContext | null
  captureEnabled?: boolean
  captureDisabledReason?: string
  replayCaptureEnabled?: boolean
  replayCaptureDisabledReason?: string
  startPilotSetup?: boolean
}>(), {
  currentDiagnosisId: null,
  currentDiagnosisStatus: null,
  currentDiagnosisRehearsal: false,
  captureContext: null,
  replayCaptureContext: null,
  captureEnabled: false,
  captureDisabledReason: '先完成一次可用的取证预览，再采集历史样本。',
  replayCaptureEnabled: false,
  replayCaptureDisabledReason: '当前 Diagnosis 不在可管理的回放范围。',
  startPilotSetup: false,
})

const emit = defineEmits<{
  back: []
  'open-diagnosis': [diagnosisId: string]
  'open-validation': []
  captured: [sample: EvidenceEvaluationSample]
}>()

const ledger = ref<EvidenceEvaluationSampleLedger | null>(null)
const baselineLedger = ref<BaselineEvaluationLedger | null>(null)
const northStar = ref<EvaluationNorthStarComparison | null>(null)
const pilotDiagnoses = ref<DiagnosisSummary[]>([])
const pilotLedger = ref<EvidenceEvaluationSampleLedger | null>(null)
const pilotBaselineLedger = ref<BaselineEvaluationLedger | null>(null)
const pilotPlan = ref<TroubleshootingPilotPlan | null>(null)
const loading = ref(false)
const captureLoading = ref(false)
const replayCaptureLoading = ref(false)
const referenceLoading = ref(false)
const baselineRunningSampleId = ref<string | null>(null)
const onlyCurrent = ref(false)
const selectedSampleId = ref<string | null>(null)
const drawerPanel = ref<DrawerPanel>('detail')
const referenceSample = ref<EvidenceEvaluationSample | null>(null)
const captureForm = reactive({ scenarioKey: '' })
const referenceForm = reactive<{
  required: string
  forbidden: string
  expectedDisposition: EvaluationExpectedDisposition
  includeHumanBaseline: boolean
  minutesToLocate: number
  baselineBasis: EvaluationHumanBaselineBasis
  baselineNote: string
}>({
  required: '',
  forbidden: '',
  expectedDisposition: 'DRAFT',
  includeHumanBaseline: false,
  minutesToLocate: 30,
  baselineBasis: 'MEASURED',
  baselineNote: '',
})

const progress = computed(() => ledger.value
  ? evaluationSampleProgress(ledger.value.summary)
  : { label: '0 / 20 条可评估样本', percent: 0, note: '' })
const latencyCards = computed(() => ledger.value
  ? evaluationLatencyCards(ledger.value.summary)
  : [])
const baselineCards = computed(() => baselineLedger.value
  ? evaluationBaselineCards(baselineLedger.value.summary)
  : [])
const northStarCards = computed(() => northStar.value
  ? evaluationNorthStarCards(northStar.value)
  : [])
const pilotConfigured = computed(() => pilotPlanReady(pilotPlan.value))
const pilotQueue = computed(() => buildEvaluationPilotQueue(
  pilotDiagnoses.value,
  pilotLedger.value?.samples || [],
  pilotBaselineLedger.value?.runs || [],
  pilotPlan.value,
))
const pilotVisibleRows = computed(() => pilotQueue.value.slice(0, 6))
const pilotScopedDiagnoses = computed(() => pilotDiagnoses.value.filter(
  diagnosis => !diagnosis.rehearsal && matchesPilotScope(diagnosis, pilotPlan.value),
))
const pilotSummary = computed(() => ({
  formal: pilotScopedDiagnoses.value.length,
  closed: pilotScopedDiagnoses.value.filter(diagnosis => diagnosis.status === 'CLOSED').length,
  ready: pilotQueue.value.filter(row => row.stage === 'READY_FOR_REVIEW').length,
}))
const currentSamples = computed(() => ledger.value?.samples.filter(
  sample => sample.diagnosisId === props.currentDiagnosisId,
) || [])
const currentPrimarySample = computed(() => currentSamples.value.find(sampleCountsTowardEffect)
  || currentSamples.value.find(sample => sample.sourcePlatform === 'GUANCE')
  || currentSamples.value[0]
  || null)
const currentPilotSteps = computed(() => {
  const sample = currentPrimarySample.value
  const closed = props.currentDiagnosisStatus === 'CLOSED'
  const ready = sample?.referenceStatus === 'READY_FOR_EVALUATION'
  const realEffectSample = sample ? sampleCountsTowardEffect(sample) : false
  return [
    {
      key: 'DIAGNOSIS', index: 1, label: '选定排障单',
      detail: '系统、服务、取证时间和最终处置都绑定在同一张单上。',
      state: 'done', stateLabel: '已选定',
    },
    {
      key: 'CAPTURE', index: 2, label: '保存脱敏证据样本',
      detail: sample
        ? realEffectSample
          ? '已保存真实 Guance、非演练样本，可用于后续效果对照。'
          : `已保存 ${evaluationSourceLabel(sample.sourcePlatform)} 样本，只用于准确性回归。`
        : '重新执行一次服务端只读取证，不保存原始日志。',
      state: sample ? 'done' : 'current', stateLabel: sample ? '已采集' : '下一步',
    },
    {
      key: 'CLOSE', index: 3, label: '登记真实处置结果',
      detail: closed ? '排障单已关闭，结果和恢复验证由服务端读取。' : '先由负责人确认、完成平台外处置并关闭排障单。',
      state: closed ? 'done' : sample ? 'current' : 'pending', stateLabel: closed ? '已登记' : '待完成',
    },
    {
      key: 'REFERENCE', index: 4, label: '填写人工标准答案与耗时',
      detail: ready
        ? realEffectSample
          ? '标准答案已冻结，可运行基线并进入真实效果统计。'
          : '标准答案已冻结，只进入“准不准”回归，不进入真实耗时对照。'
        : !sample
          ? '先采集真实 Guance 样本；排障单关闭后再填写正确步骤与人工耗时。'
          : realEffectSample
            ? '写明正确步骤；有依据时补充原来人工定位所需时间。'
            : '写明正确步骤；回放和演练样本不登记人工耗时。',
      state: ready ? 'done' : closed && sample ? 'current' : 'pending', stateLabel: ready ? '可评估' : '待完成',
    },
  ]
})
const currentPilotAction = computed(() => {
  const sample = currentPrimarySample.value
  if (!sample) {
    return props.captureEnabled
      ? {
          kind: 'CAPTURE' as const,
          title: '下一步：采集当前排障样本',
          detail: '会重新执行服务端已审核的只读查询；输入不变时复用已有采集版本。',
        }
      : {
          kind: 'VALIDATE' as const,
          title: '下一步：先取得一次可采集的真源预览',
          detail: props.captureDisabledReason,
        }
  }
  if (props.currentDiagnosisStatus !== 'CLOSED') {
    return {
      kind: 'CLOSE' as const,
      title: '下一步：回到详情登记真实结果',
      detail: '先完成复核和平台外处置，再登记是否恢复、实际原因并关闭排障单。',
    }
  }
  if (sample.referenceStatus === 'EVIDENCE_CAPTURED') {
    if (!sampleCountsTowardEffect(sample)) {
      return {
        kind: 'REFERENCE' as const,
        title: '下一步：填写人工标准答案（仅验证准确性）',
        detail: '该样本属于回放或演练，不登记人工耗时，也不会进入真实效果对照。',
      }
    }
    return {
      kind: 'REFERENCE' as const,
      title: '下一步：填写人工确认的标准答案',
      detail: '同时补充原来人工定位耗时；无法确认耗时时可以不填，但不能宣称省时。',
    }
  }
  return {
    kind: 'DETAIL' as const,
    title: sampleCountsTowardEffect(sample) ? '当前样本已进入真实效果评估集' : '当前样本已进入准确性回归集',
    detail: sampleCountsTowardEffect(sample)
      ? '可以运行影子基线并在“查看指标”中核对准确性和耗时；这仍不等于 T8 已通过。'
      : '可以运行影子基线核对准确性，但这条记录不参与真实耗时效果统计。',
  }
})
const captureFormValid = computed(() => {
  const parsed = parseEvaluationIntentKeys(captureForm.scenarioKey)
  return parsed.invalid.length === 0
    && parsed.values.length === 1
    && parsed.values[0] === captureForm.scenarioKey.trim()
})
const replayCaptureFormValid = computed(() => {
  const scenarioKey = props.replayCaptureContext?.scenarioKey || ''
  const parsed = parseEvaluationIntentKeys(scenarioKey)
  return parsed.invalid.length === 0
    && parsed.values.length === 1
    && parsed.values[0] === scenarioKey.trim()
})
const displayCaptureContext = computed(() => props.captureContext || props.replayCaptureContext)
const parsedRequired = computed(() => parseEvaluationIntentKeys(referenceForm.required))
const parsedForbidden = computed(() => parseEvaluationIntentKeys(referenceForm.forbidden))
const referenceError = computed(() => {
  if (!parsedRequired.value.values.length) return '至少填写一个必须步骤 intent key。'
  if (!parsedForbidden.value.values.length) return '至少填写一个禁止步骤 intent key。'
  const invalid = [...parsedRequired.value.invalid, ...parsedForbidden.value.invalid]
  if (invalid.length) return `以下内容不是结构化 intent key：${invalid.join('、')}`
  if (parsedRequired.value.values.length > 20 || parsedForbidden.value.values.length > 20) {
    return '必须步骤和禁止步骤各最多 20 个。'
  }
  if (referenceForm.includeHumanBaseline) {
    if (!Number.isInteger(referenceForm.minutesToLocate)
      || referenceForm.minutesToLocate <= 0
      || referenceForm.minutesToLocate > 43200) {
      return '人工定位耗时必须是 1–43200 之间的整数分钟。'
    }
    if (!referenceForm.baselineNote.trim()) {
      return '请简要说明人工耗时来自哪个工单、群聊时间戳或谁的估算。'
    }
  }
  const overlap = parsedRequired.value.values.find(value => parsedForbidden.value.values.includes(value))
  return overlap ? `intent key 不能同时为必须与禁止：${overlap}` : ''
})

const selectedSample = computed(() => ledger.value?.samples.find(
  sample => sample.sampleId === selectedSampleId.value,
) || null)

const selectedBaselineRun = computed(() => {
  const sample = selectedSample.value
  return sample ? baselineRunFor(sample) : null
})

const drawerOpen = computed(() => Boolean(
  selectedSampleId.value
    || drawerPanel.value === 'capture'
    || drawerPanel.value === 'metrics'
    || drawerPanel.value === 'reference'
    || drawerPanel.value === 'replay',
))

const drawerTitle = computed(() => {
  if (drawerPanel.value === 'replay') return TROUBLESHOOTING_UI_LABELS.historyReplay
  if (drawerPanel.value === 'capture') return '采集样本'
  if (drawerPanel.value === 'metrics') return '时延与基线指标'
  if (drawerPanel.value === 'reference') return '填写人工标准答案'
  if (selectedSample.value) return selectedSample.value.scenarioKey
  return '样本详情'
})

onMounted(() => {
  syncCaptureForm()
  referenceSample.value = null
  onlyCurrent.value = Boolean(props.currentDiagnosisId)
  void loadLedger()
})
watch(() => props.captureContext, syncCaptureForm, { deep: true })

function syncCaptureForm() {
  captureForm.scenarioKey = props.captureContext?.scenarioKey || ''
}

async function loadLedger() {
  loading.value = true
  try {
    const params = {
      diagnosisId: onlyCurrent.value ? props.currentDiagnosisId || undefined : undefined,
      limit: 100,
    }
    const portfolioSampleRequest = onlyCurrent.value
      ? troubleshootingApi.evaluationSamples({ limit: 100 })
      : null
    const portfolioBaselineRequest = onlyCurrent.value
      ? troubleshootingApi.evaluationBaselineRuns({ limit: 100 })
      : null
    const [
      sampleResponse,
      baselineResponse,
      northStarResponse,
      diagnosisResponse,
      portfolioSampleResponse,
      portfolioBaselineResponse,
      pilotPlanResponse,
    ] = await Promise.all([
      troubleshootingApi.evaluationSamples(params),
      troubleshootingApi.evaluationBaselineRuns(params),
      troubleshootingApi.evaluationNorthStar(params),
      troubleshootingApi.list({ limit: 100 }),
      portfolioSampleRequest,
      portfolioBaselineRequest,
      troubleshootingApi.pilotPlan(),
    ])
    ledger.value = sampleResponse.data
    baselineLedger.value = baselineResponse.data
    northStar.value = northStarResponse.data
    pilotDiagnoses.value = diagnosisResponse.data
    pilotLedger.value = portfolioSampleResponse?.data || sampleResponse.data
    pilotBaselineLedger.value = portfolioBaselineResponse?.data || baselineResponse.data
    pilotPlan.value = pilotPlanResponse.data
    if (selectedSampleId.value && !ledger.value?.samples.some(sample => sample.sampleId === selectedSampleId.value)) {
      selectedSampleId.value = null
      if (drawerPanel.value === 'detail' || drawerPanel.value === 'reference') {
        drawerPanel.value = 'detail'
        referenceSample.value = null
      }
    }
  } catch (error) {
    ElMessage.error(`加载试点评估进度失败：${errorText(error)}`)
  } finally {
    loading.value = false
  }
}

function selectSample(sample: EvidenceEvaluationSample) {
  selectedSampleId.value = sample.sampleId
  drawerPanel.value = 'detail'
  referenceSample.value = null
}

function openCaptureDrawer() {
  drawerPanel.value = 'capture'
}

function openReplayDrawer() {
  drawerPanel.value = 'replay'
}

function openMetricsDrawer() {
  drawerPanel.value = 'metrics'
}

function onDrawerOpenChange(open: boolean) {
  if (open) return
  selectedSampleId.value = null
  drawerPanel.value = 'detail'
  referenceSample.value = null
}

function backToDetailOrClose() {
  if (selectedSampleId.value) {
    drawerPanel.value = 'detail'
    return
  }
  onDrawerOpenChange(false)
}

function sampleRowClassName({ row }: { row: EvidenceEvaluationSample }) {
  const classes = []
  if (row.sampleId === selectedSampleId.value) classes.push('selected-row')
  if (row.diagnosisId === props.currentDiagnosisId) classes.push('current-row')
  return classes.join(' ')
}

async function captureSample(source: 'GUANCE' | 'RECORDED_REPLAY') {
  const context = source === 'GUANCE'
    ? props.captureContext
    : props.replayCaptureContext
  const enabled = source === 'GUANCE' ? props.captureEnabled : props.replayCaptureEnabled
  const formValid = source === 'GUANCE' ? captureFormValid.value : replayCaptureFormValid.value
  if (!context || !enabled || !formValid) return
  if (source === 'GUANCE') captureLoading.value = true
  else replayCaptureLoading.value = true
  try {
    const response = source === 'GUANCE'
      ? await troubleshootingApi.captureGuanceEvaluationSample({
          diagnosisId: context.diagnosisId,
          scenarioKey: captureForm.scenarioKey.trim(),
          searchTerm: context.searchTerm,
          window: context.window,
        })
      : await troubleshootingApi.captureRecordedReplayEvaluationSample({
          diagnosisId: context.diagnosisId,
        })
    await loadLedger()
    selectedSampleId.value = response.data.sample.sampleId
    drawerPanel.value = 'detail'
    emit('captured', response.data.sample)
    ElMessage.success(response.data.created
      ? `证据输入发生变化，已写入采集 r${response.data.sample.captureRevision}`
      : `输入未变化，已复用采集 r${response.data.sample.captureRevision}`)
  } catch (error) {
    ElMessage.error(`样本采集失败：${errorText(error)}`)
  } finally {
    if (source === 'GUANCE') captureLoading.value = false
    else replayCaptureLoading.value = false
  }
}

function openReference(sample: EvidenceEvaluationSample) {
  if (sample.diagnosisId !== props.currentDiagnosisId
    || props.currentDiagnosisStatus !== 'CLOSED') return
  selectedSampleId.value = sample.sampleId
  referenceSample.value = sample
  referenceForm.required = ''
  referenceForm.forbidden = ''
  referenceForm.expectedDisposition = 'DRAFT'
  referenceForm.includeHumanBaseline = false
  referenceForm.minutesToLocate = 30
  referenceForm.baselineBasis = 'MEASURED'
  referenceForm.baselineNote = ''
  drawerPanel.value = 'reference'
}

async function finalizeReference() {
  const sample = referenceSample.value
  if (!sample || referenceError.value) return
  referenceLoading.value = true
  try {
    await troubleshootingApi.finalizeEvaluationSampleReference(sample.sampleId, {
      expectedVersion: sample.version,
      requiredStepIntents: parsedRequired.value.values,
      forbiddenStepIntents: parsedForbidden.value.values,
      expectedDisposition: referenceForm.expectedDisposition,
      humanBaseline: referenceForm.includeHumanBaseline
        ? {
            minutesToLocate: referenceForm.minutesToLocate,
            basis: referenceForm.baselineBasis,
            note: referenceForm.baselineNote.trim(),
          }
        : null,
    })
    referenceSample.value = null
    drawerPanel.value = 'detail'
    await loadLedger()
    ElMessage.success('人工标准答案已冻结；权威结果来自已关闭的排障单')
  } catch (error) {
    ElMessage.error(`参考解冻结失败：${errorText(error)}`)
  } finally {
    referenceLoading.value = false
  }
}

function baselineRunFor(sample: EvidenceEvaluationSample): BaselineEvaluationRun | null {
  return baselineLedger.value?.runs.find(run => run.sampleId === sample.sampleId) || null
}

function baselineRunnable(sample: EvidenceEvaluationSample) {
  return baselineUnavailableReason(sample) === ''
}

function baselineCaptureContext(sample: EvidenceEvaluationSample) {
  return evaluationSourceCaptureContext(
    sample.sourcePlatform,
    props.captureContext,
    props.replayCaptureContext,
  )
}

function sampleCountsTowardEffect(sample: EvidenceEvaluationSample) {
  return sample.sourcePlatform === 'GUANCE' && !sample.diagnosisFixtureMode
}

function baselineUnavailableReason(sample: EvidenceEvaluationSample) {
  if (!sample.modelInputHash || !sample.evidenceOccurredAt || !sample.expectedDisposition) {
    return '旧样本需重新采集并冻结人工标准答案'
  }
  if (sample.diagnosisId !== props.currentDiagnosisId) return '打开 Diagnosis 后运行'
  const context = baselineCaptureContext(sample)
  if (!context || context.diagnosisId !== sample.diagnosisId) {
    return '先打开当前 Diagnosis 以恢复查询窗口'
  }
  return ''
}

async function runBaseline(sample: EvidenceEvaluationSample) {
  const context = baselineCaptureContext(sample)
  if (!context || !baselineRunnable(sample)) return
  baselineRunningSampleId.value = sample.sampleId
  try {
    const response = await troubleshootingApi.runEvaluationBaseline(sample.sampleId, {
      expectedSampleVersion: sample.version,
      searchTerm: context.searchTerm,
      window: context.window,
    })
    await loadLedger()
    ElMessage.success(response.data.created
      ? '基线核对已运行并保存结果'
      : '该样本与模型版本已有基线结果，已返回既有记录')
  } catch (error) {
    ElMessage.error(`基线核对失败：${errorText(error)}`)
  } finally {
    baselineRunningSampleId.value = null
  }
}

function baselineClassificationTagType(value: BaselineClassification) {
  if (value === 'HELPFUL') return 'success'
  if (value === 'UNHELPFUL') return 'warning'
  return 'danger'
}

function openDiagnosis(diagnosisId: string) {
  emit('open-diagnosis', diagnosisId)
}

function stageLabel(stage: EvidenceEvaluationSample['evidence']['stage']) {
  return stage === 'FULL_SPINE_OBSERVED'
    ? '失败日志、关联日志和请求对比已齐全'
    : '已取得失败日志和关联日志'
}

function sampleContrastNarrative(sample: EvidenceEvaluationSample) {
  const contrast = sample.evidence.contrast
  if (!contrast.available) return null
  return evidenceComparisonNarrative({
    featureCode: contrast.discriminatingFeature,
    failureRequestCount: contrast.failureSampleCount,
    failureWithFeatureCount: contrast.failureMatchCount,
    normalRequestCount: contrast.successSampleCount,
    normalWithFeatureCount: contrast.successMatchCount,
  })
}

function shortTime(value: string) {
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
</script>

<style scoped>
.evaluation-ledger-workspace {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  min-height: 0;
  color: var(--mc-text-primary);
}

.ledger-alert { margin: 0; }

.pilot-relay {
  display: grid;
  gap: 12px;
  flex: 0 0 auto;
  padding: 16px;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
}

.pilot-relay-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.pilot-relay-head > div:first-child > span {
  color: var(--mc-primary);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: .08em;
}

.pilot-relay-head h2 {
  margin: 3px 0 0;
  color: var(--mc-text-primary);
  font-size: 17px;
}

.pilot-relay-head p,
.pilot-relay-boundary,
.pilot-relay-overflow {
  margin: 5px 0 0;
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.55;
}

.pilot-relay-metrics {
  display: flex;
  align-items: stretch;
  flex: none;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  overflow: hidden;
}

.pilot-relay-metrics div {
  display: grid;
  min-width: 84px;
  padding: 8px 11px;
  text-align: center;
}

.pilot-relay-metrics div + div { border-left: 1px solid var(--mc-border-light); }
.pilot-relay-metrics b { color: var(--mc-text-primary); font-size: 15px; }
.pilot-relay-metrics span { margin-top: 2px; color: var(--mc-text-tertiary); font-size: 9px; }

.pilot-relay-list {
  display: grid;
  border-top: 1px solid var(--mc-border-light);
}

.pilot-relay-row {
  display: grid;
  grid-template-columns: 126px minmax(170px, .8fr) 130px minmax(260px, 1.5fr) auto;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 11px 4px;
  border: 0;
  border-bottom: 1px solid var(--mc-border-light);
  color: inherit;
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.pilot-relay-row:hover,
.pilot-relay-row.active {
  background: color-mix(in srgb, var(--mc-primary) 6%, transparent);
}

.pilot-relay-row.active { box-shadow: inset 3px 0 0 var(--mc-primary); }
.pilot-relay-row > span { min-width: 0; }
.pilot-relay-stage { display: flex; align-items: center; gap: 7px; }
.pilot-relay-stage i {
  width: 8px;
  height: 8px;
  flex: none;
  border-radius: 50%;
  background: var(--mc-warning);
}
.stage-ready_for_review .pilot-relay-stage i { background: var(--mc-success); }
.stage-baseline_blocked .pilot-relay-stage i { background: var(--mc-danger); }
.stage-accuracy_only .pilot-relay-stage i { background: var(--mc-text-tertiary); }
.pilot-relay-stage b,
.pilot-relay-case strong,
.pilot-relay-owner b,
.pilot-relay-action b { color: var(--mc-text-primary); font-size: 11px; }
.pilot-relay-case,
.pilot-relay-owner,
.pilot-relay-action { display: grid; gap: 3px; }
.pilot-relay-case small,
.pilot-relay-owner small,
.pilot-relay-action small { color: var(--mc-text-tertiary); font-size: 9px; }
.pilot-relay-case small {
  overflow: hidden;
  font-family: var(--mc-mono, monospace);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pilot-relay-action b {
  color: var(--mc-text-secondary);
  font-weight: 500;
  line-height: 1.45;
}
.pilot-relay-open {
  color: var(--mc-primary);
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}
.pilot-relay-overflow { padding: 8px 4px 0; }

.pilot-relay-empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px;
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-muted);
}

.pilot-relay-empty div { display: grid; gap: 4px; }
.pilot-relay-empty b { font-size: 12px; }
.pilot-relay-empty span { color: var(--mc-text-secondary); font-size: 11px; }
.pilot-relay-boundary { margin: 0; color: var(--mc-text-tertiary); }

.current-pilot-card {
  display: grid;
  gap: 13px;
  flex: 0 0 auto;
  padding: 15px 16px;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
}

.current-pilot-card > header,
.pilot-next-action,
.human-baseline-form > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.current-pilot-card > header span,
.current-pilot-card > header strong { display: block; }
.current-pilot-card > header span {
  color: var(--mc-primary);
  font-size: 11px;
  font-weight: 750;
}
.current-pilot-card > header strong {
  margin-top: 4px;
  color: var(--mc-text-primary);
  font: 600 12px var(--mc-mono, monospace);
}

.pilot-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.pilot-steps li {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 4px 8px;
  padding: 10px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg);
}

.pilot-steps li > i {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-muted);
  font-size: 10px;
  font-style: normal;
  font-weight: 800;
}
.pilot-steps li > div b,
.pilot-steps li > div small { display: block; }
.pilot-steps li > div b { color: var(--mc-text-primary); font-size: 11px; }
.pilot-steps li > div small { margin-top: 3px; color: var(--mc-text-secondary); font-size: 10px; line-height: 1.45; }
.pilot-steps li > span { grid-column: 2; color: var(--mc-text-tertiary); font-size: 10px; }
.pilot-steps li.done > i { color: var(--mc-status-success-text); background: var(--mc-status-success-bg); }
.pilot-steps li.done > span { color: var(--mc-status-success-text); }
.pilot-steps li.current { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.pilot-steps li.current > i { color: var(--mc-text-inverse); background: var(--mc-primary); }
.pilot-steps li.current > span { color: var(--mc-primary); font-weight: 700; }
.pilot-steps li.pending { border-style: dashed; }

.pilot-next-action {
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--mc-border-light);
}
.pilot-next-action b { display: block; font-size: 12px; }
.pilot-next-action p { margin: 3px 0 0; color: var(--mc-text-secondary); font-size: 11px; line-height: 1.55; }
.pilot-next-action small { display: block; margin-top: 4px; color: var(--mc-warning); font-size: 10px; }
.pilot-next-action .el-button { flex: none; }

.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex: 0 0 auto;
  padding: 12px 14px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
}

.status-metrics {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  min-width: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.progress-note {
  flex-basis: 100%;
  color: var(--mc-text-tertiary);
  font-size: 10px;
  line-height: 1.5;
}

.progress-inline {
  display: grid;
  grid-template-columns: auto minmax(120px, 180px);
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.progress-inline b {
  color: var(--mc-text-primary);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}

.status-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  justify-content: flex-end;
}

.ledger-body {
  flex: 1;
  min-height: 280px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  overflow: hidden;
  background: var(--mc-bg-elevated);
}

.cell-stack {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.cell-stack strong {
  font-size: 12px;
  color: var(--mc-text-primary);
}

.cell-stack small,
.muted {
  color: var(--mc-text-secondary);
  font-size: 11px;
}

.mono { font-family: var(--mc-mono, monospace); }

.scenario-key {
  color: var(--mc-primary);
  font: 600 11px var(--mc-mono, monospace);
}

:deep(.el-table) {
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--mc-primary) 7%, var(--mc-bg));
}

:deep(.el-table__row) { cursor: pointer; }

:deep(.selected-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--mc-primary) 12%, var(--mc-bg)) !important;
}

:deep(.selected-row > td:first-child) {
  box-shadow: inset 3px 0 0 var(--mc-primary);
}

:deep(.current-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--mc-accent, var(--mc-primary)) 5%, var(--mc-bg));
}

.drawer-panel { display: grid; gap: 14px; padding-bottom: 8px; }
.back-link { justify-self: start; margin: -4px 0 0; }
.panel-lead {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.scope-chip {
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
  font: 600 11px var(--mc-mono, monospace);
}

.capture-block,
.metrics-block {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-muted);
}

.capture-block h3,
.section-title span {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.capture-form {
  display: grid;
  gap: 10px;
}

.capture-form label > span,
.reference-form label > span {
  display: block;
  margin-bottom: 5px;
  color: var(--mc-text-secondary);
  font-size: 11px;
  font-weight: 650;
}

.disabled-reason {
  color: var(--mc-warning);
  font-size: 11px;
  line-height: 1.5;
}

.metric-list {
  display: grid;
  gap: 10px;
}

.metric-list article {
  padding: 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
}

.metric-list header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.metric-list header b { font-size: 12px; }
.metric-list header small,
.metric-list p {
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.metric-list p { margin: 8px 0; }
.metric-explanation { margin: 0; color: var(--mc-text-secondary); font-size: 11px; line-height: 1.55; }
.north-star-list { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.metric-caveats { display: grid; gap: 4px; margin: 0; padding-left: 17px; color: var(--mc-text-secondary); font-size: 10px; line-height: 1.5; }

.metric-list dl {
  display: grid;
  gap: 7px;
  margin: 0;
}

.metric-list dl > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.metric-list dt { color: var(--mc-text-secondary); font-size: 11px; }
.metric-list dd {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 11px;
  font-weight: 650;
  text-align: right;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.eyebrow {
  color: var(--mc-text-secondary);
  font: 10px var(--mc-mono, monospace);
}

.detail-head h2 {
  margin: 4px 0;
  font-size: 18px;
  line-height: 1.35;
}

.detail-head p {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: 0;
  border-top: 1px solid var(--mc-border-light);
}

.meta-grid > div {
  padding: 10px 0;
  border-bottom: 1px solid var(--mc-border-light);
}

.meta-grid > div:nth-child(odd) { padding-right: 12px; }
.meta-grid dt { color: var(--mc-text-secondary); font-size: 10px; }
.meta-grid dd { margin: 4px 0 0; font-size: 12px; overflow-wrap: anywhere; }

.contrast-box {
  padding: 10px 12px;
  border-left: 2px solid var(--mc-success);
  border-radius: 6px;
  background: color-mix(in srgb, var(--mc-success) 8%, var(--mc-bg));
}

.contrast-box strong,
.contrast-box small { display: block; }
.contrast-box strong { font-size: 12px; line-height: 1.5; }
.contrast-box small {
  margin-top: 4px;
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.outcome-line {
  margin: 0;
  color: var(--mc-success);
  font-size: 12px;
  line-height: 1.5;
}

.baseline-result {
  display: grid;
  gap: 4px;
  justify-items: start;
}

.baseline-result small {
  color: var(--mc-text-secondary);
  font-size: 11px;
}

.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.immutable-mark {
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.reference-form {
  display: grid;
  gap: 12px;
}

.human-baseline-form {
  display: grid;
  gap: 12px;
  padding: 13px;
  border: 1px solid var(--mc-border-light);
  border-radius: 9px;
  background: var(--mc-bg-muted);
}
.human-baseline-excluded {
  padding: 13px;
  border: 1px dashed var(--mc-border);
  border-radius: 9px;
  background: var(--mc-bg-muted);
}
.human-baseline-excluded b { display: block; font-size: 12px; }
.human-baseline-excluded p { margin: 4px 0 0; color: var(--mc-text-secondary); font-size: 10px; line-height: 1.5; }
.human-baseline-form header b,
.human-baseline-form header small { display: block; }
.human-baseline-form header b { font-size: 12px; }
.human-baseline-form header small { margin-top: 4px; color: var(--mc-text-secondary); font-size: 10px; line-height: 1.5; }
.human-baseline-fields { display: grid; grid-template-columns: minmax(0, .75fr) minmax(0, 1.25fr); gap: 10px; }
.human-baseline-fields :deep(.el-input-number),
.human-baseline-fields :deep(.el-select) { width: 100%; }
.baseline-note-field { grid-column: 1 / -1; }

.disposition-field :deep(.el-select) { width: 100%; }

.reference-error {
  margin: 0;
  color: var(--mc-danger);
  font-size: 12px;
}

.drawer-empty {
  display: grid;
  place-content: center;
  gap: 6px;
  min-height: 240px;
  text-align: center;
  color: var(--mc-text-secondary);
}

.drawer-empty strong { color: var(--mc-text-primary); font-size: 13px; }
.drawer-empty p { margin: 0; font-size: 12px; line-height: 1.5; }

@media (max-width: 900px) {
  .pilot-relay-head {
    align-items: stretch;
    flex-direction: column;
  }

  .pilot-relay-metrics { width: 100%; }
  .pilot-relay-metrics div { flex: 1; min-width: 0; }
  .pilot-relay-row {
    grid-template-columns: 120px minmax(0, 1fr);
    align-items: start;
  }

  .pilot-relay-open { grid-column: 2; }

  .status-bar {
    align-items: flex-start;
    flex-direction: column;
  }

  .status-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .progress-inline {
    grid-template-columns: 1fr;
    width: 100%;
  }

  .meta-grid { grid-template-columns: 1fr; }
  .meta-grid > div:nth-child(odd) { padding-right: 0; }
  .pilot-steps,
  .north-star-list { grid-template-columns: 1fr 1fr; }
}

@media (max-width: 620px) {
  .pilot-relay { padding: 13px; }
  .pilot-relay-row { grid-template-columns: 1fr; }
  .pilot-relay-stage,
  .pilot-relay-case,
  .pilot-relay-owner,
  .pilot-relay-action,
  .pilot-relay-open { grid-column: 1; }
  .pilot-relay-empty { align-items: stretch; flex-direction: column; }
  .pilot-relay-empty .el-button { width: 100%; }
  .pilot-steps,
  .north-star-list,
  .human-baseline-fields { grid-template-columns: 1fr; }
  .pilot-next-action { align-items: stretch; flex-direction: column; }
  .pilot-next-action .el-button { width: 100%; }
  .baseline-note-field { grid-column: auto; }
}
</style>
