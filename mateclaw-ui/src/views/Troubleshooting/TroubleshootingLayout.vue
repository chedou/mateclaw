<template>
  <div class="mc-page-shell troubleshooting-shell">
    <div class="mc-page-frame troubleshooting-frame">
      <div
        class="mc-page-inner troubleshooting-layout"
        :class="{ 'without-capability-nav': !canManageTroubleshooting }"
      >
      <aside
        v-if="canManageTroubleshooting"
        class="capability-nav mc-surface-card"
        :class="{ collapsed: navCompact }"
      >
        <div v-if="!navCompact" class="capability-nav__intro">
          <span>运行与治理</span>
          <h2>智能排障</h2>
        </div>

        <nav aria-label="智能排障二级菜单">
          <el-tooltip content="排障工作台" placement="right" :disabled="!navCompact">
            <button
              type="button"
              class="nav-item"
              aria-label="排障工作台"
              :class="{ active: workbenchActive }"
              :aria-current="workbenchActive ? 'page' : undefined"
              @click="openWorkbench"
            >
              <el-icon class="nav-icon"><DataAnalysis /></el-icon>
              <span v-if="!navCompact" class="nav-label">排障工作台</span>
            </button>
          </el-tooltip>

          <section v-for="group in WORKBENCH_CAPABILITY_GROUPS" :key="group.key" class="nav-group">
            <h3 v-if="!navCompact">{{ group.label }}</h3>
            <el-tooltip
              v-for="item in group.items"
              :key="item.command"
              :content="item.label"
              placement="right"
              :disabled="!navCompact"
            >
              <div>
                <button
                  type="button"
                  class="nav-item"
                  :aria-label="item.label"
                  :class="{ active: activeCommand === item.command }"
                  :aria-current="activeCommand === item.command ? 'page' : undefined"
                  @click="openCapability(item.command)"
                >
                  <el-icon class="nav-icon">
                    <component :is="CAPABILITY_ICONS[item.command]" />
                  </el-icon>
                  <span v-if="!navCompact" class="nav-label">{{ item.label }}</span>
                </button>
              </div>
            </el-tooltip>
          </section>
        </nav>

        <button
          v-if="!forcedRailViewport"
          type="button"
          class="nav-collapse"
          :title="navCollapsed ? '展开二级菜单' : '折叠二级菜单'"
          :aria-label="navCollapsed ? '展开二级菜单' : '折叠二级菜单'"
          @click="toggleNav"
        >
          <svg v-if="!navCollapsed" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
        </button>
      </aside>

      <main class="capability-content mc-surface-card">
        <router-view />
      </main>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Collection,
  Connection,
  DataAnalysis,
  DocumentAdd,
  OfficeBuilding,
  TrendCharts,
} from '@element-plus/icons-vue'
import { useMediaQuery } from '@/composables/useBreakpoint'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import type { WorkbenchCapabilityCommand } from './workbenchView'
import {
  WORKBENCH_CAPABILITY_GROUPS,
  observabilityAssetsLocation,
  normalizeWorkbenchOverlayCapability,
  safeTroubleshootingReturnPath,
  workbenchOverlayLocation,
  type WorkbenchOverlayCapability,
} from './workbenchCapabilityMenu'

const route = useRoute()
const router = useRouter()
const workspaceStore = useWorkspaceStore()
const canManageTroubleshooting = computed(() => workspaceStore.can('manage:troubleshooting'))
const navCollapsed = ref(localStorage.getItem('mc-troubleshooting-nav-collapsed') === 'true')
const forcedRailViewport = useMediaQuery('(max-width: 1040px)')
const navCompact = computed(() => navCollapsed.value || forcedRailViewport.value)

const CAPABILITY_ICONS: Record<WorkbenchCapabilityCommand, Component> = {
  playbooks: Collection,
  'observability-assets': OfficeBuilding,
  guance: Connection,
  ledger: TrendCharts,
  'case-knowledge': DocumentAdd,
}

const activeCommand = computed<WorkbenchCapabilityCommand | null>(() => {
  if (route.path === '/troubleshooting/observability-assets') return 'observability-assets'
  if (route.path === '/troubleshooting/sops') return 'playbooks'
  return normalizeWorkbenchOverlayCapability(route.query.capability)
})
const workbenchActive = computed(() => route.path === '/troubleshooting' && !activeCommand.value)

function preferredWorkbenchPath(): string {
  if (route.path === '/troubleshooting') {
    const query = { ...route.query }
    delete query.capability
    const resolved = router.resolve({ path: '/troubleshooting', query })
    return resolved.fullPath
  }
  return safeTroubleshootingReturnPath(route.query.returnTo) || '/troubleshooting?view=list'
}

function openWorkbench() {
  void router.push(preferredWorkbenchPath())
}

