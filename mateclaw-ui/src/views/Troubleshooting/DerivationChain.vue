<template>
  <div class="chain">
    <!-- shape of the reasoning before the detail -->
    <div class="tally">
      <div class="tg">
        <span class="tn">{{ diagnosis.evidence.length }}</span><span class="tl">项证据</span>
      </div>
      <div class="tg">
        <span class="tn">{{ diagnosis.triggeredSignals.length }}</span><span class="tl">个信号成立</span>
      </div>
      <div class="tg">
        <span class="tn" :class="diagnosis.abstained ? 'abst' : 'win'">
          {{ diagnosis.abstained ? '弃权' : diagnosis.confidence }}
        </span>
        <span class="tl">{{ diagnosis.abstained ? '未产出根因' : '置信档位' }}</span>
      </div>
    </div>

    <!-- 1. evidence -->
    <section class="link">
      <div class="lrail"><span class="lnode">1</span><span class="lconn" /></div>
      <div class="lbody">
        <header class="lhd">
          <span class="lt">证据采集</span>
          <span class="lf">EvidenceResult · 高亮字段被成立判据读取</span>
        </header>

        <article
          v-for="ev in diagnosis.evidence"
          :key="ev.queryId"
          class="ev"
          :class="[ev.status.toLowerCase(), { dim: !feedsASignal(ev.queryId) }]"
        >
          <div class="evh">
            <span class="qid">{{ ev.queryId }}</span>
            <span class="ns">{{ ev.namespace }}::</span>
            <span class="evsum">{{ ev.summary }}</span>
            <span class="evst" :class="ev.status">{{ ev.status }}</span>
          </div>

          <div v-if="observedEntries(ev).length" class="obsline">
            <span
              v-for="[field, value] in observedEntries(ev)"
              :key="field"
              class="ob"
              :class="{ used: usedFields(ev.queryId).has(field) }"
            >{{ field }} <b>{{ value }}</b></span>
          </div>
          <div v-else class="obsline">
            <span class="ob empty">observed 为空 · 取证失败</span>
          </div>

          <button class="qtoggle" type="button" @click="toggle(ev.queryId)">
            <span class="caret" :class="{ open: expanded.has(ev.queryId) }">›</span>
            查看查询与采集来源
          </button>
          <div v-if="expanded.has(ev.queryId)" class="qbox">
            <pre class="q">{{ ev.query || '（SOP 未记录查询语句）' }}</pre>
            <div class="qmeta">
              <span>source {{ ev.source }}</span>
              <span>collectedAt {{ ev.collectedAt }}</span>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 2. signals -->
    <section class="link">
      <div class="lrail"><span class="lnode">2</span><span class="lconn" /></div>
      <div class="lbody">
        <header class="lhd">
          <span class="lt">判据求值 → 信号</span>
          <span class="lf">纯 Java pattern-matching · 零 LLM</span>
        </header>
        <div v-if="diagnosis.triggeredSignals.length" class="sigs">
          <span v-for="s in diagnosis.triggeredSignals" :key="s" class="sig">{{ s }}</span>
        </div>
        <p v-else class="empty-note">
          没有任何判据成立。这可能是证据缺失（假设未验证），也可能是判据求值为假（假设已排除）——
          两者含义不同，需要看上方每条证据的状态。
        </p>
      </div>
    </section>

    <!-- 3. conclusion -->
    <section class="link">
      <div class="lrail">
        <span class="lnode final" :class="{ abst: diagnosis.abstained }">
          {{ diagnosis.abstained ? '!' : '✓' }}
        </span>
      </div>
      <div class="lbody">
        <header class="lhd">
          <span class="lt">{{ diagnosis.abstained ? '弃权结论' : '根因结论' }}</span>
          <span class="lf">rootCause + confidence</span>
        </header>
        <div class="concl" :class="{ abst: diagnosis.abstained }">
          <p class="rc">{{ diagnosis.rootCause }}</p>
          <p class="sum">{{ diagnosis.summary }}</p>
          <p v-if="diagnosis.abstained" class="abst-why">
            系统未强凑结论。恢复动作也随之为空——契约保证弃权时不输出任何处置建议。
          </p>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { Diagnosis, EvidenceResult } from '@/api'

const props = defineProps<{ diagnosis: Diagnosis }>()

const expanded = ref(new Set<string>())

function toggle(queryId: string) {
  const next = new Set(expanded.value)
  next.has(queryId) ? next.delete(queryId) : next.add(queryId)
  expanded.value = next
}

function observedEntries(ev: EvidenceResult): [string, unknown][] {
  return Object.entries(ev.observed ?? {})
}

/**
 * Which evidence rows actually carried the conclusion.
 *
 * The backend does not ship the criterion-to-evidence mapping yet, so this
 * highlights on the honest signal available today: a non-MISSING row whose
 * anomaly matches a triggered signal. Once the API exposes anomalyCriteria we
 * can highlight the exact fields each criterion read instead of inferring.
 */
function feedsASignal(queryId: string): boolean {
  if (!props.diagnosis.triggeredSignals.length) return false
  const ev = props.diagnosis.evidence.find((e) => e.queryId === queryId)
  return !!ev && ev.status !== 'MISSING'
}

function usedFields(queryId: string): Set<string> {
  const ev = props.diagnosis.evidence.find((e) => e.queryId === queryId)
  if (!ev || ev.status !== 'ANOMALY') return new Set()
  return new Set(Object.keys(ev.observed ?? {}))
}
</script>

<style scoped>
.chain { display: flex; flex-direction: column; }

.tally {
  display: flex; gap: 0; flex-wrap: wrap; align-items: center;
  background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px; padding: 10px 15px; margin-bottom: 12px;
}
.tg { display: flex; align-items: baseline; gap: 7px; padding-right: 18px; margin-right: 18px;
  border-right: 1px solid var(--el-border-color-lighter); }
