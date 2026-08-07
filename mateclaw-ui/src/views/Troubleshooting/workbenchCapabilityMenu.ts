import {
  TROUBLESHOOTING_UI_LABELS,
  type WorkbenchCapabilityCommand,
} from './workbenchView'
import { EVIDENCE_SYNTHESIS_FOCUS } from './synthesisPreview'

export type EvidenceCatalogTab = 'systems' | 'assets' | 'contracts' | 'routes' | 'acceptance'
export type WorkbenchOverlayCapability = 'guance' | 'ledger' | 'case-knowledge'

export type WorkbenchCapabilityNavItem = {
  command: WorkbenchCapabilityCommand
  label: string
}

export type WorkbenchCapabilityNavGroup = {
  key: 'configuration' | 'learning'
  label: string
  items: ReadonlyArray<WorkbenchCapabilityNavItem>
}

export type EvidenceCatalogDestination = {
  tab: EvidenceCatalogTab
  label: string
}

export const WORKBENCH_CAPABILITY_GROUPS: ReadonlyArray<WorkbenchCapabilityNavGroup> = [
  {
    key: 'configuration',
    label: '配置与接入',
    items: [
      {
        command: 'observability-assets',
        label: TROUBLESHOOTING_UI_LABELS.observabilityAssets,
      },
      {
        command: 'playbooks',
        label: TROUBLESHOOTING_UI_LABELS.rules,
      },
    ],
  },
  {
    key: 'learning',
    label: '复盘与沉淀',
    items: [
      {
        command: 'ledger',
        label: TROUBLESHOOTING_UI_LABELS.evaluation,
      },
      {
        command: 'case-knowledge',
        label: TROUBLESHOOTING_UI_LABELS.caseKnowledge,
      },
    ],
  },
]

/**
 * 兼容旧深链 `?tab=`。新页面已收敛为单工作区，侧栏不再展示子入口。
 * tab 仅用于把旧书签映射到页内焦点（资产 / 路由 / 联调）。
 */
export const EVIDENCE_CATALOG_DESTINATIONS: ReadonlyArray<EvidenceCatalogDestination> = []

const EVIDENCE_CATALOG_TABS = new Set<EvidenceCatalogTab>([
  'systems',
  'assets',
  'contracts',
  'routes',
  'acceptance',
])
const WORKBENCH_OVERLAY_CAPABILITIES = new Set<WorkbenchOverlayCapability>([
  'guance',
  'ledger',
  'case-knowledge',
])

function firstQueryValue(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value
}

export function normalizeEvidenceCatalogTab(value: unknown): EvidenceCatalogTab {
  const candidate = firstQueryValue(value)
  return typeof candidate === 'string' && EVIDENCE_CATALOG_TABS.has(candidate as EvidenceCatalogTab)
    ? candidate as EvidenceCatalogTab
    : 'systems'
}

export function safeTroubleshootingReturnPath(value: unknown): string | null {
  const candidate = firstQueryValue(value)
  if (typeof candidate !== 'string') return null
  if (!/^\/troubleshooting(?:[?#]|$)/.test(candidate)) return null
  return candidate
}

export function normalizeWorkbenchOverlayCapability(
  value: unknown,
): WorkbenchOverlayCapability | null {
  const candidate = firstQueryValue(value)
  return typeof candidate === 'string'
    && WORKBENCH_OVERLAY_CAPABILITIES.has(candidate as WorkbenchOverlayCapability)
    ? candidate as WorkbenchOverlayCapability
    : null
}

export function workbenchOverlayLocation(
  capability: WorkbenchOverlayCapability,
  preferredReturnPath?: unknown,
): { path: string; query: Record<string, string> } {
  const returnPath = safeTroubleshootingReturnPath(preferredReturnPath)
    || '/troubleshooting?view=list'
  const [pathAndQuery] = returnPath.split('#', 1)
  const queryStart = pathAndQuery.indexOf('?')
  const path = queryStart >= 0 ? pathAndQuery.slice(0, queryStart) : pathAndQuery
  const rawQuery = queryStart >= 0 ? pathAndQuery.slice(queryStart + 1) : ''
  const query = Object.fromEntries(new URLSearchParams(rawQuery))

  return {
    path,
    query: {
      ...query,
      capability,
    },
  }
}

export function legacyEvidenceSynthesisLocation(
  preferredReturnPath?: unknown,
): { path: string; query: Record<string, string> } {
  const location = workbenchOverlayLocation('ledger', preferredReturnPath)
  return {
    ...location,
    query: {
      ...location.query,
      focus: EVIDENCE_SYNTHESIS_FOCUS,
    },
  }
}

export function evidenceCatalogLocation(
  tab: EvidenceCatalogTab = 'systems',
  currentFullPath?: string,
): { path: string; query: Record<string, string> } {
  const returnTo = safeTroubleshootingReturnPath(currentFullPath)
  return {
    path: '/troubleshooting/evidence-catalog',
    query: {
      ...(tab !== 'systems' ? { tab } : {}),
      ...(returnTo ? { returnTo } : {}),
    },
  }
}

export function observabilityAssetsLocation(
  scope?: { system?: string; service?: string },
  currentFullPath?: string,
): { path: string; query: Record<string, string> } {
  const returnTo = safeTroubleshootingReturnPath(currentFullPath)
  return {
    path: '/troubleshooting/observability-assets',
    query: {
      ...(scope?.system ? { system: scope.system } : {}),
      ...(scope?.service ? { service: scope.service } : {}),
      ...(returnTo ? { returnTo } : {}),
    },
  }
}
