<!--
  PROTOTYPE — throwaway after review.

  The developer projection of one Diagnosis: call chain, anomaly point, evidence
  refs, criteria, capability boundary. It lives in its own file because the file
  boundary mirrors the contract boundary — this panel is exactly what
  `DeveloperEvidenceView` has to carry, and nothing else.
-->
<template>
  <div class="dev-panel">
    <aside class="rail">
      <span class="label">调用链</span>
      <h3>{{ scene.railHeadline }}</h3>
      <div v-if="scene.traceNodes.length">
        <div
          v-for="(node, index) in scene.traceNodes"
          :key="node.hop"
          class="rnode"
          :class="{ bad: node.bad }"
        >
          <span>{{ String(index + 1).padStart(2, '0') }}</span>
          <div><b>{{ node.service }}</b><small>{{ node.duration }}</small></div>
        </div>
      </div>
      <p v-else class="rail-empty">{{ scene.traceEmpty }}</p>
      <div class="rscope">
        <span>影响范围</span><b>{{ scene.blastRadius }}</b><small>{{ scene.impactNote }}</small>
      </div>
    </aside>

    <main class="dev-main">
      <div class="dev-title">
        <div><span class="label">开发证据台</span><h3>{{ scene.devHeadline }}</h3></div>
        <span class="pill" :class="confidence">{{ confidence }} · {{ conclusionLabel }}</span>
      </div>

      <div class="tl">
        <article v-for="event in scene.evidenceEvents" :key="event.ref" :class="event.tone">
          <time>{{ event.time }}</time>
          <div class="tlline"><span /></div>
          <div><b>{{ event.title }}</b><p>{{ event.detail }}</p><code>{{ event.ref }}</code></div>
        </article>
      </div>

      <div class="dev-bottom">
        <section>
          <span class="label">排查步骤草稿</span>
          <ol v-if="scene.draft.steps.length">
            <li v-for="step in scene.draft.steps" :key="step">{{ step }}</li>
          </ol>
          <p v-else class="nodraft">{{ scene.draft.emptyReason }}</p>
          <div class="dstate"><span>{{ scene.draft.state }}</span><p>{{ scene.draft.stateNote }}</p></div>
        </section>
        <section class="boundary">
          <span class="label">系统明确做不到</span>
          <p>{{ scene.boundary }}</p>
          <b>{{ scene.boundaryRule }}</b>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
/** Exactly what `DeveloperEvidenceView` has to carry — nothing else. */
interface TraceNode { hop: string; service: string; duration: string; bad: boolean }
interface EvidenceEvent { time: string; title: string; detail: string; ref: string; tone: string }
interface DraftView { steps: readonly string[]; emptyReason: string; state: string; stateNote: string }
interface DeveloperScene {
  railHeadline: string
  traceNodes: readonly TraceNode[]
  traceEmpty: string
  blastRadius: string
  impactNote: string
  devHeadline: string
  evidenceEvents: readonly EvidenceEvent[]
  draft: DraftView
  boundary: string
  boundaryRule: string
}

defineProps<{
  scene: DeveloperScene
  confidence: string
  conclusionLabel: string
}>()
</script>

