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
    description: '新增系统、模块和资源范围',
  },
]

export const WORKBENCH_CAPABILITY_GROUPS: ReadonlyArray<WorkbenchCapabilityNavGroup> = [
  {
    key: 'advanced',
    label: '高级设置',
    items: [
      {
        key: 'evidence-tools',
        command: 'observability-assets',
        section: 'tools',
        label: '取证方法',
        description: '配置日志、调用链和拨测方法',
      },
      {
        key: 'evidence-source',
        command: 'observability-assets',
        section: 'source',
        label: '数据连接',
        description: '检查观测云能否正常读取',
      },
      {
        key: 'playbooks',
        command: 'playbooks',
        label: TROUBLESHOOTING_UI_LABELS.rules,
        description: '场景、步骤与判断标准',
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
      },
      {
        key: 'case-knowledge',
        command: 'case-knowledge',
        label: TROUBLESHOOTING_UI_LABELS.caseKnowledge,
        description: '沉淀已解决的故障',
      },
    ],
  },
]

export const EVIDENCE_SETUP_SECTIONS: ReadonlyArray<{
  key: EvidenceSetupSection
  label: string
  description: string
}> = [
  { key: 'modules', label: '接入系统', description: '新增要排障的系统和模块，并填写环境、集群等资源范围。' },
  { key: 'tools', label: '取证方法', description: '配置发生故障时要查询的日志、调用链、拨测和服务状态。' },
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

function firstQueryValue(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value
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
