<template>
  <el-popover
    v-model:visible="panelOpen"
    placement="bottom-end"
    trigger="click"
    :width="760"
    :show-arrow="false"
    popper-class="workbench-capability-popper"
  >
    <template #reference>
      <el-button
        :size="size"
        plain
        class="capability-trigger"
        :class="{ 'is-open': panelOpen }"
        aria-haspopup="dialog"
        :aria-expanded="panelOpen"
        aria-controls="workbench-capability-panel"
      >
        更多能力
        <el-icon class="trigger-chevron"><ArrowDown /></el-icon>
      </el-button>
    </template>

    <section
      id="workbench-capability-panel"
      ref="panelRef"
      class="capability-panel"
      aria-label="智能排障管理能力"
      @keydown.esc.stop="panelOpen = false"
    >
      <header class="panel-header">
        <div>
          <span class="panel-eyebrow">管理能力</span>
          <h2>配置智能排障所需的规则、证据与评估能力</h2>
          <p>日常排障仍在当前页面进行；这里用于低频配置、验证和复盘。</p>
        </div>
        <div class="panel-header-actions">
          <el-tag size="small" effect="plain">管理员</el-tag>
          <button type="button" class="panel-close" aria-label="关闭更多能力" @click="panelOpen = false">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </header>

      <div class="panel-body">
        <nav class="capability-nav" aria-label="能力分类">
          <section v-for="group in WORKBENCH_CAPABILITY_GROUPS" :key="group.key" class="capability-group">
            <h3>{{ group.label }}</h3>
            <button
              v-for="item in group.items"
              :key="item.command"
              type="button"
              class="capability-nav-item"
              :class="{ active: selectedCommand === item.command }"
              :data-command="item.command"
              :aria-current="selectedCommand === item.command ? 'true' : undefined"
              :aria-expanded="item.expandable ? selectedCommand === item.command : undefined"
              @click="selectCapability(item)"
              @focus="selectCapability(item)"
              @keydown.down.prevent="moveCapabilityFocus($event, 1)"
              @keydown.up.prevent="moveCapabilityFocus($event, -1)"
              @keydown.right.prevent="focusPreviewAction"
            >
              <span>
                <strong>{{ item.label }}</strong>
                <small>{{ item.description }}</small>
              </span>
              <el-icon v-if="item.expandable"><ArrowRight /></el-icon>
            </button>
          </section>
        </nav>

        <main class="capability-preview">
          <header class="preview-header">
            <span>{{ selectedGroupLabel }}</span>
            <h3>{{ selectedItem.label }}</h3>
            <p>{{ selectedItem.description }}</p>
          </header>

          <template v-if="selectedCommand === 'evidence-catalog'">
            <div class="destination-grid" aria-label="取证查询目录工作区">
              <button
                v-for="destination in EVIDENCE_CATALOG_DESTINATIONS"
                :key="destination.tab"
                type="button"
                class="destination-card"
                @click="openEvidenceCatalog(destination.tab)"
                @keydown.left.prevent="focusSelectedCapability"
              >
                <span class="destination-title">
                  <strong>{{ destination.label }}</strong>
                  <em v-if="destination.badge">{{ destination.badge }}</em>
                </span>
                <small>{{ destination.description }}</small>
                <span class="destination-link">进入工作区 <el-icon><ArrowRight /></el-icon></span>
              </button>
            </div>
            <div class="navigation-boundary">
              <strong>这里只负责导航</strong>
              <span>展开面板不会调用 Guance；真实查询、路由修改和验收仍在目标页面按权限执行。</span>
            </div>
          </template>

          <template v-else>
            <div class="single-capability-card">
              <span>能力说明</span>
              <p>{{ selectedItem.description }}</p>
              <el-button type="primary" class="preview-action" @click="executeSelectedCapability">
                {{ selectedItem.actionLabel }}
                <el-icon><ArrowRight /></el-icon>
              </el-button>
            </div>
          </template>
        </main>
      </div>
    </section>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowDown, ArrowRight, Close } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import type { WorkbenchCapabilityCommand } from './workbenchView'
