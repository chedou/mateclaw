<template>
  <div class="mc-page-shell troubleshooting-shell">
    <div class="mc-page-frame troubleshooting-frame">
      <div
        class="mc-page-inner troubleshooting-layout"
        :class="{ 'without-capability-nav': !canViewTroubleshooting }"
      >
      <aside
        v-if="canViewTroubleshooting"
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
              <span v-if="!navCompact" class="nav-copy">
                <span class="nav-label">排障工作台</span>
                <small>创建与查看排障单</small>
              </span>
            </button>
          </el-tooltip>

          <el-tooltip
            v-for="item in visiblePrimaryCapabilities"
            :key="item.key"
            :content="item.label"
            placement="right"
            :disabled="!navCompact"
          >
            <button
              type="button"
              class="nav-item"
              :aria-label="item.label"
              :class="{ active: capabilityItemActive(item) }"
              :aria-current="capabilityItemActive(item) ? 'page' : undefined"
              @click="openCapability(item)"
            >
              <el-icon class="nav-icon"><component :is="capabilityIcon(item)" /></el-icon>
              <span v-if="!navCompact" class="nav-copy">
                <span class="nav-label">{{ item.label }}</span>
                <small>{{ item.description }}</small>
              </span>
            </button>
          </el-tooltip>

          <section v-for="group in visibleCapabilityGroups" :key="group.key" class="nav-group">
            <el-tooltip
              :content="group.label"
              placement="right"
              :disabled="!navCompact"
            >
              <button
                type="button"
                class="nav-item nav-group-toggle"
                :class="{
                  expanded: capabilityGroupExpanded(group),
                  'contains-active': capabilityGroupActive(group),
                }"
                :aria-label="group.label"
                :aria-expanded="capabilityGroupExpanded(group)"
                @click="toggleCapabilityGroup(group.key)"
              >
                <el-icon class="nav-icon"><component :is="capabilityGroupIcon(group)" /></el-icon>
                <span v-if="!navCompact" class="nav-copy">
                  <span class="nav-label">{{ group.label }}</span>
                  <small>{{ capabilityGroupDescription(group) }}</small>
                </span>
                <el-icon v-if="!navCompact" class="nav-group-chevron"><ArrowDown /></el-icon>
              </button>
            </el-tooltip>

            <div v-show="capabilityGroupExpanded(group)" class="nav-children">
              <el-tooltip
                v-for="item in group.items"
                :key="item.key"
                :content="item.label"
                placement="right"
                :disabled="!navCompact"
              >
                <button
                  type="button"
                  class="nav-child"
                  :aria-label="item.label"
                  :class="{ active: capabilityItemActive(item) }"
                  :aria-current="capabilityItemActive(item) ? 'page' : undefined"
                  @click="openCapability(item)"
                >
                  <el-icon class="nav-icon">
                    <component :is="capabilityIcon(item)" />
                  </el-icon>
                  <span v-if="!navCompact" class="nav-label">{{ item.label }}</span>
                </button>
              </el-tooltip>
            </div>
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
  ArrowDown,
  Collection,
  Connection,
  DataAnalysis,
  Document,
  DocumentAdd,
  OfficeBuilding,
  TrendCharts,
  Tools,
} from '@element-plus/icons-vue'
import { useMediaQuery } from '@/composables/useBreakpoint'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import type { WorkbenchCapabilityCommand } from './workbenchView'
import {
  WORKBENCH_CAPABILITY_GROUPS,
  WORKBENCH_PRIMARY_CAPABILITIES,
  observabilityAssetsLocation,
  normalizeEvidenceSetupSection,
  normalizeWorkbenchOverlayCapability,
  safeTroubleshootingReturnPath,
  t7OwnerContractLocation,
  workbenchOverlayLocation,
  type WorkbenchOverlayCapability,
  type WorkbenchCapabilityNavGroup,
  type WorkbenchCapabilityNavItem,
} from './workbenchCapabilityMenu'

const route = useRoute()
const router = useRouter()
const workspaceStore = useWorkspaceStore()
const canViewTroubleshooting = computed(() => workspaceStore.can('view:troubleshooting'))
const canManageTroubleshooting = computed(() => workspaceStore.can('manage:troubleshooting'))
const navCollapsed = ref(localStorage.getItem('mc-troubleshooting-nav-collapsed') === 'true')
const manuallyExpandedGroups = ref(new Set<WorkbenchCapabilityNavGroup['key']>())
const forcedRailViewport = useMediaQuery('(max-width: 1040px)')
const navCompact = computed(() => navCollapsed.value || forcedRailViewport.value)

function navItemVisible(item: WorkbenchCapabilityNavItem) {
  const required = item.requiredCapability || 'manage:troubleshooting'
  return workspaceStore.can(required)
}

const visiblePrimaryCapabilities = computed(() =>
  WORKBENCH_PRIMARY_CAPABILITIES.filter(navItemVisible),
)
const visibleCapabilityGroups = computed(() =>
  WORKBENCH_CAPABILITY_GROUPS
    .map(group => ({
      ...group,
      items: group.items.filter(navItemVisible),
    }))
    .filter(group => group.items.length > 0),
)

const CAPABILITY_ICONS: Record<WorkbenchCapabilityCommand, Component> = {
  playbooks: Collection,
  'observability-assets': OfficeBuilding,
  't7-owner-contract': Document,
  guance: Connection,
  ledger: TrendCharts,
  'case-knowledge': DocumentAdd,
}
const EVIDENCE_SECTION_ICONS: Record<string, Component> = {
  modules: OfficeBuilding,
  tools: Tools,
  source: Connection,
}
const CAPABILITY_GROUP_ICONS: Record<WorkbenchCapabilityNavGroup['key'], Component> = {
  advanced: Tools,
  learning: TrendCharts,
}

