<!--
  PROTOTYPE — throwaway after review.
  Question: which information structure best proves the no-error-code
  evidence-to-diagnosis-to-SOP-draft loop without becoming a flashy cockpit?
  Three variants on the development-only /prototype/troubleshooting route;
  the authenticated /troubleshooting?demo=1 entry renders the same component.
-->
<template>
  <div class="proto-page">
    <header class="proto-topbar">
      <div>
        <span class="eyebrow">MATECLAW · 智能排障体验原型</span>
        <h1>会话消息发送失败</h1>
      </div>
      <div class="top-meta">
        <span class="mode">场景辅助调查</span>
        <span class="fixture">Recorded Replay · 非真实观测云</span>
      </div>
    </header>

    <section v-if="variant === 'A'" class="variant variant-a">
      <div class="a-status">
        <div>
          <span class="section-label">给服务经理的结论</span>
          <h2>消息请求已进入 session-api，但会话状态写入超时</h2>
          <p>当前证据只覆盖单个客户，尚未发现系统级扩散。建议由会话服务开发确认状态锁竞争。</p>
        </div>
        <div class="confidence-block">
          <span>可信等级</span>
          <strong>MEDIUM</strong>
          <small>场景由模型提议，证据已核验</small>
        </div>
      </div>

      <div class="summary-strip">
        <article>
          <span>问题</span>
          <b>发送消息返回失败</b>
          <small>客户 7F2A · 20:41:16</small>
        </article>
        <article>
          <span>影响</span>
          <b>单客户 · 单会话</b>
          <small>其他客户同期请求正常</small>
        </article>
        <article>
          <span>下一步</span>
          <b>转会话服务开发确认</b>
          <small>平台只提供证据，不执行修改</small>
        </article>
      </div>

      <div class="a-body">
        <section class="trace-card">
          <div class="section-head">
            <div><span class="section-label">证据收敛</span><h3>PS ID 全链路</h3></div>
            <code>synthetic-ps-message-send-001</code>
          </div>
          <div class="trace-line">
            <div v-for="(node, index) in traceNodes" :key="node.service" class="trace-node" :class="{ bad: node.bad }">
              <span class="node-index">{{ index + 1 }}</span>
              <b>{{ node.service }}</b>
              <small>{{ node.duration }}</small>
            </div>
          </div>
          <div class="finding">
            <span class="finding-mark" />
            <div><b>异常点</b><p>session-state 在写入会话状态时等待 1.82s，随后 session-api 返回失败。</p></div>
            <code>evidence:SYNTH-TRACE-BUNDLE</code>
          </div>
        </section>

        <aside class="draft-card">
          <span class="section-label">AI 生成的知识草稿</span>
          <h3>SOP Draft · 等待人工对照</h3>
          <ol>
            <li v-for="step in draftSteps" :key="step">{{ step }}</li>
          </ol>
          <div class="draft-state"><span>CANDIDATE</span><p>不能直接进入已批准 Playbook</p></div>
        </aside>
      </div>

      <details class="evidence-fold">
        <summary>展开开发证据与能力边界</summary>
        <div class="evidence-table">
          <div><span>日志取样</span><code>SYNTH-LOG-SEARCH</code><b>4 条 · PS ID 已提取</b></div>
          <div><span>链路包</span><code>SYNTH-TRACE-BUNDLE</code><b>3 服务 · 1 异常点</b></div>
          <div><span>路由可信</span><code>MODEL_PROPOSED</code><b>最高 MEDIUM</b></div>
          <div><span>能力边界</span><code>READ_ONLY</code><b>不执行生产变更</b></div>
        </div>
      </details>
    </section>

    <section v-else-if="variant === 'B'" class="variant variant-b">
      <aside class="b-rail">
        <span class="section-label">调用链</span>
        <h2>4 条日志<br>3 个服务</h2>
        <div class="rail-flow">
          <div v-for="(node, index) in traceNodes" :key="node.service" class="rail-node" :class="{ bad: node.bad }">
            <span>{{ String(index + 1).padStart(2, '0') }}</span>
            <div><b>{{ node.service }}</b><small>{{ node.duration }}</small></div>
          </div>
        </div>
        <div class="rail-scope"><span>影响范围</span><b>SINGLE_CUSTOMER</b><small>系统侧未见批量异常</small></div>
      </aside>

      <main class="b-main">
        <div class="b-title">
          <div><span class="section-label">开发调查台</span><h2>状态写入超时是当前最强假设</h2></div>
          <span class="confidence-pill">MEDIUM · 待确认</span>
        </div>

        <div class="evidence-timeline">
          <article v-for="event in evidenceEvents" :key="event.time" :class="{ anomaly: event.anomaly }">
            <time>{{ event.time }}</time>
            <div class="timeline-line"><span /></div>
            <div><b>{{ event.title }}</b><p>{{ event.detail }}</p><code>{{ event.ref }}</code></div>
          </article>
        </div>

        <div class="b-bottom">
          <section>
            <span class="section-label">排查步骤草稿</span>
            <ol><li v-for="step in draftSteps" :key="step">{{ step }}</li></ol>
          </section>
          <section class="boundary-panel">
            <span class="section-label">系统明确做不到</span>
            <p>尚不能证明锁竞争就是最终根因；不能修改数据、重启服务或自动发布 SOP。</p>
            <b>证据不足时必须弃权</b>
          </section>
        </div>
      </main>

      <aside class="b-summary">
        <span class="section-label">业务投影</span>
        <h3>消息发送失败</h3>
        <dl>
          <dt>影响</dt><dd>单客户、单会话</dd>
          <dt>进度</dt><dd>已完成日志与全链路取证</dd>
          <dt>结论</dt><dd>会话状态写入异常，待开发确认</dd>
          <dt>下一步</dt><dd>把证据包转给 session-state owner</dd>
        </dl>
        <button type="button" disabled>确认结论（原型不提交）</button>
      </aside>
    </section>

    <section v-else class="variant variant-c">
      <aside class="chat-panel">
        <div class="chat-head">
          <span class="status-dot" />
          <div><b>数字化服务平台智能小助手</b><small>企业微信群 · 演示会话</small></div>
        </div>
        <div class="messages">
          <div class="msg user"><span>服务经理</span><p>@小助手 客户反馈会话消息发送失败</p><time>20:41</time></div>
          <div class="msg bot"><span>MateClaw</span><p>请补充客户 ID 和大致发生时间；截图或视频可直接引用。</p><time>20:41</time></div>
          <div class="msg user"><span>服务经理</span><p>客户 7F2A，20:40 左右。附截图。</p><time>20:42</time></div>
          <div class="attachment"><span>IMG</span><div><b>消息发送失败.png</b><small>仅保存受控引用</small></div></div>
          <div class="msg bot result">
            <span>MateClaw · 调查完成</span>
            <p><b>当前判断：</b>会话状态写入超时，暂未发现批量影响。</p>
            <p><b>下一步：</b>建议转会话服务开发确认；系统未执行任何变更。</p>
            <time>20:43</time>
          </div>
        </div>
      </aside>

      <main class="investigation-panel">
        <div class="investigation-head">
          <div><span class="section-label">后台调查状态</span><h2>从一句现象收敛到可交接证据</h2></div>
          <span class="confidence-pill">SCENARIO · MODEL_PROPOSED</span>
        </div>
        <div class="stage-grid">
          <article v-for="(stage, index) in stages" :key="stage.title" :class="{ active: stage.active }">
            <span>{{ index + 1 }}</span><div><b>{{ stage.title }}</b><p>{{ stage.detail }}</p></div><small>{{ stage.state }}</small>
          </article>
        </div>
        <div class="c-result">
          <section>
            <span class="section-label">证据支持的结论</span>
            <h3>session-state 写入耗时异常</h3>
            <p>证据链指向状态写入阶段，但当前只是根因假设；需要开发结合锁和数据库状态确认。</p>
            <div class="refs"><code>SYNTH-LOG-SEARCH</code><code>SYNTH-TRACE-BUNDLE</code></div>
          </section>
          <section>
            <span class="section-label">知识生产</span>
            <h3>SopDraft 已形成</h3>
            <p>包含 3 个排查步骤和 2 条证据引用，等待与人工解法比较后进入审核。</p>
            <span class="candidate-chip">CANDIDATE · NOT ROUTEABLE</span>
          </section>
        </div>
      </main>
    </section>

    <nav class="prototype-switcher" aria-label="原型方案切换">
      <button type="button" aria-label="上一版" @click="cycle(-1)">←</button>
      <span><b>{{ variant }}</b> — {{ currentVariant.name }}</span>
      <button type="button" aria-label="下一版" @click="cycle(1)">→</button>
    </nav>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const variants = [
  { key: 'A', name: '服务经理简报' },
  { key: 'B', name: '开发证据台' },
  { key: 'C', name: '企微协同流' },
] as const

