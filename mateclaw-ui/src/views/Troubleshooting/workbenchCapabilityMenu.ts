import {
  TROUBLESHOOTING_UI_LABELS,
  type WorkbenchCapabilityCommand,
} from './workbenchView'

export type EvidenceCatalogTab = 'systems' | 'contracts' | 'routes' | 'acceptance'

export type WorkbenchCapabilityPanelItem = {
  command: WorkbenchCapabilityCommand
  label: string
  description: string
  actionLabel: string
  expandable?: boolean
}

export type WorkbenchCapabilityPanelGroup = {
  key: 'configuration' | 'validation' | 'learning'
  label: string
  items: ReadonlyArray<WorkbenchCapabilityPanelItem>
}

export type EvidenceCatalogDestination = {
  tab: EvidenceCatalogTab
  label: string
  description: string
  badge?: string
}

export const WORKBENCH_CAPABILITY_GROUPS: ReadonlyArray<WorkbenchCapabilityPanelGroup> = [
  {
    key: 'configuration',
    label: '配置与接入',
    items: [
      {
        command: 'playbooks',
        label: TROUBLESHOOTING_UI_LABELS.rules,
        description: '管理已经审核、可供排障选用的排查指南。',
        actionLabel: '打开排障规则库',
      },
      {
        command: 'evidence-catalog',
        label: TROUBLESHOOTING_UI_LABELS.evidenceCatalog,
        description: '按系统和模块维护查询合同、来源路由与验收状态。',
        actionLabel: '打开取证查询目录',
        expandable: true,
      },
      {
        command: 'guance',
        label: TROUBLESHOOTING_UI_LABELS.guanceOnboarding,
        description: '检查观测云真实数据源的全局连接和 Owner 验收状态。',
        actionLabel: '打开观测云接入与验收',
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
        description: '在不影响生产的前提下预览证据归纳和规则草稿。',
        actionLabel: '开始无码场景预演',
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
        description: '复盘命中、弃权、人工接管和诊断效果。',
        actionLabel: '打开诊断效果评估',
      },
      {
        command: 'case-knowledge',
        label: TROUBLESHOOTING_UI_LABELS.caseKnowledge,
        description: '把已经闭环的故障案例沉淀为可检索知识。',
        actionLabel: '导入历史案例',
      },
    ],
  },
]

export const EVIDENCE_CATALOG_DESTINATIONS: ReadonlyArray<EvidenceCatalogDestination> = [
  {
    tab: 'systems',
    label: '系统与模块',
    description: '按系统、模块和场景找到需要调查的证据。',
  },
  {
    tab: 'contracts',
    label: '查询合同',
    description: '核对调用方式、参数来源、固定条件和规范输出。',
  },
  {
    tab: 'routes',
    label: '路由与绑定',
    description: '选择只读取证来源并调整调用优先级。',
    badge: '可配置',
  },
  {
    tab: 'acceptance',
    label: '联调与验收',
    description: '检查端点、凭据、查询绑定、Owner 验收和阻断原因。',
  },
]

const EVIDENCE_CATALOG_TABS = new Set<EvidenceCatalogTab>(
  EVIDENCE_CATALOG_DESTINATIONS.map(item => item.tab),
)

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
  if (!/^\/troubleshooting(?:[/?#]|$)/.test(candidate)) return null
  if (candidate.startsWith('/troubleshooting/evidence-catalog')) return null
  return candidate
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
