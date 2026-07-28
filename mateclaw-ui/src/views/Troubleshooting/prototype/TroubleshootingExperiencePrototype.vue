<!--
  PROTOTYPE — throwaway after review.

  Scope decision (2026-07-28): concentrate on the two audiences that carry the
  product — 服务经理 (business summary) and 开发 (evidence desk). The WeCom flow
  is parked with P3, kept only as a reminder of where the real entry point is.

  What still varies is what actually changes the answer:
    view       how the developer projection is reached (inline fold vs split pane)
    outcome    what the system ends up being able to say (4 endings)
    authority  why this investigation path was chosen (3 trust levels)

  A layout that reads well when the answer is found still has to survive
  "I could not find out" — the ending this system produces most often.

  Dev-only route: /prototype/troubleshooting?view=..&outcome=..&authority=..
-->
<template>
  <div class="proto-page">
    <header class="proto-topbar">
      <div>
        <span class="eyebrow">MateClaw · 智能排障体验原型</span>
        <h1>{{ scene.title }}</h1>
      </div>
      <div class="top-meta">
        <span class="mode">{{ authorityView.modeLabel }}</span>
        <span class="authority" :class="authority.toLowerCase()">{{ authority }}</span>
        <span class="fixture">Recorded Replay · 非真实观测云</span>
      </div>
    </header>

    <nav class="axes" aria-label="演示状态切换">
      <div class="axis">
        <span class="axis-label">开发证据</span>
        <button
          v-for="item in VIEWS"
          :key="item.key"
          type="button"
          :class="{ on: item.key === view, parked: item.parked }"
          @click="setQuery('view', item.key)"
        >{{ item.short }}</button>
      </div>
      <div class="axis">
        <span class="axis-label">结局</span>
        <button
          v-for="item in OUTCOMES"
          :key="item.key"
          type="button"
          :class="{ on: item.key === outcome }"
          @click="setQuery('outcome', item.key)"
        >{{ item.short }}</button>
      </div>
      <div class="axis">
        <span class="axis-label">路由可信</span>
        <button
          v-for="item in AUTHORITIES"
          :key="item.key"
          type="button"
          :class="{ on: item.key === authority }"
          @click="setQuery('authority', item.key)"
        >{{ item.short }}</button>
      </div>
      <p class="axis-hint">{{ authorityView.hint }}</p>
    </nav>

    <!-- SPLIT 才需要显式切页；INLINE 下开发证据就在同一页折叠着 -->
    <nav v-if="view === 'SPLIT'" class="panes" aria-label="投影切换">
      <button type="button" :class="{ on: pane === 'BUSINESS' }" @click="pane = 'BUSINESS'">
        业务摘要 · 服务经理
      </button>
      <button type="button" :class="{ on: pane === 'DEV' }" @click="pane = 'DEV'">
        开发证据台
      </button>
    </nav>

    <section v-if="view !== 'WECOM'" class="variant">
      <!-- ========== 业务摘要投影（服务经理默认看到的全部） ========== -->
      <template v-if="view === 'INLINE' || pane === 'BUSINESS'">
        <div class="a-status">
          <div>
            <div class="conclusion-row">
              <span class="ctype" :class="scene.conclusionType">{{ CONCLUSION_LABEL[scene.conclusionType] }}</span>
              <span class="section-label">给服务经理的结论</span>
            </div>
            <h2>{{ scene.headline }}</h2>
            <p>{{ scene.narrative }}</p>
          </div>
          <div class="confidence-block">
            <span>可信等级</span>
            <strong :class="confidence.toLowerCase()">{{ confidence }}</strong>
            <small>{{ authorityView.ceilingNote }}</small>
          </div>
        </div>

        <div class="summary-strip">
          <article>
            <span>问题</span><b>{{ scene.problem }}</b><small>客户 7F2A · 20:41:06</small>
          </article>
          <article>
            <span>影响</span><b>{{ scene.impact }}</b><small>{{ scene.impactNote }}</small>
          </article>
          <article>
            <span>下一步</span><b>{{ scene.nextStep }}</b><small>平台只提供证据，不执行修改</small>
          </article>
        </div>

        <TimingStrip :timings="scene.timings" />

        <div class="a-body">
          <section class="trace-card">
            <div class="section-head">
              <div><span class="section-label">证据收敛</span><h3>PS ID 全链路</h3></div>
              <code>{{ scene.psId }}</code>
            </div>

            <div v-if="scene.traceNodes.length" class="trace-line">
              <div
                v-for="(node, index) in scene.traceNodes"
                :key="node.hop"
                class="trace-node"
                :class="{ bad: node.bad }"
              >
                <span class="node-index">{{ index + 1 }}</span>
                <b>{{ node.service }}</b>
                <small>{{ node.duration }}</small>
              </div>
            </div>
            <p v-else class="no-trace">{{ scene.traceEmpty }}</p>

            <div class="contrast" :class="{ missing: !scene.contrast.available }">
              <span class="contrast-label">成功样本对照</span>
              <template v-if="scene.contrast.available">
                <b>{{ scene.contrast.failed }}</b>
                <span class="vs">vs</span>
                <b class="ok">{{ scene.contrast.baseline }}</b>
                <small>{{ scene.contrast.note }}</small>
              </template>
              <template v-else>
                <b class="warn">contrastAvailable = false</b>
                <small>{{ scene.contrast.note }}</small>
              </template>
            </div>

            <div class="finding" :class="scene.findingTone">
              <span class="finding-mark" />
              <div><b>{{ scene.findingTitle }}</b><p>{{ scene.finding }}</p></div>
              <code>{{ scene.findingRef }}</code>
            </div>
          </section>

          <aside class="draft-card">
            <span class="section-label">AI 生成的知识草稿</span>
            <h3>{{ scene.draft.title }}</h3>
            <ol v-if="scene.draft.steps.length">
              <li v-for="step in scene.draft.steps" :key="step">{{ step }}</li>
            </ol>
            <p v-else class="no-draft">{{ scene.draft.emptyReason }}</p>
            <div class="draft-state">
              <span>{{ scene.draft.state }}</span>
              <p>{{ scene.draft.stateNote }}</p>
            </div>
          </aside>
        </div>

        <div class="dispose">
          <button type="button" @click="explainAction">{{ scene.primaryAction }}</button>
          <p class="action-note">点击只推进领域状态，系统不执行任何生产变更。</p>
          <p v-if="actionEcho" class="action-echo">{{ actionEcho }}</p>
        </div>
      </template>

      <!-- ========== 开发证据投影 ========== -->
      <details v-if="view === 'INLINE'" class="dev-fold">
        <summary>
          展开开发证据台
          <span class="fold-hint">{{ scene.devHeadline }}</span>
        </summary>
        <DeveloperEvidencePanel
          :scene="scene"
          :confidence="confidence"
          :conclusion-label="CONCLUSION_LABEL[scene.conclusionType]"
        />
      </details>

      <DeveloperEvidencePanel
        v-else-if="pane === 'DEV'"
        :scene="scene"
        :confidence="confidence"
        :conclusion-label="CONCLUSION_LABEL[scene.conclusionType]"
      />
    </section>

    <!-- ========== 企微协同流（P3 暂缓，保留以记住真实入口在哪） ========== -->
    <section v-else class="variant variant-c">
      <div class="parked-banner">
        <b>P3 暂缓</b>
        <span>企微入口是录音里的真实一线入口（F6），但当前集中兵力做服务经理与开发两个投影；
          本页只保留结构，不再投入。</span>
      </div>
      <div class="c-body">
        <aside class="chat-panel">
          <div class="chat-head">
            <span class="status-dot" />
            <div><b>数字化服务平台智能小助手</b><small>企业微信群 · 演示会话</small></div>
          </div>
          <div class="messages">
            <div class="msg user">
              <span>服务经理</span><p>@小助手 客户反馈会话消息发送失败</p><time>{{ scene.timings.reportedAt }}</time>
            </div>
            <div class="msg bot">
              <span>MateClaw</span><p>请补充客户 ID 和大致发生时间；截图或视频可直接引用。</p>
              <time>{{ scene.timings.reportedAt }}</time>
            </div>
            <div class="msg user">
              <span>服务经理</span><p>客户 7F2A，20:40 左右。附截图。</p><time>{{ scene.timings.readyAt }}</time>
            </div>
            <div class="attachment">
              <span>IMG</span><div><b>消息发送失败.png</b><small>仅保存受控引用</small></div>
            </div>
            <div class="msg bot result" :class="scene.chatTone">
              <span>MateClaw · {{ scene.chatStatus }}</span>
              <p><b>当前判断：</b>{{ scene.chatVerdict }}</p>
              <p><b>下一步：</b>{{ scene.chatNext }}</p>
              <time>{{ scene.timings.conclusionAt }}</time>
            </div>
          </div>
        </aside>
        <main class="investigation-panel">
          <div class="investigation-head">
            <div><span class="section-label">后台调查状态</span><h2>从一句现象收敛到可交接证据</h2></div>
            <span class="confidence-pill" :class="confidence.toLowerCase()">
              {{ authorityView.shortMode }} · {{ authority }}
            </span>
          </div>
          <TimingStrip :timings="scene.timings" />
          <div class="stage-grid">
            <article v-for="(stage, index) in scene.stages" :key="stage.title" :class="stage.tone">
              <span>{{ index + 1 }}</span>
              <div><b>{{ stage.title }}</b><p>{{ stage.detail }}</p></div>
              <small>{{ stage.state }}</small>
            </article>
          </div>
        </main>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DeveloperEvidencePanel from './DeveloperEvidencePanel.vue'

