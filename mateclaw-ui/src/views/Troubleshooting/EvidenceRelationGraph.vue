<template>
  <section class="relation-view">
    <div class="relation-head">
      <div>
        <span>证据关系视图</span>
        <h4>从结论反查判据与证据</h4>
      </div>
      <small>默认聚焦结论；点击任一节点可切换反查目标</small>
    </div>

    <div v-if="!view.available" class="relation-unavailable">
      <b>本次没有形成可反查到结论的完整关系链</b>
      <span>{{ traceDisplay(view.emptyReason) }}</span>
    </div>

    <div class="relation-canvas">
      <section
        v-for="column in columns"
        :key="column.kind"
        class="relation-column"
      >
        <header><span>{{ column.order }}</span><b>{{ column.label }}</b></header>
        <div v-if="nodesByKind(column.kind).length" class="relation-node-list">
          <button
            v-for="node in nodesByKind(column.kind)"
            :key="node.nodeId"
            class="relation-node"
            :class="[
              nodeTone(node.status),
              { selected: node.nodeId === selectedNodeId, dimmed: !path.nodeIds.has(node.nodeId) },
            ]"
            @click="selectedNodeId = node.nodeId"
          >
            <span>{{ nodeStatusLabel(node.status) }}</span>
            <b>{{ node.label }}</b>
            <small>{{ traceDisplay(node.detail) }}</small>
            <code>{{ node.ref }}</code>
          </button>
        </div>
        <div v-else class="relation-column-empty">未记录</div>
      </section>
    </div>

    <div class="relation-ledger">
      <div class="relation-ledger-head">
        <b>当前反查链路</b>
        <span>{{ pathEdges.length }} 条已记录关系</span>
      </div>
      <article
        v-for="edge in pathEdges"
        :key="edge.edgeId"
        :class="`is-${edge.relation.toLowerCase()}`"
      >
        <span>{{ nodeLabel(edge.fromNodeId) }}</span>
        <div><b>{{ relationTypeLabel(edge.relation) }}</b><small>{{ edge.label }}</small></div>
        <span>{{ nodeLabel(edge.toNodeId) }}</span>
      </article>
      <p v-if="!pathEdges.length">未记录</p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  EvidenceRelationView,
  RelationNodeKind,
} from '@/api'
import {
  relationTypeLabel,
  relationUpstreamPath,
  traceDisplay,
} from './investigationTrace'

const props = defineProps<{ view: EvidenceRelationView }>()

const columns: { kind: RelationNodeKind; order: string; label: string }[] = [
  { kind: 'EVIDENCE', order: '01', label: '只读证据' },
  { kind: 'CRITERION', order: '02', label: '确定性判据' },
  { kind: 'RULE', order: '03', label: '候选规则' },
  { kind: 'CONCLUSION', order: '04', label: '结论 / 弃权' },
]

const conclusionNodeId = () => props.view.nodes.find(node => node.kind === 'CONCLUSION')?.nodeId
  ?? props.view.nodes.at(-1)?.nodeId
  ?? ''
const selectedNodeId = ref(conclusionNodeId())

watch(
  () => props.view,
  () => { selectedNodeId.value = conclusionNodeId() },
)

const path = computed(() => relationUpstreamPath(props.view.edges, selectedNodeId.value))
const pathEdges = computed(() => props.view.edges.filter(edge => path.value.edgeIds.has(edge.edgeId)))

function nodesByKind(kind: RelationNodeKind) {
  return props.view.nodes.filter(node => node.kind === kind)
}

function nodeLabel(nodeId: string) {
  return props.view.nodes.find(node => node.nodeId === nodeId)?.label ?? nodeId
}

function nodeTone(status: string) {
  if (['ANOMALY', 'SATISFIED', 'FIRED', 'LOCATED'].includes(status)) return 'is-positive'
  if (['EXCLUDED', 'NOT_FIRED'].includes(status)) return 'is-neutral'
  if (['MISSING', 'UNEVALUATED', 'INSUFFICIENT_EVIDENCE'].includes(status)) return 'is-warning'
  return 'is-normal'
}

function nodeStatusLabel(status: string) {
  return ({
    NORMAL: '正常',
    ANOMALY: '异常',
    MISSING: '缺失',
    SATISFIED: '已满足',
    EXCLUDED: '已排除',
    UNEVALUATED: '未求值',
    FIRED: '已命中',
    NOT_FIRED: '未命中',
    LOCATED: '已定位',
    HYPOTHESIS: '根因假设',
    INSUFFICIENT_EVIDENCE: '证据不足',
  } as Record<string, string>)[status] ?? traceDisplay(status)
}
</script>

