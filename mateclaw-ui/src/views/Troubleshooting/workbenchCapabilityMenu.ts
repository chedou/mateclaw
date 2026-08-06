import {
  TROUBLESHOOTING_UI_LABELS,
  type WorkbenchCapabilityCommand,
} from './workbenchView'

export type EvidenceCatalogTab = 'systems' | 'assets' | 'contracts' | 'routes' | 'acceptance'
export type WorkbenchOverlayCapability = 'guance' | 'ledger' | 'case-knowledge'

export type WorkbenchCapabilityNavItem = {
  command: WorkbenchCapabilityCommand
  label: string
}

export type WorkbenchCapabilityNavGroup = {
  key: 'configuration' | 'validation' | 'learning'
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
        command: 'playbooks',
        label: TROUBLESHOOTING_UI_LABELS.rules,
      },
      {
        command: 'evidence-catalog',
        label: TROUBLESHOOTING_UI_LABELS.evidenceCatalog,
      },
    ],
  },
  {
    key: 'validation',
    label: '验证与演练',
    items: [
      {
        command: 'synthesis',
        label: TROUBLESHOOTING_UI_LABELS.noCodePreview,
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

export const EVIDENCE_CATALOG_DESTINATIONS: ReadonlyArray<EvidenceCatalogDestination> = [
  {
    tab: 'systems',
    label: '系统与模块',
  },
  {
    tab: 'assets',
    label: '系统观测资产',
  },
  {
    tab: 'contracts',
    label: '查询规则',
  },
  {
    tab: 'routes',
    label: '路由与绑定',
  },
  {
    tab: 'acceptance',
    label: '数据源联调',
  },
]

const EVIDENCE_CATALOG_TABS = new Set<EvidenceCatalogTab>(
  EVIDENCE_CATALOG_DESTINATIONS.map(item => item.tab),
)
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

export function evidenceCatalogLocation(
  tab: EvidenceCatalogTab,
  currentFullPath?: string,
): { path: string; query: Record<string, string> } {
  const returnTo = safeTroubleshootingReturnPath(currentFullPath)
  return {
    path: '/troubleshooting/evidence-catalog',
    query: {
      tab,
      ...(returnTo ? { returnTo } : {}),
    },
  }
}