type VariantKey = typeof variants[number]['key']

const route = useRoute()
const router = useRouter()
const variant = computed<VariantKey>(() => {
  const raw = String(route.query.variant || 'A').toUpperCase()
  return variants.some(item => item.key === raw) ? raw as VariantKey : 'A'
})
const currentVariant = computed(() => variants.find(item => item.key === variant.value) || variants[0])

const traceNodes = [
  { service: 'session-api', duration: '42 ms', bad: false },
  { service: 'session-state', duration: '1.82 s', bad: true },
  { service: 'session-api', duration: '18 ms', bad: false },
]

const evidenceEvents = [
  { time: '+0 ms', title: '收到消息发送请求', detail: '客户与会话上下文已关联。', ref: 'log-search:01', anomaly: false },
  { time: '+37 ms', title: '进入会话状态写入', detail: 'session-api 调用 session-state。', ref: 'trace:02', anomaly: false },
  { time: '+1.82 s', title: '状态写入等待超时', detail: '耗时显著高于同时间窗基线。', ref: 'trace:03', anomaly: true },
  { time: '+1.86 s', title: '上游返回发送失败', detail: '错误由 session-api 返回给调用方。', ref: 'trace:04', anomaly: false },
]

const draftSteps = [
  '按客户与时间窗检索消息发送日志，提取 PS ID。',
  '展开同一 PS ID 的服务跳序，确认异常首先出现在哪个服务。',
  '检查 session-state 的锁等待与数据库写入耗时，再由开发判断修复方式。',
]