import {
  EVIDENCE_CATALOG_DESTINATIONS,
  WORKBENCH_CAPABILITY_GROUPS,
  evidenceCatalogLocation,
  type EvidenceCatalogTab,
  type WorkbenchCapabilityPanelItem,
} from './workbenchCapabilityMenu'

withDefaults(defineProps<{
  size?: 'small' | 'default' | 'large'
}>(), {
  size: 'default',
})

const emit = defineEmits<{
  command: [command: WorkbenchCapabilityCommand]
}>()

const route = useRoute()
const router = useRouter()
const panelRef = ref<HTMLElement | null>(null)
const panelOpen = ref(false)
const selectedCommand = ref<WorkbenchCapabilityCommand>('evidence-catalog')

const allCapabilities = WORKBENCH_CAPABILITY_GROUPS.flatMap(group => group.items)
const selectedItem = computed(() =>
  allCapabilities.find(item => item.command === selectedCommand.value) || allCapabilities[0]
)
const selectedGroupLabel = computed(() =>
  WORKBENCH_CAPABILITY_GROUPS.find(group =>
    group.items.some(item => item.command === selectedCommand.value)
  )?.label || '管理能力'
)

function selectCapability(item: WorkbenchCapabilityPanelItem) {
  selectedCommand.value = item.command
}

function moveCapabilityFocus(event: KeyboardEvent, offset: number) {
  const buttons = [...(panelRef.value?.querySelectorAll<HTMLButtonElement>('.capability-nav-item') || [])]
  if (!buttons.length) return
  const currentIndex = buttons.indexOf(event.currentTarget as HTMLButtonElement)
  const nextIndex = (currentIndex + offset + buttons.length) % buttons.length
  buttons[nextIndex]?.focus()
}

function focusPreviewAction() {
  panelRef.value
    ?.querySelector<HTMLButtonElement>('.destination-card, .preview-action')
    ?.focus()
}

function focusSelectedCapability() {
  panelRef.value
    ?.querySelector<HTMLButtonElement>(`[data-command="${selectedCommand.value}"]`)
    ?.focus()
}

function executeSelectedCapability() {
  if (selectedCommand.value === 'evidence-catalog') return
  panelOpen.value = false
  emit('command', selectedCommand.value)
}

async function openEvidenceCatalog(tab: EvidenceCatalogTab) {
  panelOpen.value = false
  await router.push(evidenceCatalogLocation(tab, route.fullPath))
}
</script>