const activeCommand = computed<WorkbenchCapabilityCommand | null>(() => {
  if (route.path === '/troubleshooting/observability-assets') return 'observability-assets'
  if (route.path === '/troubleshooting/sops') return 'playbooks'
  if (route.path === '/troubleshooting/t7-owner-contract') return 't7-owner-contract'
  return normalizeWorkbenchOverlayCapability(route.query.capability)
})
const workbenchActive = computed(() => route.path === '/troubleshooting' && !activeCommand.value)

function capabilityItemActive(item: WorkbenchCapabilityNavItem) {
  if (activeCommand.value !== item.command) return false
  if (item.command !== 'observability-assets') return true
  return normalizeEvidenceSetupSection(route.query.section) === item.section
}

function capabilityIcon(item: WorkbenchCapabilityNavItem) {
  return item.section ? EVIDENCE_SECTION_ICONS[item.section] : CAPABILITY_ICONS[item.command]
}

function capabilityGroupIcon(group: WorkbenchCapabilityNavGroup) {
  return CAPABILITY_GROUP_ICONS[group.key]
}

function capabilityGroupDescription(group: WorkbenchCapabilityNavGroup) {
  return group.key === 'advanced' ? '更多配置：取证总览与数据连接' : '效果评估与案例沉淀'
}

function capabilityGroupActive(group: WorkbenchCapabilityNavGroup) {
  return group.items.some(capabilityItemActive)
}

function capabilityGroupExpanded(group: WorkbenchCapabilityNavGroup) {
  return capabilityGroupActive(group) || manuallyExpandedGroups.value.has(group.key)
}

function toggleCapabilityGroup(groupKey: WorkbenchCapabilityNavGroup['key']) {
  const next = new Set(manuallyExpandedGroups.value)
  if (next.has(groupKey)) next.delete(groupKey)
  else next.add(groupKey)
  manuallyExpandedGroups.value = next
}

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

function openCapability(item: WorkbenchCapabilityNavItem) {
  const command = item.command
  const returnTo = preferredWorkbenchPath()
  if (command === 'playbooks') {
    void router.push({ path: '/troubleshooting/sops', query: { returnTo } })
    return
  }
  if (command === 'observability-assets') {
    void router.push(observabilityAssetsLocation(undefined, returnTo, item.section))
    return
  }
  if (command === 't7-owner-contract') {
    void router.push(t7OwnerContractLocation(returnTo))
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
.capability-nav { display:flex; flex:0 0 var(--mc-ts-side-rail-width); flex-direction:column; width:var(--mc-ts-side-rail-width); min-width:var(--mc-ts-side-rail-width); padding:14px 10px; overflow-y:auto; transition:width .25s ease,min-width .25s ease,flex-basis .25s ease; }
.capability-nav.collapsed { flex-basis:56px; width:56px; min-width:56px; padding:12px 8px; }
.capability-nav__intro { padding:4px 8px 12px; margin-bottom:6px; border-bottom:1px solid var(--mc-border-light); }
.capability-nav__intro span { display:block; margin-bottom:5px; color:var(--mc-primary); font-size:10px; font-weight:800; letter-spacing:.12em; text-transform:uppercase; }
.capability-nav__intro h2 { margin:0; color:var(--mc-text-primary); font-size:20px; letter-spacing:-.03em; }
.capability-nav nav { display:flex; flex-direction:column; }
.nav-group { margin-top:4px; }
.nav-item,.nav-child { display:flex; align-items:center; gap:8px; width:100%; border:0; color:var(--mc-text-secondary); background:transparent; font:inherit; text-align:left; cursor:pointer; transition:background .15s ease,color .15s ease; }
.nav-item { position:relative; min-height:52px; padding:8px 10px; border-radius:10px; font-size:13px; font-weight:500; }
.nav-item:hover,.nav-item:focus-visible,.nav-child:hover,.nav-child:focus-visible { color:var(--mc-text-primary); background:var(--mc-bg-muted); outline:none; }
.nav-item.active,.nav-child.active { color:var(--mc-primary); background:var(--mc-primary-bg); font-weight:650; box-shadow:inset 0 0 0 1px rgba(217,109,70,.08); }
.nav-item.active::before { position:absolute; top:10px; bottom:10px; left:0; width:3px; border-radius:0 3px 3px 0; background:var(--mc-primary); content:""; }
.nav-icon { display:grid; place-items:center; flex:0 0 18px; width:18px; height:18px; font-size:17px; }
.nav-label { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.nav-copy { display:flex; flex:1; flex-direction:column; min-width:0; line-height:1.25; }
.nav-copy small { margin-top:3px; overflow:hidden; color:var(--mc-text-tertiary); font-size:10px; font-weight:450; text-overflow:ellipsis; white-space:nowrap; }
.nav-item.active .nav-copy small { color:color-mix(in srgb,var(--mc-primary) 70%,var(--mc-text-secondary)); }
.nav-group-toggle.contains-active { color:var(--mc-primary); }
.nav-group-chevron { flex:0 0 14px; font-size:12px; transition:transform .18s ease; }
.nav-group-toggle.expanded .nav-group-chevron { transform:rotate(180deg); }
.capability-nav.collapsed .nav-item { justify-content:center; min-height:40px; padding:10px 8px; }
.capability-nav.collapsed .nav-item.active::before { top:8px; bottom:8px; }
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