const stages = [
  { title: '补齐上下文', detail: '客户 ID + 时间窗 + 截图引用', state: 'DONE', active: false },
  { title: '日志检索', detail: '命中 4 条相关日志并提取 PS ID', state: 'DONE', active: false },
  { title: '全链路取证', detail: '3 个服务，定位 1 个异常耗时点', state: 'DONE', active: false },
  { title: '证据诊断', detail: '形成 MEDIUM 根因假设', state: 'DONE', active: false },
  { title: '人工确认', detail: '等待开发核对锁与数据库状态', state: 'WAITING', active: true },
]

function cycle(offset: number) {
  const index = variants.findIndex(item => item.key === variant.value)
  const next = variants[(index + offset + variants.length) % variants.length]
  router.replace({ query: { ...route.query, demo: '1', variant: next.key } })
}

function onKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  if (target?.matches('input, textarea, [contenteditable="true"]')) return
  if (event.key === 'ArrowLeft') cycle(-1)
  if (event.key === 'ArrowRight') cycle(1)
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.proto-page {
  --ink: #172033; --muted: #667085; --line: #dfe3ea; --soft: #f5f7fb;
  --blue: #2f5cf5; --blue-soft: #edf2ff; --red: #d92d20; --red-soft: #fff2f0;
  --green: #138a58; --amber: #b54708;
  min-height: 100%; overflow: auto; background: #f2f4f8; color: var(--ink);
  padding: 24px 28px 88px; font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
}
.proto-topbar { max-width: 1440px; margin: 0 auto 18px; display: flex; align-items: end; justify-content: space-between; gap: 20px; }
.eyebrow, .section-label { display: block; color: var(--muted); font-size: 11px; font-weight: 700; letter-spacing: .11em; text-transform: uppercase; }
.proto-topbar h1 { margin: 5px 0 0; font-size: 26px; letter-spacing: -.03em; }
.top-meta { display: flex; gap: 8px; align-items: center; font-size: 12px; }
.top-meta span { padding: 7px 10px; border-radius: 5px; border: 1px solid var(--line); background: white; }
.top-meta .mode { color: var(--blue); border-color: #bccbff; background: var(--blue-soft); font-weight: 700; }
.top-meta .fixture { color: var(--amber); background: #fff8eb; border-color: #f6d795; }
.variant { max-width: 1440px; margin: auto; }
.variant h2, .variant h3, .variant p { margin-top: 0; }
code { font-family: "SFMono-Regular", Consolas, monospace; font-size: 11px; }

/* A — business-summary first */
.variant-a { display: grid; gap: 14px; }
.a-status { display: grid; grid-template-columns: 1fr 210px; gap: 24px; background: white; border: 1px solid var(--line); padding: 24px 28px; }
.a-status h2 { margin: 7px 0 8px; font-size: 24px; letter-spacing: -.025em; }
.a-status p { margin: 0; color: var(--muted); line-height: 1.65; }
.confidence-block { border-left: 1px solid var(--line); padding-left: 24px; display: flex; flex-direction: column; justify-content: center; }
.confidence-block span { color: var(--muted); font-size: 12px; }
.confidence-block strong { color: var(--amber); font-family: "SFMono-Regular", monospace; font-size: 23px; margin: 6px 0 4px; }
.confidence-block small { color: var(--muted); line-height: 1.45; }
.summary-strip { display: grid; grid-template-columns: repeat(3, 1fr); border: 1px solid var(--line); background: white; }
.summary-strip article { padding: 18px 22px; min-height: 92px; border-right: 1px solid var(--line); display: flex; flex-direction: column; gap: 5px; }
.summary-strip article:last-child { border: 0; }
.summary-strip span, .summary-strip small { color: var(--muted); font-size: 12px; }
.summary-strip b { font-size: 16px; }
.a-body { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .7fr); gap: 14px; }
.trace-card, .draft-card { background: white; border: 1px solid var(--line); padding: 22px; }
.section-head { display: flex; justify-content: space-between; align-items: end; gap: 18px; }
.section-head h3, .draft-card h3 { margin: 5px 0 0; font-size: 17px; }
.section-head code { color: var(--muted); }
.trace-line { margin: 30px 0 24px; display: grid; grid-template-columns: repeat(3, 1fr); position: relative; }
.trace-line::before { content: ""; position: absolute; height: 1px; background: #aeb8cb; left: 8%; right: 8%; top: 14px; }
.trace-node { position: relative; display: grid; justify-items: center; gap: 5px; font-family: "SFMono-Regular", monospace; }
.trace-node .node-index { z-index: 1; width: 28px; height: 28px; display: grid; place-items: center; border-radius: 50%; color: white; background: var(--blue); font-size: 11px; }
.trace-node.bad .node-index { background: var(--red); box-shadow: 0 0 0 5px var(--red-soft); }
.trace-node b { font-size: 12px; }.trace-node small { color: var(--muted); }
.finding { display: grid; grid-template-columns: 12px 1fr auto; gap: 12px; align-items: start; padding: 14px; background: var(--red-soft); border: 1px solid #f5c9c4; }
.finding-mark { width: 8px; height: 8px; margin-top: 4px; border-radius: 50%; background: var(--red); }
.finding p { margin: 3px 0 0; color: #7a271a; font-size: 13px; }.finding code { color: #9f3a2d; }
.draft-card ol, .b-bottom ol { margin: 18px 0; padding-left: 20px; color: #344054; line-height: 1.65; font-size: 13px; }
.draft-card li + li, .b-bottom li + li { margin-top: 8px; }
.draft-state { border-top: 1px solid var(--line); padding-top: 14px; }.draft-state span, .candidate-chip { color: var(--blue); font-family: "SFMono-Regular", monospace; font-size: 11px; font-weight: 700; }
.draft-state p { margin: 4px 0 0; font-size: 12px; color: var(--muted); }
.evidence-fold { background: white; border: 1px solid var(--line); }
.evidence-fold summary { cursor: pointer; padding: 14px 18px; font-size: 13px; font-weight: 700; }
.evidence-table { border-top: 1px solid var(--line); display: grid; grid-template-columns: repeat(4, 1fr); }
.evidence-table > div { padding: 14px 18px; display: grid; gap: 5px; border-right: 1px solid var(--line); }
.evidence-table span { color: var(--muted); font-size: 11px; }.evidence-table b { font-size: 12px; }

/* B — developer-evidence first */
.variant-b { display: grid; grid-template-columns: 210px minmax(520px, 1fr) 280px; min-height: 650px; background: white; border: 1px solid var(--line); }
.b-rail { padding: 24px 20px; color: #e9edfa; background: #172033; }
.b-rail .section-label { color: #9ea9c2; }.b-rail h2 { margin: 8px 0 24px; line-height: 1.3; }
.rail-flow { display: grid; gap: 0; }
.rail-node { display: grid; grid-template-columns: 30px 1fr; gap: 10px; min-height: 72px; position: relative; }
.rail-node::after { content: ""; position: absolute; left: 12px; top: 26px; bottom: -5px; width: 1px; background: #43506e; }
.rail-node:last-child::after { display: none; }
.rail-node > span { z-index: 1; width: 25px; height: 25px; display: grid; place-items: center; border: 1px solid #6f7c99; background: #172033; font: 10px "SFMono-Regular", monospace; }
.rail-node.bad > span { color: white; border-color: var(--red); background: var(--red); }
.rail-node div { display: grid; gap: 4px; align-content: start; }.rail-node b { font: 12px "SFMono-Regular", monospace; }.rail-node small { color: #9ea9c2; }
.rail-scope { margin-top: 30px; padding-top: 18px; border-top: 1px solid #34405a; display: grid; gap: 6px; }
.rail-scope span, .rail-scope small { color: #9ea9c2; font-size: 11px; }.rail-scope b { color: #cbd5ff; font: 11px "SFMono-Regular", monospace; }
.b-main { padding: 26px 28px; border-right: 1px solid var(--line); }
.b-title { display: flex; justify-content: space-between; gap: 18px; align-items: start; padding-bottom: 20px; border-bottom: 1px solid var(--line); }
.b-title h2 { margin: 6px 0 0; font-size: 21px; }.confidence-pill { color: var(--amber); border: 1px solid #e9c56e; background: #fff8e8; padding: 7px 9px; font: 10px "SFMono-Regular", monospace; }
.evidence-timeline { margin: 24px 0; }
.evidence-timeline article { display: grid; grid-template-columns: 58px 18px 1fr; gap: 10px; min-height: 90px; }
.evidence-timeline time { color: var(--muted); font: 11px "SFMono-Regular", monospace; padding-top: 2px; }
.timeline-line { position: relative; }.timeline-line::before { content: ""; position: absolute; left: 7px; top: 8px; bottom: -5px; width: 1px; background: var(--line); }.evidence-timeline article:last-child .timeline-line::before { display: none; }
.timeline-line span { position: relative; display: block; width: 9px; height: 9px; border-radius: 50%; background: var(--blue); margin-top: 3px; }
.evidence-timeline article.anomaly .timeline-line span { background: var(--red); box-shadow: 0 0 0 4px var(--red-soft); }
.evidence-timeline b { font-size: 13px; }.evidence-timeline p { margin: 4px 0 7px; color: var(--muted); font-size: 12px; }.evidence-timeline code { color: var(--blue); }
.b-bottom { display: grid; grid-template-columns: 1.2fr .8fr; gap: 14px; padding-top: 20px; border-top: 1px solid var(--line); }
.b-bottom section { padding: 16px; background: var(--soft); }.boundary-panel { border: 1px solid #f2cfca; background: var(--red-soft) !important; }.boundary-panel p { margin: 8px 0; color: #7a271a; font-size: 12px; line-height: 1.55; }.boundary-panel b { color: var(--red); font-size: 12px; }
.b-summary { padding: 26px 22px; background: #fafbfc; }.b-summary h3 { margin: 7px 0 22px; font-size: 20px; }.b-summary dl { margin: 0; display: grid; gap: 0; }.b-summary dt { color: var(--muted); font-size: 11px; padding-top: 14px; border-top: 1px solid var(--line); }.b-summary dd { margin: 5px 0 14px; font-size: 13px; line-height: 1.5; }.b-summary button { width: 100%; margin-top: 20px; border: 0; padding: 10px; background: #d9deea; color: #7a8499; }

/* C — WeCom collaboration first */
.variant-c { display: grid; grid-template-columns: 390px minmax(0, 1fr); min-height: 680px; background: white; border: 1px solid var(--line); }
.chat-panel { background: #eef1f5; border-right: 1px solid var(--line); display: flex; flex-direction: column; }
.chat-head { padding: 18px 20px; background: white; border-bottom: 1px solid var(--line); display: flex; align-items: center; gap: 11px; }
.status-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--green); }.chat-head div { display: grid; gap: 3px; }.chat-head small { color: var(--muted); }
.messages { padding: 22px 18px; display: grid; gap: 14px; overflow: auto; }
.msg { max-width: 84%; display: grid; gap: 4px; }.msg span { color: var(--muted); font-size: 10px; }.msg p { margin: 0; padding: 11px 13px; background: white; border: 1px solid #dfe3ea; font-size: 13px; line-height: 1.55; }.msg time { color: #98a2b3; font-size: 9px; }
.msg.user { justify-self: end; }.msg.user span, .msg.user time { text-align: right; }.msg.user p { background: #dce7ff; border-color: #c2d3ff; }
.msg.result { max-width: 94%; }.msg.result p { background: white; border-left: 3px solid var(--blue); }.msg.result p + p { border-top: 0; }
.attachment { justify-self: end; display: flex; gap: 9px; align-items: center; background: white; border: 1px solid var(--line); padding: 9px 11px; width: 210px; }.attachment > span { width: 34px; height: 34px; display: grid; place-items: center; color: var(--blue); background: var(--blue-soft); font: 10px "SFMono-Regular", monospace; }.attachment div { display: grid; gap: 3px; }.attachment b { font-size: 11px; }.attachment small { color: var(--muted); font-size: 9px; }
.investigation-panel { padding: 28px 30px; }.investigation-head { display: flex; justify-content: space-between; gap: 20px; align-items: start; padding-bottom: 22px; border-bottom: 1px solid var(--line); }.investigation-head h2 { margin: 7px 0 0; font-size: 22px; }
.stage-grid { margin: 24px 0; display: grid; gap: 9px; }.stage-grid article { display: grid; grid-template-columns: 28px 1fr auto; gap: 13px; align-items: center; padding: 12px 14px; border: 1px solid var(--line); }.stage-grid article > span { width: 25px; height: 25px; display: grid; place-items: center; color: white; background: var(--blue); font: 10px "SFMono-Regular", monospace; }.stage-grid article.active { border-color: #e6c36b; background: #fffbf0; }.stage-grid article.active > span { background: var(--amber); }.stage-grid b { font-size: 13px; }.stage-grid p { margin: 3px 0 0; color: var(--muted); font-size: 11px; }.stage-grid small { color: var(--green); font: 10px "SFMono-Regular", monospace; }.stage-grid article.active small { color: var(--amber); }
.c-result { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }.c-result section { padding: 20px; border: 1px solid var(--line); background: var(--soft); }.c-result h3 { margin: 7px 0 8px; font-size: 17px; }.c-result p { color: var(--muted); font-size: 12px; line-height: 1.6; }.refs { display: flex; flex-wrap: wrap; gap: 6px; }.refs code { color: var(--blue); background: white; border: 1px solid #cbd6ff; padding: 5px 7px; }

.prototype-switcher { position: fixed; z-index: 20; left: 50%; bottom: 22px; transform: translateX(-50%); display: flex; align-items: center; gap: 14px; color: white; background: #111827; border: 1px solid #344054; border-radius: 999px; padding: 7px 9px; box-shadow: 0 12px 30px rgba(16, 24, 40, .28); }
.prototype-switcher button { width: 30px; height: 30px; border: 0; border-radius: 50%; color: white; background: #273247; cursor: pointer; }.prototype-switcher span { min-width: 170px; text-align: center; font-size: 12px; }.prototype-switcher b { color: #a9baff; }

@media (max-width: 1050px) {
  .variant-b { grid-template-columns: 180px 1fr; }.b-summary { grid-column: 1 / -1; }
  .variant-c { grid-template-columns: 340px 1fr; }.a-body { grid-template-columns: 1fr; }
}
@media (max-width: 760px) {
  .proto-page { padding: 16px 14px 82px; }.proto-topbar, .a-status, .investigation-head { align-items: start; flex-direction: column; display: flex; }
  .top-meta { flex-wrap: wrap; }.summary-strip, .evidence-table, .c-result { grid-template-columns: 1fr; }.summary-strip article { border-right: 0; border-bottom: 1px solid var(--line); }
  .variant-b, .variant-c { display: block; }.b-rail { display: none; }.b-main { border: 0; }.chat-panel { min-height: 560px; border-right: 0; border-bottom: 1px solid var(--line); }
}
</style>