/**
 * How the developer projection is reached. Both show the same evidence — the
 * open question was only whether it opens in place or replaces the page, so it
 * is a switch here instead of two rival layouts.
 */
const VIEWS = [
  { key: 'INLINE', short: '原地展开', parked: false },
  { key: 'SPLIT', short: '独立视图', parked: false },
  { key: 'WECOM', short: '企微协同（P3 暂缓）', parked: true },
] as const
type ViewKey = typeof VIEWS[number]['key']

/** The four endings this system actually produces. */
const OUTCOMES = [
  { key: 'HYPOTHESIS', short: '根因假设' },
  { key: 'EXCLUDED', short: '排除（非定位）' },
  { key: 'INSUFFICIENT', short: '证据不足弃权' },
  { key: 'SOURCE_DOWN', short: '证据源故障' },
] as const
type OutcomeKey = typeof OUTCOMES[number]['key']

/** Why this path was chosen. It changes the trust ceiling, not the evidence. */
const AUTHORITIES = [
  { key: 'EXPLICIT', short: '错误码命中' },
  { key: 'RULE_MATCHED', short: '规则命中场景' },
  { key: 'MODEL_PROPOSED', short: '模型提议场景' },
] as const
type AuthorityKey = typeof AUTHORITIES[number]['key']

type ConclusionType = 'LOCATED' | 'EXCLUDED' | 'HYPOTHESIS' | 'INSUFFICIENT_EVIDENCE'
const CONCLUSION_LABEL: Record<ConclusionType, string> = {
  LOCATED: '已定位',
  EXCLUDED: '已排除',
  HYPOTHESIS: '根因假设',
  INSUFFICIENT_EVIDENCE: '证据不足',
}

const route = useRoute()
const router = useRouter()
const pane = ref<'BUSINESS' | 'DEV'>('BUSINESS')

function pick<T extends string>(name: string, allowed: readonly { key: T }[], fallback: T): T {
  const raw = String(route.query[name] || '').toUpperCase()
  return allowed.some((item) => item.key === raw) ? (raw as T) : fallback
}

const view = computed<ViewKey>(() => pick('view', VIEWS, 'INLINE'))
const outcome = computed<OutcomeKey>(() => pick('outcome', OUTCOMES, 'HYPOTHESIS'))
const authority = computed<AuthorityKey>(() => pick('authority', AUTHORITIES, 'MODEL_PROPOSED'))