<style scoped>
.capability-trigger { display:inline-flex; align-items:center; gap:6px; }
.trigger-chevron { transition:transform .18s ease; }
.capability-trigger.is-open .trigger-chevron { transform:rotate(180deg); }
.capability-panel { width:100%; overflow:hidden; color:var(--mc-text-primary); background:var(--mc-bg-elevated); }
.panel-header { display:flex; align-items:flex-start; justify-content:space-between; gap:24px; padding:22px 24px 18px; border-bottom:1px solid var(--mc-border); background:linear-gradient(135deg,var(--mc-bg-elevated),var(--mc-bg-muted)); }
.panel-eyebrow,.preview-header>span { display:block; color:var(--mc-primary); font-size:10px; font-weight:800; letter-spacing:.12em; text-transform:uppercase; }
.panel-header h2 { margin:5px 0 4px; font-size:18px; line-height:1.35; letter-spacing:-.02em; }
.panel-header p,.preview-header p { margin:0; color:var(--mc-text-secondary); font-size:12px; line-height:1.55; }
.panel-header-actions { display:flex; align-items:center; gap:8px; flex:0 0 auto; }
.panel-close { display:grid; place-items:center; width:30px; height:30px; padding:0; border:1px solid transparent; border-radius:var(--mc-radius-sm); color:var(--mc-text-secondary); background:transparent; cursor:pointer; }
.panel-close:hover,.panel-close:focus-visible { border-color:var(--mc-border); color:var(--mc-text-primary); background:var(--mc-bg-elevated); outline:none; }
.panel-body { display:grid; grid-template-columns:260px minmax(0,1fr); min-height:390px; max-height:min(590px,70vh); }
.capability-nav { overflow-y:auto; padding:14px 12px 18px; border-right:1px solid var(--mc-border); background:var(--mc-bg-muted); }
.capability-group+.capability-group { margin-top:16px; }
.capability-group h3 { margin:0 10px 7px; color:var(--mc-text-tertiary); font-size:10px; font-weight:800; letter-spacing:.08em; }
.capability-nav-item { display:flex; align-items:center; justify-content:space-between; gap:10px; width:100%; min-height:54px; padding:9px 10px; border:1px solid transparent; border-radius:var(--mc-radius-sm); color:var(--mc-text-secondary); background:transparent; font:inherit; text-align:left; cursor:pointer; }
.capability-nav-item:hover,.capability-nav-item:focus-visible { border-color:var(--mc-border); background:var(--mc-bg-elevated); outline:none; }
.capability-nav-item.active { border-color:color-mix(in srgb,var(--mc-primary) 24%,var(--mc-border)); color:var(--mc-primary); background:var(--mc-sidebar-active); box-shadow:0 6px 18px var(--mc-shadow-soft); }
.capability-nav-item>span { min-width:0; }
.capability-nav-item strong { display:block; font-size:13px; line-height:1.35; }
.capability-nav-item small { display:-webkit-box; overflow:hidden; margin-top:3px; color:var(--mc-text-tertiary); font-size:10px; line-height:1.35; -webkit-box-orient:vertical; -webkit-line-clamp:2; }
.capability-nav-item.active small { color:var(--mc-text-secondary); }
.capability-preview { min-width:0; overflow-y:auto; padding:24px; }
.preview-header h3 { margin:6px 0 6px; font-size:22px; letter-spacing:-.03em; }
.preview-header p { max-width:560px; font-size:13px; }
.destination-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-top:22px; }
.destination-card { display:flex; align-items:stretch; flex-direction:column; min-height:126px; padding:15px 16px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); color:var(--mc-text-primary); background:var(--mc-bg-elevated); font:inherit; text-align:left; cursor:pointer; transition:border-color .16s ease,transform .16s ease,box-shadow .16s ease; }
.destination-card:hover,.destination-card:focus-visible { border-color:var(--mc-primary); outline:none; box-shadow:0 10px 24px var(--mc-shadow-soft); transform:translateY(-1px); }
.destination-title { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.destination-title strong { font-size:14px; }
.destination-title em { padding:2px 6px; border-radius:999px; color:var(--mc-primary); background:var(--mc-sidebar-active); font-size:10px; font-style:normal; font-weight:750; }
.destination-card small { margin:8px 0 12px; color:var(--mc-text-secondary); font-size:11px; line-height:1.55; }
.destination-link { display:inline-flex; align-items:center; gap:4px; margin-top:auto; color:var(--mc-primary); font-size:11px; font-weight:750; }
.navigation-boundary { display:grid; grid-template-columns:auto minmax(0,1fr); gap:10px; margin-top:16px; padding:11px 13px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); color:var(--mc-text-secondary); background:var(--mc-bg-muted); font-size:11px; line-height:1.5; }
.navigation-boundary strong { color:var(--mc-text-primary); white-space:nowrap; }
.single-capability-card { margin-top:22px; padding:22px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-muted); }
.single-capability-card>span { color:var(--mc-text-tertiary); font-size:10px; font-weight:800; letter-spacing:.08em; }
.single-capability-card p { margin:8px 0 22px; color:var(--mc-text-secondary); font-size:13px; line-height:1.65; }
.preview-action { min-width:180px; }

:global(.workbench-capability-popper.el-popover) { width:min(760px,calc(100vw - 24px))!important; padding:0!important; overflow:hidden; border-color:var(--mc-border)!important; border-radius:var(--mc-radius-md)!important; box-shadow:0 20px 54px rgba(25,33,45,.16)!important; }

@media(max-width:720px){
  .panel-header{padding:18px 16px 14px}.panel-header h2{font-size:16px}.panel-header p{display:none}.panel-header-actions .el-tag{display:none}
  .panel-body{display:block;max-height:min(680px,78vh);overflow-y:auto}.capability-nav{max-height:255px;border-right:0;border-bottom:1px solid var(--mc-border)}
  .capability-preview{overflow:visible;padding:18px 16px}.destination-grid{grid-template-columns:1fr}.destination-card{min-height:104px}.navigation-boundary{grid-template-columns:1fr;gap:3px}
}
</style>
