<template>
  <nav v-if="items.length" class="five-question-rail" aria-label="日常排障五问">
    <header class="fq-head">
      <span class="fq-kicker">日常五问</span>
      <span class="fq-note">从告警进同一张排障单后，按这五问推进；证据不够就停，人不改生产。</span>
    </header>
    <ol class="fq-list">
      <li
        v-for="item in items"
        :key="item.index"
        class="fq-item"
        :class="item.state.toLowerCase()"
      >
        <div class="fq-top">
          <span class="fq-index">第 {{ item.index }} 问</span>
          <span class="fq-state">{{ stateLabel(item.state) }}</span>
        </div>
        <strong>{{ item.title }}</strong>
        <small>{{ item.hint }}</small>
        <p>{{ item.answer }}</p>
      </li>
    </ol>
  </nav>
</template>

<script setup lang="ts">
import type { FiveQuestionItem, FiveQuestionState } from './fiveQuestionProgress'

defineProps<{
  items: FiveQuestionItem[]
}>()

function stateLabel(state: FiveQuestionState): string {
  switch (state) {
    case 'DONE':
      return '已确认'
    case 'ACTIVE':
      return '进行中'
    case 'STOPPED':
      return '已停止'
    default:
      return '待开始'
  }
}
</script>

<style scoped>
.five-question-rail {
  margin: 0 0 14px;
  padding: 14px 16px 16px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-md);
  background: var(--mc-bg);
}
.fq-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 14px;
  margin-bottom: 12px;
}
.fq-kicker {
  color: var(--mc-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.fq-note {
  color: var(--mc-text-tertiary);
  font-size: 12px;
  line-height: 1.5;
}
.fq-list {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.fq-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 132px;
  padding: 12px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-sm, 8px);
  background: var(--mc-bg-muted);
}
.fq-item.done {
  border-color: color-mix(in srgb, var(--mc-primary) 28%, var(--mc-border));
  background: color-mix(in srgb, var(--mc-primary) 6%, var(--mc-bg));
}
.fq-item.active {
  border-color: color-mix(in srgb, var(--mc-warning, #c47d00) 40%, var(--mc-border));
  background: color-mix(in srgb, var(--mc-warning, #c47d00) 8%, var(--mc-bg));
}
.fq-item.stopped {
  border-color: color-mix(in srgb, var(--mc-danger, #b42318) 30%, var(--mc-border));
}
.fq-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}
.fq-index {
  color: var(--mc-text-tertiary);
  font-size: 11px;
  font-weight: 700;
}
.fq-state {
  padding: 1px 6px;
  border-radius: 999px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg);
  font-size: 10px;
  font-weight: 700;
}
.fq-item strong {
  font-size: 13px;
  line-height: 1.35;
}
.fq-item small {
  color: var(--mc-text-tertiary);
  font-size: 11px;
  line-height: 1.4;
}
.fq-item p {
  margin: auto 0 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
@media (max-width: 1100px) {
  .fq-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .fq-list {
    grid-template-columns: 1fr;
  }
}
</style>