const AUTHORITY_VIEW: Record<AuthorityKey, {
  modeLabel: string; shortMode: string; ceiling: 'HIGH' | 'MEDIUM'; ceilingNote: string; hint: string
}> = {
  EXPLICIT: {
    modeLabel: '错误码确定性命中',
    shortMode: 'ERROR_CODE',
    ceiling: 'HIGH',
    ceilingNote: '确定性判据裁决，可到 HIGH',
    hint: 'ERROR_CODE_PLAYBOOK：选路、取证、判据全程零 LLM，结论由可复算判据得出。',
  },
  RULE_MATCHED: {
    modeLabel: '场景规则命中',
    shortMode: 'SCENARIO',
    ceiling: 'HIGH',
    ceilingNote: '服务端规则选路，非模型提议',
    hint: 'SCENARIO_PLAYBOOK：由服务端规则匹配 scenarioKey，EvidencePlan 来自已审核 Playbook。',
  },
  MODEL_PROPOSED: {
    modeLabel: '场景辅助调查',
    shortMode: 'SCENARIO',
    ceiling: 'MEDIUM',
    ceilingNote: '场景由模型提议，最高 MEDIUM',
    hint: 'MODEL_PROPOSED：模型只建议用哪张已审核地图，不产 DQL/工具名，置信被压到 MEDIUM。',
  },
}
const authorityView = computed(() => AUTHORITY_VIEW[authority.value])

/** min(outcome base, authority ceiling) — the ceiling can only lower it. */
const confidence = computed<'HIGH' | 'MEDIUM' | 'LOW'>(() => {
  const base = scene.value.baseConfidence
  if (base === 'LOW') return 'LOW'
  return base === 'HIGH' && authorityView.value.ceiling === 'MEDIUM' ? 'MEDIUM' : base
})

interface Timings {
  reportedAt: string; readyAt: string; conclusionAt: string; handoffAt: string | null
  intake: string; investigate: string; adopt: string | null
}
interface TraceNode { hop: string; service: string; duration: string; bad: boolean }

const TRACE: TraceNode[] = [
  { hop: 'h1', service: 'session-api', duration: '42 ms', bad: false },
  { hop: 'h2', service: 'session-state', duration: '1.82 s', bad: true },
  { hop: 'h3', service: 'session-api', duration: '18 ms', bad: false },
]

function timings(
  conclusionAt: string, investigate: string, handoffAt: string | null, adopt: string | null,
): Timings {
  return {
    reportedAt: '20:41:06', readyAt: '20:42:31', conclusionAt, handoffAt,
    intake: '1m25s', investigate, adopt,
  }
}