<style scoped>
.relation-view{display:flex;min-width:0;flex-direction:column;gap:16px}
.relation-head{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}
.relation-head span{color:var(--mc-text-secondary);font-size:var(--mc-text-xs);font-weight:700;letter-spacing:.1em;text-transform:uppercase}
.relation-head h4{margin:5px 0 0;font-size:var(--mc-text-base)}
.relation-head small{max-width:360px;color:var(--mc-text-tertiary);font-size:var(--mc-text-xs);line-height:1.5;text-align:right}
.relation-unavailable{display:flex;align-items:center;gap:10px;padding:10px 12px;border:1px solid var(--mc-status-warning-border,var(--mc-border));border-radius:var(--mc-radius-sm);color:var(--mc-status-warning-text);background:var(--mc-status-warning-bg);font-size:var(--mc-text-xs)}
.relation-unavailable span{color:var(--mc-text-secondary)}
.relation-canvas{box-sizing:border-box;display:grid;width:100%;min-width:0;grid-template-columns:repeat(4,minmax(180px,1fr));gap:12px;padding:14px;border:1px solid var(--mc-border);border-radius:var(--mc-radius-sm);background:var(--mc-bg-muted);overflow-x:auto}
.relation-column{position:relative;min-width:0}
.relation-column:not(:last-child)::after{position:absolute;top:16px;right:-10px;content:'→';color:var(--mc-text-tertiary);font-size:var(--mc-text-sm)}
.relation-column>header{display:flex;align-items:center;gap:7px;margin-bottom:10px;color:var(--mc-text-secondary);font-size:var(--mc-text-xs)}
.relation-column>header span{display:inline-grid;place-items:center;width:22px;height:22px;border-radius:50%;color:var(--mc-primary);background:var(--mc-primary-soft,var(--mc-bg-elevated));font-family:var(--mc-mono,monospace)}
.relation-node-list{display:flex;flex-direction:column;gap:8px}
.relation-node{width:100%;padding:10px;border:1px solid var(--mc-border);border-left:3px solid var(--mc-primary);border-radius:var(--mc-radius-xs);background:var(--mc-bg-elevated);color:var(--mc-text-primary);text-align:left;cursor:pointer;transition:opacity .15s,border-color .15s,box-shadow .15s}
.relation-node:hover,.relation-node.selected{border-color:var(--mc-primary);box-shadow:var(--mc-shadow-soft)}
.relation-node.dimmed{opacity:.35}
.relation-node>span{display:inline-flex;padding:2px 6px;border-radius:999px;color:var(--mc-text-secondary);background:var(--mc-bg-muted);font-size:10px}
.relation-node>b,.relation-node>small,.relation-node>code{display:block}
.relation-node>b{margin-top:7px;font-size:var(--mc-text-xs);line-height:1.45}
.relation-node>small{margin-top:4px;color:var(--mc-text-secondary);font-size:11px;line-height:1.45}
.relation-node>code{margin-top:7px;color:var(--mc-primary);font-size:10px;overflow-wrap:anywhere}
.relation-node.is-positive{border-left-color:var(--mc-success)}
.relation-node.is-warning{border-left-color:var(--mc-warning)}
.relation-node.is-neutral{border-left-color:var(--mc-text-tertiary)}
.relation-column-empty{padding:16px;border:1px dashed var(--mc-border);border-radius:var(--mc-radius-xs);color:var(--mc-text-tertiary);font-size:var(--mc-text-xs);text-align:center}
.relation-ledger{border:1px solid var(--mc-border);border-radius:var(--mc-radius-sm);overflow:hidden}
.relation-ledger-head{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;background:var(--mc-bg-muted);font-size:var(--mc-text-xs)}
.relation-ledger-head span{color:var(--mc-text-secondary)}
.relation-ledger article{display:grid;grid-template-columns:minmax(0,1fr) 140px minmax(0,1fr);align-items:center;gap:12px;padding:10px 12px;border-top:1px solid var(--mc-border-light);font-size:var(--mc-text-xs)}
.relation-ledger article>span{overflow-wrap:anywhere}
.relation-ledger article>div{display:flex;flex-direction:column;align-items:center;color:var(--mc-primary);text-align:center}
.relation-ledger article>div::before{width:100%;height:1px;margin-bottom:-9px;content:'';background:currentColor;opacity:.35}
.relation-ledger article>div b{position:relative;padding:1px 5px;background:var(--mc-bg-elevated)}
.relation-ledger article>div small{margin-top:5px;color:var(--mc-text-tertiary);line-height:1.3}
.relation-ledger article.is-refutes>div{color:var(--mc-text-tertiary)}
.relation-ledger article.is-blocks>div{color:var(--mc-warning)}
.relation-ledger>p{margin:0;padding:18px;color:var(--mc-text-tertiary);font-size:var(--mc-text-xs);text-align:center}
@media(max-width:900px){.relation-head{flex-direction:column}.relation-head small{text-align:left}.relation-canvas{grid-template-columns:repeat(4,220px)}.relation-ledger article{grid-template-columns:1fr}.relation-ledger article>div{align-items:flex-start;text-align:left}}
</style>
