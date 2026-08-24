import {
  TROUBLESHOOTING_UI_LABELS,
  type WorkbenchCapabilityCommand,
} from './workbenchView'
import { EVIDENCE_SYNTHESIS_FOCUS } from './synthesisPreview'

export type WorkbenchOverlayCapability = 'guance' | 'ledger' | 'case-knowledge'
export type EvidenceSetupSection = 'modules' | 'tools' | 'source'

export type WorkbenchCapabilityNavItem = {
  key: string
  command: WorkbenchCapabilityCommand
  label: string
  description: string
  section?: EvidenceSetupSection
  /** Route/nav gate; defaults to manage:troubleshooting for config pages. */
  requiredCapability?: 'view:troubleshooting' | 'operate:troubleshooting' | 'manage:troubleshooting'
}

export type WorkbenchCapabilityNavGroup = {
  key: 'advanced' | 'learning'
  label: string
  items: ReadonlyArray<WorkbenchCapabilityNavItem>
}

export const WORKBENCH_PRIMARY_CAPABILITIES: ReadonlyArray<WorkbenchCapabilityNavItem> = [
  {
    key: 'evidence-modules',
    command: 'observability-assets',
    section: 'modules',
    label: '接入系统',
    description: '登记系统模块，并在模块里选择取证方法',
    requiredCapability: 'manage:troubleshooting',
  },
]

export const WORKBENCH_CAPABILITY_GROUPS: ReadonlyArray<WorkbenchCapabilityNavGroup> = [
  {
    key: 'advanced',
    label: '更多配置',
    items: [
      {
    key: 'evidence-tools',
    command: 'observability-assets',
    section: 'tools',
    label: '取证方法',
    description: '维护方法库（可设通用或指定系统/模块），再在接入系统里选用',
    requiredCapability: 'manage:troubleshooting',
  },
      {
        key: 'evidence-source',
        command: 'observability-assets',
        section: 'source',
        label: '数据连接',
        description: '检查观测云能否正常读取',
        requiredCapability: 'manage:troubleshooting',
      },
      {
        key: 'playbooks',
        command: 'playbooks',
        label: TROUBLESHOOTING_UI_LABELS.rules,
        description: '场景、步骤与判断标准',
        requiredCapability: 'manage:troubleshooting',
      },
    ],
  },
  {
    key: 'learning',
    label: '复盘与沉淀',
    items: [
      {
        key: 'ledger',
        command: 'ledger',
        label: TROUBLESHOOTING_UI_LABELS.evaluation,
        description: '用真实样本验证效果',
        requiredCapability: 'manage:troubleshooting',
      },
      {
        key: 'case-knowledge',
        command: 'case-knowledge',
        label: TROUBLESHOOTING_UI_LABELS.caseKnowledge,
        description: '沉淀已解决的故障',
        requiredCapability: 'manage:troubleshooting',
      },
    ],
  },
]

export const EVIDENCE_SETUP_SECTIONS: ReadonlyArray<{
  key: EvidenceSetupSection
  label: string
  description: string
}> = [
  { key: 'modules', label: '接入系统', description: '登记要排障的系统和模块；点进模块即可勾选取证方法、查看数据源缺口并试跑。' },
  { key: 'tools', label: '取证方法', description: '方法库：配置查什么、要哪些参数、作用域是通用还是指定系统/模块；查询模板由管理员维护。' },
  { key: 'source', label: '数据连接', description: '检查系统能否只读访问观测云并正常获取排障数据。' },
]

export function normalizeEvidenceSetupSection(value: unknown): EvidenceSetupSection {
  const candidate = firstQueryValue(value)
  return candidate === 'tools' || candidate === 'source' ? candidate : 'modules'
}

const WORKBENCH_OVERLAY_CAPABILITIES = new Set<WorkbenchOverlayCapability>([
  'guance',
  'ledger',
  'case-knowledge',
])

const PILOT_MEMBER_SOURCE = 'troubleshooting-pilot'
const DEFAULT_PILOT_SETUP_RETURN = '/troubleshooting?view=list&capability=ledger&pilotSetup=1'

function firstQueryValue(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value
}

export function safeTroubleshootingReturnPath(value: unknown): string | null {
  const candidate = firstQueryValue(value)
  if (typeof candidate !== 'string') return null
  if (!/^\/troubleshooting(?:[?#]|$)/.test(candidate)) return null
  return candidate
}

export function pilotMemberSettingsLocation(
  preferredReturnPath?: unknown,
): { path: string; query: Record<string, string> } {
  return {
    path: '/settings/members',
    query: {
      source: PILOT_MEMBER_SOURCE,
      returnTo: safeTroubleshootingReturnPath(preferredReturnPath) || DEFAULT_PILOT_SETUP_RETURN,
    },
  }
}

export function pilotMemberReturnPath(source: unknown, returnTo: unknown): string | null {
  if (firstQueryValue(source) !== PILOT_MEMBER_SOURCE) return null
  return safeTroubleshootingReturnPath(returnTo)
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

export function observabilityAssetsLocation(
  scope?: { system?: string; service?: string },
  currentFullPath?: string,
  section?: EvidenceSetupSection,
): { path: string; query: Record<string, string> } {
  const returnTo = safeTroubleshootingReturnPath(currentFullPath)
  return {
    path: '/troubleshooting/observability-assets',
    query: {
      ...(scope?.system ? { system: scope.system } : {}),
      ...(scope?.service ? { service: scope.service } : {}),
      ...(section ? { section } : {}),
      ...(returnTo ? { returnTo } : {}),
    },
  }
}

export function t7OwnerContractLocation(
  currentFullPath?: string,
): { path: string; query: Record<string, string> } {
  const returnTo = safeTroubleshootingReturnPath(currentFullPath)
  return {
    path: '/troubleshooting/t7-owner-contract',
    query: {
      ...(returnTo ? { returnTo } : {}),
    },
  }
}