const SCENES = {
  HYPOTHESIS: {
    title: '会话消息发送失败',
    conclusionType: 'HYPOTHESIS' as ConclusionType,
    baseConfidence: 'HIGH' as const,
    headline: '消息请求已进入 session-api，但会话状态写入超时',
    narrative: '证据链指向状态写入阶段。当前只覆盖单个客户，尚未发现系统级扩散，需由会话服务开发确认锁竞争。',
    problem: '发送消息返回失败',
    impact: '单客户 · 单会话',
    impactNote: '其他客户同期请求正常',
    blastRadius: 'SINGLE_CUSTOMER',
    nextStep: '转会话服务开发确认',
    psId: 'synthetic-ps-message-send-001',
    traceNodes: TRACE,
    traceEmpty: '',
    contrast: {
      available: true,
      failed: '失败链路 session-state 1.82s',
      baseline: '同接口成功样本 P50 40ms',
      note: 'contrast_sample · 同窗口 20 条成功请求',
    },
    findingTone: 'bad',
    findingTitle: '异常点',
    finding: 'session-state 写入会话状态等待 1.82s，为成功样本 P50 的 45 倍，随后 session-api 返回失败。',
    findingRef: 'evidence:SYNTH-TRACE-BUNDLE',
    railHeadline: '4 条日志\n3 个服务',
    devHeadline: '状态写入超时是当前最强假设',
    progress: '已完成日志、全链路与成功样本对照取证',
    primaryAction: '确认结论',
    boundary: '尚不能证明锁竞争就是最终根因；不能修改数据、重启服务或自动发布 SOP。',
    boundaryRule: '证据不足时必须弃权',
    chatStatus: '调查完成',
    chatTone: '',
    chatVerdict: '会话状态写入超时，暂未发现批量影响。',
    chatNext: '建议转会话服务开发确认；系统未执行任何变更。',
    refs: ['SYNTH-LOG-SEARCH', 'SYNTH-TRACE-BUNDLE', 'SYNTH-CONTRAST'],
    timings: timings('20:43:14', '43s', '20:47:52', '4m38s'),
    evidenceEvents: [
      { time: '+0 ms', title: '收到消息发送请求', detail: '客户与会话上下文已关联。', ref: 'log-search:01', tone: '' },
      { time: '+37 ms', title: '进入会话状态写入', detail: 'session-api 调用 session-state。', ref: 'trace:02', tone: '' },
      { time: '+1.82 s', title: '状态写入等待超时', detail: '同接口成功样本 P50 为 40ms，本次为其 45 倍。', ref: 'trace:03', tone: 'anomaly' },
      { time: '+1.86 s', title: '上游返回发送失败', detail: '错误由 session-api 返回给调用方。', ref: 'trace:04', tone: '' },
    ],
    stages: [
      { title: '补齐上下文', detail: '客户 ID + 时间窗 + 截图引用', state: 'DONE', tone: '' },
      { title: '日志检索', detail: '命中 4 条相关日志并提取 PS ID', state: 'DONE', tone: '' },
      { title: '全链路取证', detail: '3 个服务，定位 1 个异常耗时点', state: 'DONE', tone: '' },
      { title: '成功样本对照', detail: '同窗口 20 条成功请求 P50 40ms', state: 'DONE', tone: '' },
      { title: '人工确认', detail: '等待开发核对锁与数据库状态', state: 'WAITING', tone: 'active' },
    ],
    draft: {
      title: 'SOP Draft · 等待人工对照',
      steps: [
        '按客户与时间窗检索消息发送日志，提取 PS ID。',
        '展开同一 PS ID 的服务跳序，确认异常首先出现在哪个服务。',
        '取同窗口成功样本做对照，确认耗时差异不是全局抖动。',
        '检查 session-state 的锁等待与数据库写入耗时，再由开发判断修复方式。',
      ],
      emptyReason: '',
      summary: '包含 4 个排查步骤和 3 条证据引用，等待与人工解法比较后进入审核。',
      state: 'CANDIDATE',
      stateNote: '不能直接进入已批准 Playbook',
    },
    evidenceRows: [
      { label: '日志取样', ref: 'SYNTH-LOG-SEARCH', value: '4 条 · PS ID 已提取' },
      { label: '链路包', ref: 'SYNTH-TRACE-BUNDLE', value: '3 服务 · 1 异常点' },
      { label: '成功对照', ref: 'SYNTH-CONTRAST', value: '20 条 · P50 40ms' },
      { label: '能力边界', ref: 'READ_ONLY', value: '不执行生产变更' },
    ],
  },

  EXCLUDED: {
    title: '会话消息发送失败',
    conclusionType: 'EXCLUDED' as ConclusionType,
    baseConfidence: 'MEDIUM' as const,
    headline: '平台侧未见异常：同窗口内仅该客户报错，其余客户全部正常',
    narrative: '这是排除，不是定位。我们能证明系统侧功能正常，但无法定位客户端根因——浏览器兼容性与客户网络本系统明确不做。',
    problem: '发送消息返回失败',
    impact: '仅 1 个客户',
    impactNote: '同窗口 214 个活跃客户零报错',
    blastRadius: 'SINGLE_CUSTOMER',
    nextStep: '请客户换网络/浏览器复现',
    psId: 'synthetic-ps-message-send-014',
    traceNodes: [
      { hop: 'h1', service: 'session-api', duration: '38 ms', bad: false },
      { hop: 'h2', service: 'session-state', duration: '41 ms', bad: false },
      { hop: 'h3', service: 'session-api', duration: '16 ms', bad: false },
    ],
    traceEmpty: '',
    contrast: {
      available: true,
      failed: '该客户失败 23 次',
      baseline: '其余 214 客户失败 0 次',
      note: 'blast_radius_probe · 同窗口客户维度分布',
    },
    findingTone: 'good',
    findingTitle: '排除依据',
    finding: '链路各跳耗时均在成功样本区间内；同窗口其他客户零报错，系统级故障判据求值为假（EXCLUDED，非 UNEVALUATED）。',
    findingRef: 'evidence:SYNTH-BLAST-PROBE',
    railHeadline: '3 条日志\n0 个异常点',
    devHeadline: '系统侧判据全部正常，只能给排除结论',
    progress: '已完成链路与客户维度分布取证',
    primaryAction: '确认排除结论',
    boundary: '不能定位客户端根因：浏览器兼容性、客户本地网络不在证据可得范围内。',
    boundaryRule: '排除 ≠ 定位，置信不得超过 MEDIUM',
    chatStatus: '调查完成',
    chatTone: 'good',
    chatVerdict: '平台侧未见异常，同期其他客户均正常。',
    chatNext: '建议先请客户更换网络或浏览器复现；如仍失败请回帖补充。',
    refs: ['SYNTH-LOG-SEARCH', 'SYNTH-BLAST-PROBE'],
    timings: timings('20:43:02', '31s', '20:45:10', '2m08s'),
    evidenceEvents: [
      { time: '+0 ms', title: '收到消息发送请求', detail: '客户与会话上下文已关联。', ref: 'log-search:01', tone: '' },
      { time: '+38 ms', title: '会话状态写入正常', detail: '耗时落在成功样本区间内。', ref: 'trace:02', tone: 'good' },
      { time: '窗口内', title: '客户维度分布', detail: '该客户 23 次失败，其余 214 客户 0 次。', ref: 'blast:03', tone: 'good' },
      { time: '判据', title: 'system_wide_impact = false', detail: '系统级故障假设被证据排除，非缺证据。', ref: 'criterion:04', tone: 'good' },
    ],
    stages: [
      { title: '补齐上下文', detail: '客户 ID + 时间窗 + 截图引用', state: 'DONE', tone: '' },
      { title: '爆炸半径探测', detail: '同窗口客户维度分布：单客户', state: 'DONE', tone: '' },
      { title: '全链路取证', detail: '3 个服务，0 个异常耗时点', state: 'DONE', tone: '' },
      { title: '判据裁决', detail: 'system_wide_impact 求值为假', state: 'DONE', tone: '' },
      { title: '人工确认', detail: '等待服务经理回复客户', state: 'WAITING', tone: 'active' },
    ],
    draft: {
      title: 'SOP Draft · 排除类',
      steps: [
        '先做爆炸半径探测：同窗口同接口的客户维度失败分布。',
        '仅单客户报错时，逐跳核对链路耗时是否落在成功样本区间。',
        '输出排除结论，明确标注这是排除而非定位，置信不超过 MEDIUM。',
      ],
      emptyReason: '',
      summary: '排除类草稿：结论形态受限，不得输出恢复动作。',
      state: 'CANDIDATE',
      stateNote: '不能直接进入已批准 Playbook',
    },
    evidenceRows: [
      { label: '日志取样', ref: 'SYNTH-LOG-SEARCH', value: '3 条 · PS ID 已提取' },
      { label: '半径探测', ref: 'SYNTH-BLAST-PROBE', value: '1 / 215 客户' },
      { label: '判据结果', ref: 'EXCLUDED', value: '求值为假，非缺证据' },
      { label: '能力边界', ref: 'READ_ONLY', value: '不定位客户端根因' },
    ],
  },

  INSUFFICIENT: {
    title: '会话消息发送失败',
    conclusionType: 'INSUFFICIENT_EVIDENCE' as ConclusionType,
    baseConfidence: 'LOW' as const,
    headline: '证据不足，系统弃权：PS ID 未能贯通全链路',
    narrative: '日志检索命中了失败记录，但同一 PS ID 只在入口服务出现，下游三跳缺失。链路不完整时给出的根因只会是看起来合理的猜测，因此弃权。',
    problem: '发送消息返回失败',
    impact: '影响面未知',
    impactNote: '爆炸半径未取到，不用 0 冒充已测量',
    blastRadius: 'UNKNOWN',
    nextStep: '转人工排查并补齐埋点',
    psId: 'synthetic-ps-message-send-031',
    traceNodes: [{ hop: 'h1', service: 'session-api', duration: '39 ms', bad: false }],
    traceEmpty: '',
    contrast: {
      available: true,
      failed: '失败链路仅 1 跳',
      baseline: '成功样本平均 3 跳',
      note: 'contrast_sample · 跳数差异本身就是埋点缺口的证据',
    },
    findingTone: 'unknown',
    findingTitle: '证据缺口',
    finding: 'session-state 与下游没有携带同一 PS ID，判据 failed_hop_in_session 为 UNEVALUATED——假设从未被检验，不等于已排除。',
    findingRef: 'gap:SYNTH-TRACE-BUNDLE',
    railHeadline: '2 条日志\n1 个服务（缺 2 跳）',
    devHeadline: '链路不完整，不产出根因',
    progress: '取证中断于全链路阶段',
    primaryAction: '转人工深查',
    boundary: '缺少下游日志时不能推断异常点；不允许用单跳耗时反推根因。',
    boundaryRule: '不完整比编造更好',
    chatStatus: '需要人工介入',
    chatTone: 'warn',
    chatVerdict: '证据不足，暂时无法判断问题出在哪一段。',
    chatNext: '已转人工排查；如能补充失败时的具体操作步骤会更快定位。',
    refs: ['SYNTH-LOG-SEARCH'],
    timings: timings('20:43:47', '76s', null, null),
    evidenceEvents: [
      { time: '+0 ms', title: '收到消息发送请求', detail: '入口日志命中，PS ID 已提取。', ref: 'log-search:01', tone: '' },
      { time: '+39 ms', title: '入口服务返回', detail: '仅此一跳携带该 PS ID。', ref: 'trace:02', tone: '' },
      { time: '—', title: '下游链路缺失', detail: 'session-state 及之后未携带同一 PS ID。', ref: 'gap:03', tone: 'unknown' },
      { time: '判据', title: 'failed_hop_in_session = UNEVALUATED', detail: '证据缺失，假设未被检验。', ref: 'criterion:04', tone: 'unknown' },
    ],
    stages: [
      { title: '补齐上下文', detail: '客户 ID + 时间窗 + 截图引用', state: 'DONE', tone: '' },
      { title: '日志检索', detail: '命中 2 条相关日志并提取 PS ID', state: 'DONE', tone: '' },
      { title: '全链路取证', detail: 'PS ID 未贯通，仅 1 跳', state: 'GAP', tone: 'gap' },
      { title: '证据诊断', detail: '必需判据无法求值，强制 abstain', state: 'ABSTAIN', tone: 'gap' },
      { title: '人工接管', detail: '等待开发补齐埋点后重跑', state: 'WAITING', tone: 'active' },
    ],
    draft: {
      title: '未生成草稿',
      steps: [],
      emptyReason: '弃权时不产出排查步骤：没有被证据支持的步骤会污染知识库。这里只登记一条埋点缺口待办。',
      summary: '弃权不产出草稿，只登记 PS ID 埋点缺口。',
      state: 'NO CANDIDATE',
      stateNote: '证据不足不产生知识候选',
    },
    evidenceRows: [
      { label: '日志取样', ref: 'SYNTH-LOG-SEARCH', value: '2 条 · PS ID 已提取' },
      { label: '链路包', ref: 'SYNTH-TRACE-BUNDLE', value: '缺 2 跳 · 不完整' },
      { label: '判据结果', ref: 'UNEVALUATED', value: '未被检验 ≠ 已排除' },
      { label: '能力边界', ref: 'ABSTAIN', value: '不产出根因与动作' },
    ],
  },

  SOURCE_DOWN: {
    title: '会话消息发送失败',
    conclusionType: 'INSUFFICIENT_EVIDENCE' as ConclusionType,
    baseConfidence: 'LOW' as const,
    headline: '证据源不可用：观测云查询超时，本次未取到任何证据',
    narrative: '这不是"没有问题"，而是"我们这次没能看"。失败原因与来源已登记，可在源恢复后原样重跑。',
    problem: '发送消息返回失败',
    impact: '影响面未知',
    impactNote: '未取到证据，不做任何影响推断',
    blastRadius: 'UNKNOWN',
    nextStep: '源恢复后重跑，或转人工',
    psId: '—',
    traceNodes: [],
    traceEmpty: '本次未取到链路数据：log_search 在 5s 超时后 fail closed 返回 MISSING。',
    contrast: {
      available: false,
      failed: '',
      baseline: '',
      note: '对照取证同样失败；按 D15 只降级不失败，但该草稿被锁定在校准期档',
    },
    findingTone: 'unknown',
    findingTitle: '源状态',
    finding: 'guance adapter 连续 2 次 5s 超时，canonical 结果为 MISSING，记录 source 与原因，不重试到"看起来成功"。',
    findingRef: 'source:GUANCE_TIMEOUT',
    railHeadline: '0 条日志\n源不可用',
    devHeadline: '取证阶段即失败，未进入判定',
    progress: '取证失败，未进入诊断',
    primaryAction: '重跑取证',
    boundary: '源不可用时不能降级为"系统正常"；也不能用历史缓存冒充本次证据。',
    boundaryRule: '失败必须可见，不得静默成功',
    chatStatus: '暂时无法调查',
    chatTone: 'warn',
    chatVerdict: '观测云查询超时，本次没有取到证据。',
    chatNext: '已记录并会在源恢复后重跑；紧急可直接转值班开发。',
    refs: [],
    timings: timings('20:42:43', '12s', null, null),
    evidenceEvents: [
      { time: '+0 s', title: '发起日志检索', detail: 'log_search 提交到 guance adapter。', ref: 'source:01', tone: '' },
      { time: '+5 s', title: '第 1 次超时', detail: '按 EvidenceProperties.Guance.timeout fail closed。', ref: 'source:02', tone: 'unknown' },
      { time: '+11 s', title: '第 2 次超时', detail: '主备均不可用，返回 canonical MISSING。', ref: 'source:03', tone: 'unknown' },
      { time: '+12 s', title: '停止合成', detail: '前两步任一缺失即不调用模型。', ref: 'policy:04', tone: 'unknown' },
    ],
    stages: [
      { title: '补齐上下文', detail: '客户 ID + 时间窗 + 截图引用', state: 'DONE', tone: '' },
      { title: '日志检索', detail: 'guance adapter 超时 · MISSING', state: 'FAILED', tone: 'gap' },
      { title: '全链路取证', detail: '未执行：前置证据缺失', state: 'SKIPPED', tone: 'gap' },
      { title: '证据诊断', detail: '未执行：不在无证据情况下调用模型', state: 'SKIPPED', tone: 'gap' },
      { title: '等待重跑', detail: '源恢复后可用同一入参重放', state: 'WAITING', tone: 'active' },
    ],
    draft: {
      title: '未生成草稿',
      steps: [],
      emptyReason: '取证阶段即失败，模型未被调用——按预算表，前两次取证任一缺失就停止合成。',
      summary: '未调用模型，无草稿产出。',
      state: 'NO CANDIDATE',
      stateNote: '无证据不产生知识候选',
    },
    evidenceRows: [
      { label: '日志取样', ref: 'MISSING', value: '超时 · 2 次' },
      { label: '链路包', ref: 'SKIPPED', value: '前置缺失，未执行' },
      { label: '模型调用', ref: 'NOT_CALLED', value: '0 次' },
      { label: '能力边界', ref: 'FAIL_CLOSED', value: '不静默降级为正常' },
    ],
  },
} as const

