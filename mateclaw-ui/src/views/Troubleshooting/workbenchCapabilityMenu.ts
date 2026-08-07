import {
  TROUBLESHOOTING_UI_LABELS,
  type WorkbenchCapabilityCommand,
} from './workbenchView'
import { EVIDENCE_SYNTHESIS_FOCUS } from './synthesisPreview'

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