.tg:last-child { border-right: none; margin-right: 0; padding-right: 0; }
.tn { font-family: var(--mc-mono, monospace); font-size: 17px; font-weight: 700;
  color: var(--el-text-color-primary); font-variant-numeric: tabular-nums; }
.tn.win { color: var(--el-color-success); font-size: 14px; }
.tn.abst { color: var(--el-color-warning); font-size: 14px; }
.tl { font-size: 11px; color: var(--el-text-color-secondary); }

.link { display: flex; gap: 13px; padding-bottom: 11px; }
.link:last-child { padding-bottom: 0; }
.lrail { display: flex; flex-direction: column; align-items: center; flex-shrink: 0; width: 28px; }
.lnode {
  width: 28px; height: 28px; border-radius: 9px; display: flex; align-items: center;
  justify-content: center; font-family: var(--mc-mono, monospace); font-size: 11px;
  font-weight: 700; background: var(--el-color-primary-light-9); color: var(--el-color-primary);
}
.lnode.final { background: var(--el-color-success); color: #fff; }
.lnode.final.abst { background: var(--el-color-warning); }
.lconn { flex: 1; width: 2px; background: var(--el-border-color-lighter); margin: 3px 0; border-radius: 2px; }
.lbody { flex: 1; min-width: 0; }
.lhd { display: flex; align-items: baseline; gap: 8px; margin-bottom: 7px; flex-wrap: wrap; }
.lt { font-size: 12.5px; font-weight: 650; color: var(--el-text-color-primary); }
.lf { font-family: var(--mc-mono, monospace); font-size: 10px; color: var(--el-text-color-placeholder); }

.ev {
  border: 1px solid var(--el-border-color-lighter); border-radius: 8px;
  background: var(--el-bg-color); overflow: hidden;
}
.ev + .ev { margin-top: 7px; }
.ev.anomaly { border-color: var(--el-color-danger-light-5); }
.ev.missing { border-color: var(--el-color-warning-light-5); }
.ev.dim { opacity: 0.66; }
.evh { padding: 8px 11px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.qid {
  font-family: var(--mc-mono, monospace); font-size: 10px; font-weight: 700;
  color: var(--el-color-primary); background: var(--el-color-primary-light-9);
  border-radius: 4px; padding: 2px 6px;
}
.ns { font-family: var(--mc-mono, monospace); font-size: 10px; color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light); border-radius: 4px; padding: 2px 6px; }
.evsum { font-size: 12px; font-weight: 600; color: var(--el-text-color-primary); }
.evst { font-size: 9.5px; font-weight: 700; font-family: var(--mc-mono, monospace);
  padding: 2px 7px; border-radius: 4px; margin-left: auto; }
.evst.ANOMALY { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.evst.NORMAL { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.evst.MISSING { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }

.obsline { padding: 0 11px 9px; display: flex; gap: 6px; flex-wrap: wrap; }
.ob {
  font-family: var(--mc-mono, monospace); font-size: 10.5px;
  border: 1px solid var(--el-border-color-lighter); border-radius: 5px; padding: 2px 7px;
  background: var(--el-fill-color-lighter); color: var(--el-text-color-regular);
}
.ob b { color: var(--el-text-color-primary); }
.ob.used { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9);
  color: var(--el-color-primary); }
.ob.used b { color: var(--el-color-primary); }
.ob.empty { border-style: dashed; color: var(--el-color-warning); border-color: var(--el-color-warning-light-5); }

.qtoggle {
  border: none; background: transparent; color: var(--el-text-color-secondary);
  font-family: var(--mc-mono, monospace); font-size: 10px; cursor: pointer;
  padding: 5px 11px; display: flex; align-items: center; gap: 6px; width: 100%;
  border-top: 1px solid var(--el-border-color-lighter);
}
.qtoggle:hover { color: var(--el-color-primary); background: var(--el-fill-color-lighter); }
.caret { display: inline-block; transition: transform 0.18s; }
.caret.open { transform: rotate(90deg); }
.qbox { border-top: 1px solid var(--el-border-color-lighter); }
.q {
  margin: 0; background: #0e1420; color: #c9d6ef; font-family: var(--mc-mono, monospace);
  font-size: 10.5px; line-height: 1.7; padding: 9px 11px; overflow-x: auto; white-space: pre;
}
.qmeta {
  padding: 6px 11px; background: var(--el-fill-color-lighter); font-family: var(--mc-mono, monospace);
  font-size: 10px; color: var(--el-text-color-secondary); display: flex; gap: 12px; flex-wrap: wrap;
}

.sigs { display: flex; gap: 6px; flex-wrap: wrap; }
.sig {
  font-family: var(--mc-mono, monospace); font-size: 11px; font-weight: 600;
  padding: 3px 9px; border-radius: 6px; background: var(--el-color-success-light-9);
  color: var(--el-color-success); border: 1px solid var(--el-color-success-light-7);
}
.empty-note {
  font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.6;
  background: var(--el-fill-color-lighter); border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px; padding: 9px 11px; margin: 0;
}

.concl {
  border: 1px solid var(--el-color-success-light-7); background: var(--el-color-success-light-9);
  border-radius: 8px; padding: 11px 13px;
}
.concl.abst { border-color: var(--el-color-warning-light-7); background: var(--el-color-warning-light-9); }
.rc { margin: 0; font-size: 13px; font-weight: 650; color: var(--el-text-color-primary); line-height: 1.5; }
.sum { margin: 5px 0 0; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.55; }
.abst-why { margin: 8px 0 0; font-size: 11.5px; color: var(--el-color-warning); line-height: 1.55; }
</style>