<style scoped>
.dev-panel {
  --mono: ui-monospace, "SFMono-Regular", Consolas, monospace;
  display: grid; grid-template-columns: 210px minmax(0, 1fr);
  background: var(--el-bg-color, #fff); border: 1px solid var(--line, #dfe3ea);
}
.label { display: block; color: var(--muted, #667085); font-size: 11px; font-weight: 700; letter-spacing: .11em; text-transform: uppercase; }

.rail { padding: 22px 20px; color: #e9edfa; background: #172033; }
.rail .label { color: #9ea9c2; }
.rail h3 { margin: 8px 0 22px; font-size: 18px; line-height: 1.35; white-space: pre-line; }
.rnode { display: grid; grid-template-columns: 30px 1fr; gap: 10px; min-height: 70px; position: relative; }
.rnode::after { content: ""; position: absolute; left: 12px; top: 26px; bottom: -5px; width: 1px; background: #43506e; }
.rnode:last-child::after { display: none; }
.rnode > span { z-index: 1; width: 25px; height: 25px; display: grid; place-items: center; border: 1px solid #6f7c99; background: #172033; font: 10px var(--mono); }
.rnode.bad > span { color: #fff; border-color: #d92d20; background: #d92d20; }
.rnode div { display: grid; gap: 4px; align-content: start; }
.rnode b { font: 12px var(--mono); } .rnode small { color: #9ea9c2; }
.rail-empty { color: #9ea9c2; font-size: 12px; line-height: 1.6; border: 1px dashed #43506e; padding: 12px; }
.rscope { margin-top: 26px; padding-top: 18px; border-top: 1px solid #34405a; display: grid; gap: 6px; }
.rscope span, .rscope small { color: #9ea9c2; font-size: 11px; }
.rscope b { color: #cbd5ff; font: 11px var(--mono); }

.dev-main { padding: 24px 26px; min-width: 0; }
.dev-title { display: flex; justify-content: space-between; gap: 18px; align-items: flex-start; padding-bottom: 18px; border-bottom: 1px solid var(--line, #dfe3ea); }
.dev-title h3 { margin: 6px 0 0; font-size: 20px; }
.pill { border: 1px solid var(--line, #dfe3ea); padding: 7px 9px; font: 10px var(--mono); white-space: nowrap; }
.pill.HIGH { color: #138a58; border-color: #a9e0c4; background: #ecfdf3; }
.pill.MEDIUM { color: #b54708; border-color: #e9c56e; background: #fff8eb; }
.pill.LOW { color: #667085; border-color: #d3d8e0; background: #f2f4f7; }

.tl { margin: 20px 0 22px; }
.tl article { display: grid; grid-template-columns: 64px 18px 1fr; gap: 10px; min-height: 86px; }
.tl time { color: var(--muted, #667085); font: 11px var(--mono); padding-top: 2px; }
.tlline { position: relative; }
.tlline::before { content: ""; position: absolute; left: 7px; top: 8px; bottom: -5px; width: 1px; background: var(--line, #dfe3ea); }
.tl article:last-child .tlline::before { display: none; }
.tlline span { position: relative; display: block; width: 9px; height: 9px; border-radius: 50%; background: #2f5cf5; margin-top: 3px; }
.tl article.anomaly .tlline span { background: #d92d20; box-shadow: 0 0 0 4px #fff2f0; }
.tl article.good .tlline span { background: #138a58; box-shadow: 0 0 0 4px #ecfdf3; }
.tl article.unknown .tlline span { background: #fff; border: 1.5px dashed #667085; }
.tl b { font-size: 13px; } .tl p { margin: 4px 0 7px; color: var(--muted, #667085); font-size: 12px; } .tl code { color: #2f5cf5; font-family: var(--mono); font-size: 11px; }

.dev-bottom { display: grid; grid-template-columns: 1.2fr .8fr; gap: 14px; padding-top: 18px; border-top: 1px solid var(--line, #dfe3ea); }
.dev-bottom section { padding: 16px; background: #f5f7fb; }
.dev-bottom ol { margin: 14px 0; padding-left: 20px; font-size: 13px; line-height: 1.65; }
.dev-bottom li + li { margin-top: 8px; }
.nodraft { margin: 14px 0; padding: 13px; color: var(--muted, #667085); font-size: 12.5px; line-height: 1.65; background: #f2f4f7; border: 1px dashed #d3d8e0; }
.dstate { border-top: 1px solid var(--line, #dfe3ea); padding-top: 12px; }
.dstate span { color: #2f5cf5; font-family: var(--mono); font-size: 11px; font-weight: 700; }
.dstate p { margin: 4px 0 0; font-size: 12px; color: var(--muted, #667085); }
.boundary { border: 1px solid #f2cfca; background: #fff2f0 !important; }
.boundary p { margin: 8px 0; color: #7a271a; font-size: 12px; line-height: 1.55; }
.boundary b { color: #d92d20; font-size: 12px; }

@media (max-width: 1050px) {
  .dev-panel { grid-template-columns: 1fr; }
  .rail { display: none; }
  .dev-bottom { grid-template-columns: 1fr; }
}
</style>