const scene = computed(() => SCENES[outcome.value])

/* ------------------------------------------------------- timing strip */

/** D14 north-star: three separate spans, never one total. */
const TimingStrip = defineComponent({
  name: 'TimingStrip',
  props: { timings: { type: Object as () => Timings, required: true } },
  setup(props) {
    const cell = (label: string, value: string, range: string, pending = false) =>
      h('div', { class: ['timing-cell', { pending }] }, [
        h('span', label), h('b', value), h('small', range),
      ])
    return () => h('div', { class: 'timing-strip' }, [
      h('span', { class: 'section-label' }, '北极星耗时'),
      h('div', { class: 'timing-cells' }, [
        cell('补问成本', props.timings.intake, `${props.timings.reportedAt} → ${props.timings.readyAt}`),
        cell('系统调查', props.timings.investigate, `${props.timings.readyAt} → ${props.timings.conclusionAt}`),
        cell(
          '人的采纳',
          props.timings.adopt ?? '未发生',
          props.timings.handoffAt
            ? `${props.timings.conclusionAt} → ${props.timings.handoffAt}`
            : 'handoffAt = null，不用 0 填充',
          !props.timings.adopt,
        ),
      ]),
    ])
  },
})

/* ---------------------------------------------------------- interaction */

const actionEcho = ref('')
function explainAction() {
  actionEcho.value = `原型不提交。真实系统里这一步只把状态推进到下一档（${scene.value.primaryAction}），不执行任何生产变更。`
  window.setTimeout(() => { actionEcho.value = '' }, 4000)
}