function openCapability(command: WorkbenchCapabilityCommand) {
  const returnTo = preferredWorkbenchPath()
  if (command === 'playbooks') {
    void router.push({ path: '/troubleshooting/sops', query: { returnTo } })
    return
  }
  if (command === 'observability-assets') {
    void router.push(observabilityAssetsLocation(undefined, returnTo))
    return
  }
  void router.push(workbenchOverlayLocation(command as WorkbenchOverlayCapability, returnTo))
}

function toggleNav() {
  navCollapsed.value = !navCollapsed.value
  localStorage.setItem('mc-troubleshooting-nav-collapsed', String(navCollapsed.value))
}
</script>

<style scoped>
.troubleshooting-shell { height:100%; min-height:0; overflow:hidden; background:transparent; }
.troubleshooting-frame { height:min(calc(100vh - 28px),100%); min-height:0; overflow:hidden; }
.troubleshooting-layout { display:flex; gap:18px; width:100%; height:100%; min-width:0; min-height:0; }
.capability-nav { display:flex; flex:0 0 210px; flex-direction:column; width:210px; min-width:210px; padding:14px 10px; overflow-y:auto; transition:width .25s ease,min-width .25s ease,flex-basis .25s ease; }
.capability-nav.collapsed { flex-basis:56px; width:56px; min-width:56px; padding:12px 8px; }
.capability-nav__intro { padding:4px 8px 12px; margin-bottom:6px; border-bottom:1px solid var(--mc-border-light); }
.capability-nav__intro span { display:block; margin-bottom:5px; color:var(--mc-primary); font-size:10px; font-weight:800; letter-spacing:.12em; text-transform:uppercase; }
.capability-nav__intro h2 { margin:0; color:var(--mc-text-primary); font-size:20px; letter-spacing:-.03em; }
.capability-nav nav { display:flex; flex-direction:column; }
.nav-group { margin-top:4px; }
.nav-group h3 { margin:0; padding:12px 8px 4px; color:var(--mc-text-tertiary); font-size:10px; font-weight:700; letter-spacing:.1em; text-transform:uppercase; }
.nav-item,.nav-child { display:flex; align-items:center; gap:8px; width:100%; border:0; color:var(--mc-text-secondary); background:transparent; font:inherit; text-align:left; cursor:pointer; transition:background .15s ease,color .15s ease; }
.nav-item { min-height:38px; padding:8px 10px; border-radius:10px; font-size:13px; font-weight:500; }
.nav-item:hover,.nav-item:focus-visible,.nav-child:hover,.nav-child:focus-visible { color:var(--mc-text-primary); background:var(--mc-bg-muted); outline:none; }
.nav-item.active,.nav-child.active { color:var(--mc-primary); background:var(--mc-primary-bg); font-weight:650; box-shadow:inset 0 0 0 1px rgba(217,109,70,.08); }
.nav-icon { display:grid; place-items:center; flex:0 0 18px; width:18px; height:18px; font-size:17px; }
.nav-label { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.capability-nav.collapsed .nav-item { justify-content:center; padding:10px 8px; }
.nav-children { display:grid; gap:2px; margin:3px 0 5px 26px; padding-left:8px; border-left:1px solid var(--mc-border); }
.nav-child { min-height:32px; padding:6px 8px; border-radius:8px; font-size:12px; }
.nav-child .el-icon { flex:0 0 15px; font-size:14px; }
.capability-nav.collapsed .nav-children { margin:4px 0 6px; padding:4px 0 0; border-top:1px solid var(--mc-border-light); border-left:0; }
.capability-nav.collapsed .nav-child { justify-content:center; padding:8px; }
.nav-collapse { display:flex; align-items:center; justify-content:center; position:sticky; bottom:8px; flex:0 0 auto; align-self:center; width:32px; height:32px; padding:0; margin-top:auto; margin-bottom:8px; border:1px solid rgba(217,109,70,.22); border-radius:50%; color:var(--mc-primary); background:var(--mc-primary-bg); box-shadow:0 6px 16px rgba(217,109,70,.18); cursor:pointer; transition:background .18s ease,color .18s ease,transform .18s ease; }
.nav-collapse:hover,.nav-collapse:focus-visible { color:#fff; background:var(--mc-primary); outline:2px solid color-mix(in srgb,var(--mc-primary) 30%,transparent); outline-offset:2px; transform:scale(1.05); }
.capability-content { flex:1; min-width:0; min-height:0; overflow:hidden; }
.without-capability-nav .capability-content { width:100%; }

@media (max-width:1040px) {
  .capability-nav { flex-basis:56px; width:56px; min-width:56px; padding:12px 8px; }
}

@media (max-width:900px) {
  .troubleshooting-frame { height:auto; min-height:calc(100vh - 28px); overflow:visible; }
  .troubleshooting-layout { height:auto; min-height:calc(100vh - 64px); gap:10px; }
  .capability-nav { align-self:auto; overflow:visible; }
  .capability-content { min-height:0; overflow:visible; }
}
</style>