function setQuery(key: string, value: string) {
  router.replace({ query: { ...route.query, demo: '1', [key]: value } })
}

function cycleOutcome(offset: number) {
  const index = OUTCOMES.findIndex((item) => item.key === outcome.value)
  setQuery('outcome', OUTCOMES[(index + offset + OUTCOMES.length) % OUTCOMES.length].key)
}

function onKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  if (target?.matches('input, textarea, [contenteditable="true"]')) return
  if (event.key === 'ArrowLeft') cycleOutcome(-1)
  if (event.key === 'ArrowRight') cycleOutcome(1)
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.proto-page {
  --ink: #172033; --muted: #667085; --line: #dfe3ea; --soft: #f5f7fb;
  --blue: #2f5cf5; --blue-soft: #edf2ff; --red: #d92d20; --red-soft: #fff2f0;
  --green: #138a58; --green-soft: #ecfdf3; --amber: #b54708; --amber-soft: #fff8eb;
  --slate: #667085; --slate-soft: #f2f4f7;
  --mono: ui-monospace, "SFMono-Regular", Consolas, monospace;
  min-height: 100%; overflow: auto; background: #f2f4f8; color: var(--ink);
  padding: 24px 28px 60px; font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
}
.proto-topbar { max-width: 1440px; margin: 0 auto 14px; display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; flex-wrap: wrap; }
.eyebrow, .section-label { display: block; color: var(--muted); font-size: 11px; font-weight: 700; letter-spacing: .11em; text-transform: uppercase; }
.proto-topbar h1 { margin: 5px 0 0; font-size: 26px; letter-spacing: -.03em; }
.top-meta { display: flex; gap: 8px; align-items: center; font-size: 12px; flex-wrap: wrap; }
.top-meta span { padding: 7px 10px; border-radius: 5px; border: 1px solid var(--line); background: #fff; }
.top-meta .mode { color: var(--blue); border-color: #bccbff; background: var(--blue-soft); font-weight: 700; }
.top-meta .authority { font-family: var(--mono); font-size: 11px; }
.top-meta .authority.explicit { color: var(--green); border-color: #a9e0c4; background: var(--green-soft); }
.top-meta .authority.rule_matched { color: var(--blue); border-color: #bccbff; background: var(--blue-soft); }
.top-meta .authority.model_proposed { color: var(--amber); border-color: #f6d795; background: var(--amber-soft); }
.top-meta .fixture { color: var(--amber); background: var(--amber-soft); border-color: #f6d795; }

.axes { max-width: 1440px; margin: 0 auto 12px; background: #fff; border: 1px solid var(--line); padding: 12px 16px; display: flex; gap: 24px; align-items: center; flex-wrap: wrap; }
.axis { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.axis-label { color: var(--muted); font-size: 11px; font-weight: 700; letter-spacing: .09em; text-transform: uppercase; }
.axis button { border: 1px solid var(--line); background: #fff; color: var(--muted); font: inherit; font-size: 12px; padding: 6px 11px; border-radius: 4px; cursor: pointer; }
.axis button:hover { border-color: #b9c6f0; color: var(--blue); }
.axis button.on { color: #fff; background: var(--ink); border-color: var(--ink); font-weight: 600; }
.axis button.parked { border-style: dashed; }
.axis button:focus-visible { outline: 2px solid var(--blue); outline-offset: 2px; }
.axis-hint { flex: 1 1 300px; margin: 0; color: var(--muted); font-size: 12px; line-height: 1.5; }

.panes { max-width: 1440px; margin: 0 auto 12px; display: flex; gap: 0; border: 1px solid var(--line); background: #fff; width: fit-content; }
.panes button { border: 0; border-right: 1px solid var(--line); background: #fff; color: var(--muted); font: inherit; font-size: 13px; padding: 9px 18px; cursor: pointer; }
.panes button:last-child { border-right: 0; }
.panes button.on { color: var(--ink); background: var(--soft); font-weight: 650; box-shadow: inset 0 -2px 0 var(--blue); }
.panes button:focus-visible { outline: 2px solid var(--blue); outline-offset: -2px; }

.variant { max-width: 1440px; margin: auto; display: grid; gap: 14px; }
.variant h2, .variant h3, .variant p { margin-top: 0; }
code { font-family: var(--mono); font-size: 11px; }

.timing-strip { background: #fff; border: 1px solid var(--line); padding: 13px 18px; display: flex; align-items: center; gap: 22px; flex-wrap: wrap; }
.timing-cells { display: flex; gap: 30px; flex-wrap: wrap; }
.timing-cell { display: grid; gap: 2px; }
.timing-cell span { color: var(--muted); font-size: 11px; }
.timing-cell b { font-family: var(--mono); font-size: 16px; letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.timing-cell small { color: #98a2b3; font-family: var(--mono); font-size: 10px; }
.timing-cell.pending b { color: var(--muted); }

.a-status { display: grid; grid-template-columns: 1fr 230px; gap: 24px; background: #fff; border: 1px solid var(--line); padding: 24px 28px; }
.conclusion-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ctype { font-family: var(--mono); font-size: 11px; font-weight: 700; padding: 3px 9px; border: 1px solid; border-radius: 3px; }
.ctype.LOCATED { color: var(--red); border-color: #f5c9c4; background: var(--red-soft); }
.ctype.HYPOTHESIS { color: var(--amber); border-color: #f6d795; background: var(--amber-soft); }
.ctype.EXCLUDED { color: var(--green); border-color: #a9e0c4; background: var(--green-soft); }
.ctype.INSUFFICIENT_EVIDENCE { color: var(--slate); border-color: #d3d8e0; background: var(--slate-soft); border-style: dashed; }
.a-status h2 { margin: 9px 0 8px; font-size: 24px; letter-spacing: -.025em; line-height: 1.4; }
.a-status p { margin: 0; color: var(--muted); line-height: 1.65; }
.confidence-block { border-left: 1px solid var(--line); padding-left: 24px; display: flex; flex-direction: column; justify-content: center; }
.confidence-block span { color: var(--muted); font-size: 12px; }
.confidence-block strong { font-family: var(--mono); font-size: 23px; margin: 6px 0 4px; }
.confidence-block strong.high { color: var(--green); }
.confidence-block strong.medium { color: var(--amber); }
.confidence-block strong.low { color: var(--slate); }
.confidence-block small { color: var(--muted); line-height: 1.45; }

.summary-strip { display: grid; grid-template-columns: repeat(3, 1fr); border: 1px solid var(--line); background: #fff; }
.summary-strip article { padding: 18px 22px; min-height: 92px; border-right: 1px solid var(--line); display: flex; flex-direction: column; gap: 5px; }
.summary-strip article:last-child { border-right: 0; }
.summary-strip span, .summary-strip small { color: var(--muted); font-size: 12px; }
.summary-strip b { font-size: 16px; }

.a-body { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .7fr); gap: 14px; }
.trace-card, .draft-card { background: #fff; border: 1px solid var(--line); padding: 22px; }
.section-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 18px; }
.section-head h3, .draft-card h3 { margin: 5px 0 0; font-size: 17px; }
.section-head code { color: var(--muted); }
.trace-line { margin: 30px 0 18px; display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; position: relative; }
.trace-line::before { content: ""; position: absolute; height: 1px; background: #aeb8cb; left: 8%; right: 8%; top: 14px; }
.trace-node { position: relative; display: grid; justify-items: center; gap: 5px; font-family: var(--mono); }
.trace-node .node-index { z-index: 1; width: 28px; height: 28px; display: grid; place-items: center; border-radius: 50%; color: #fff; background: var(--blue); font-size: 11px; }
.trace-node.bad .node-index { background: var(--red); box-shadow: 0 0 0 5px var(--red-soft); }
.trace-node b { font-size: 12px; } .trace-node small { color: var(--muted); }
.no-trace { margin: 24px 0 16px; padding: 14px; color: var(--muted); font-size: 13px; line-height: 1.6; background: var(--slate-soft); border: 1px dashed #cfd5df; }
.contrast { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; padding: 11px 14px; margin-bottom: 14px; background: var(--soft); border: 1px solid var(--line); }
.contrast.missing { border-style: dashed; background: var(--slate-soft); }
.contrast-label { color: var(--muted); font-size: 11px; font-weight: 700; letter-spacing: .09em; text-transform: uppercase; }
.contrast b { font-family: var(--mono); font-size: 13px; }
.contrast b.ok { color: var(--green); } .contrast b.warn { color: var(--slate); }
.contrast .vs { color: var(--muted); font-size: 12px; }
.contrast small { color: var(--muted); font-size: 11px; }
.finding { display: grid; grid-template-columns: 12px 1fr auto; gap: 12px; align-items: start; padding: 14px; }
.finding.bad { background: var(--red-soft); border: 1px solid #f5c9c4; }
.finding.good { background: var(--green-soft); border: 1px solid #a9e0c4; }
.finding.unknown { background: var(--slate-soft); border: 1px dashed #cfd5df; }
.finding-mark { width: 8px; height: 8px; margin-top: 4px; border-radius: 50%; }
.finding.bad .finding-mark { background: var(--red); }
.finding.good .finding-mark { background: var(--green); }
.finding.unknown .finding-mark { background: var(--slate); }
.finding p { margin: 3px 0 0; font-size: 13px; line-height: 1.6; }
.finding.bad p { color: #7a271a; } .finding.good p { color: #05603a; } .finding.unknown p { color: #475467; }
.finding code { color: var(--muted); }
.draft-card ol { margin: 18px 0; padding-left: 20px; color: #344054; line-height: 1.65; font-size: 13px; }
.draft-card li + li { margin-top: 8px; }
.no-draft { margin: 16px 0; padding: 13px; color: var(--muted); font-size: 12.5px; line-height: 1.65; background: var(--slate-soft); border: 1px dashed #cfd5df; }
.draft-state { border-top: 1px solid var(--line); padding-top: 14px; }
.draft-state span { color: var(--blue); font-family: var(--mono); font-size: 11px; font-weight: 700; }
.draft-state p { margin: 4px 0 0; font-size: 12px; color: var(--muted); }

.dispose { background: #fff; border: 1px solid var(--line); padding: 16px 20px; }
.dispose button { border: 1px solid var(--ink); padding: 9px 22px; color: #fff; background: var(--ink); cursor: pointer; font: inherit; font-size: 13px; }
.dispose button:focus-visible { outline: 2px solid var(--blue); outline-offset: 2px; }
.action-note { margin: 8px 0 0; color: var(--muted); font-size: 11.5px; }
.action-echo { margin: 8px 0 0; padding: 9px 11px; color: #05603a; background: var(--green-soft); border: 1px solid #a9e0c4; font-size: 11.5px; line-height: 1.55; }

.dev-fold { background: #fff; border: 1px solid var(--line); }
.dev-fold > summary { cursor: pointer; padding: 14px 18px; font-size: 13px; font-weight: 700; display: flex; align-items: baseline; gap: 12px; }
.dev-fold > summary:focus-visible { outline: 2px solid var(--blue); outline-offset: -2px; }
.fold-hint { color: var(--muted); font-weight: 400; font-size: 12px; }

.parked-banner { display: flex; align-items: baseline; gap: 12px; padding: 12px 16px; background: var(--slate-soft); border: 1px dashed #cfd5df; font-size: 12.5px; line-height: 1.6; color: var(--muted); }
.parked-banner b { color: var(--slate); font-family: var(--mono); font-size: 11px; }
.c-body { display: grid; grid-template-columns: 390px minmax(0, 1fr); min-height: 560px; background: #fff; border: 1px solid var(--line); }
.chat-panel { background: #eef1f5; border-right: 1px solid var(--line); display: flex; flex-direction: column; }
.chat-head { padding: 18px 20px; background: #fff; border-bottom: 1px solid var(--line); display: flex; align-items: center; gap: 11px; }
.status-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--green); }
.chat-head div { display: grid; gap: 3px; } .chat-head small { color: var(--muted); }
.messages { padding: 22px 18px; display: grid; gap: 14px; overflow: auto; align-content: start; }
.msg { max-width: 84%; display: grid; gap: 4px; }
.msg span { color: var(--muted); font-size: 10px; }
.msg p { margin: 0; padding: 11px 13px; background: #fff; border: 1px solid #dfe3ea; font-size: 13px; line-height: 1.55; }
.msg time { color: #98a2b3; font-size: 9px; font-family: var(--mono); }
.msg.user { justify-self: end; } .msg.user span, .msg.user time { text-align: right; }
.msg.user p { background: #dce7ff; border-color: #c2d3ff; }
.msg.result { max-width: 94%; } .msg.result p { border-left: 3px solid var(--blue); }
.msg.result.good p { border-left-color: var(--green); }
.msg.result.warn p { border-left-color: var(--amber); }
.attachment { justify-self: end; display: flex; gap: 9px; align-items: center; background: #fff; border: 1px solid var(--line); padding: 9px 11px; width: 210px; }
.attachment > span { width: 34px; height: 34px; display: grid; place-items: center; color: var(--blue); background: var(--blue-soft); font: 10px var(--mono); }
.attachment div { display: grid; gap: 3px; } .attachment b { font-size: 11px; } .attachment small { color: var(--muted); font-size: 9px; }
.investigation-panel { padding: 26px 28px; min-width: 0; display: grid; gap: 18px; align-content: start; }
.investigation-head { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; padding-bottom: 20px; border-bottom: 1px solid var(--line); }
.investigation-head h2 { margin: 7px 0 0; font-size: 22px; }
.confidence-pill { border: 1px solid var(--line); padding: 7px 9px; font: 10px var(--mono); white-space: nowrap; }
.confidence-pill.high { color: var(--green); border-color: #a9e0c4; background: var(--green-soft); }
.confidence-pill.medium { color: var(--amber); border-color: #e9c56e; background: var(--amber-soft); }
.confidence-pill.low { color: var(--slate); border-color: #d3d8e0; background: var(--slate-soft); }
.stage-grid { display: grid; gap: 9px; }
.stage-grid article { display: grid; grid-template-columns: 28px 1fr auto; gap: 13px; align-items: center; padding: 12px 14px; border: 1px solid var(--line); }
.stage-grid article > span { width: 25px; height: 25px; display: grid; place-items: center; color: #fff; background: var(--blue); font: 10px var(--mono); }
.stage-grid article.active { border-color: #e6c36b; background: #fffbf0; }
.stage-grid article.active > span { background: var(--amber); }
.stage-grid article.gap { border-style: dashed; background: var(--slate-soft); }
.stage-grid article.gap > span { background: var(--slate); }
.stage-grid b { font-size: 13px; } .stage-grid p { margin: 3px 0 0; color: var(--muted); font-size: 11px; }
.stage-grid small { color: var(--green); font: 10px var(--mono); }
.stage-grid article.active small { color: var(--amber); }
.stage-grid article.gap small { color: var(--slate); }

@media (max-width: 1050px) {
  .a-body { grid-template-columns: 1fr; }
  .c-body { grid-template-columns: 340px 1fr; }
}
@media (max-width: 760px) {
  .proto-page { padding: 16px 14px 40px; }
  .proto-topbar, .a-status, .investigation-head { align-items: flex-start; flex-direction: column; display: flex; }
  .a-status { display: grid; grid-template-columns: 1fr; }
  .confidence-block { border-left: 0; padding-left: 0; border-top: 1px solid var(--line); padding-top: 16px; }
  .summary-strip { grid-template-columns: 1fr; }
  .summary-strip article { border-right: 0; border-bottom: 1px solid var(--line); }
  .c-body { display: block; }
  .chat-panel { border-right: 0; border-bottom: 1px solid var(--line); min-height: 480px; }
  .timing-cells { gap: 18px; }
}
</style>
