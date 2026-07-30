import type {
  DeploymentTopologyAnalysisStatus,
  DeploymentTopologyObservationStatus,
} from '@/api'

export const MAX_DEPLOYMENT_SNAPSHOT_BYTES = 512 * 1024
export const MAX_DEPLOYMENT_PROBE_NODES = 32

export interface DeploymentTopologySnapshotPreview {
  schemaVersion: string
  system: string
  systemLabel: string
  nodeCount: number
  linkCount: number
  configuredProbeNodes: number
  unconfiguredNodeCount: number
}

const SNAPSHOT_KIND = 'chain-board.runtime-topology-snapshot'
const ANALYSIS_PRESENTATION: Record<
  DeploymentTopologyAnalysisStatus,
  { label: string; tone: 'success' | 'danger' | 'warning' | 'neutral' }
> = {
  NETWORK_PROBLEM_DETECTED: { label: '发现失败拨测节点', tone: 'danger' },
  NO_PROBLEM_OBSERVED: { label: '已覆盖拨测未发现异常', tone: 'success' },
  PARTIAL_OBSERVATION: { label: '已有结果，但网络覆盖不完整', tone: 'warning' },
  NO_PROBES_CONFIGURED: { label: '部署图没有可执行拨测', tone: 'neutral' },
  INSUFFICIENT_EVIDENCE: { label: '证据不足，无法判断网络状态', tone: 'neutral' },
}
const OBSERVATION_PRESENTATION: Record<
  DeploymentTopologyObservationStatus,
  { label: string; tone: 'success' | 'danger' | 'warning' }
> = {
  HEALTHY: { label: '拨测可达', tone: 'success' },
  FAILED: { label: '拨测失败', tone: 'danger' },
  IDENTITY_MISMATCH: { label: '证据身份不匹配', tone: 'warning' },
  UNAVAILABLE: { label: '证据不可用', tone: 'warning' },
}

function record(value: unknown, message: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(message)
  return value as Record<string, unknown>
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

export function inspectDeploymentTopologySnapshot(
  value: unknown,
): DeploymentTopologySnapshotPreview {
  const snapshot = record(value, '部署图快照必须是 JSON 对象')
  if (text(snapshot.kind) !== SNAPSHOT_KIND) throw new Error('不支持的部署图快照类型')
  const schemaVersion = text(snapshot.schemaVersion)
  if (!schemaVersion) throw new Error('部署图快照缺少 schemaVersion')
  const system = record(snapshot.system, '部署图快照缺少 system')
  const systemCode = text(system.code)
  if (!systemCode) throw new Error('部署图快照缺少 system.code')
  const topology = record(snapshot.topology, '部署图快照缺少 topology')
  if (!Array.isArray(topology.nodes)) throw new Error('部署图快照缺少 topology.nodes')
  if (!Array.isArray(topology.links)) throw new Error('部署图快照缺少 topology.links')
  if (topology.nodes.length > 100) throw new Error('部署图节点数量不能超过 100')
  if (topology.links.length > 300) throw new Error('部署图链路数量不能超过 300')

  const configuredProbeNodes = topology.nodes.filter((rawNode) => {
    if (!rawNode || typeof rawNode !== 'object' || Array.isArray(rawNode)) return false
    const node = rawNode as Record<string, unknown>
    return Boolean(text(node.url) && text(node.guance_url))
  }).length
  if (configuredProbeNodes > MAX_DEPLOYMENT_PROBE_NODES) {
    throw new Error(`部署图可执行拨测不能超过 ${MAX_DEPLOYMENT_PROBE_NODES} 个`)
  }

  return {
    schemaVersion,
    system: systemCode,
    systemLabel: text(system.label) || systemCode,
    nodeCount: topology.nodes.length,
    linkCount: topology.links.length,
    configuredProbeNodes,
    unconfiguredNodeCount: topology.nodes.length - configuredProbeNodes,
  }
}

export function deploymentAnalysisLabel(status: DeploymentTopologyAnalysisStatus): string {
  return ANALYSIS_PRESENTATION[status].label
}

export function deploymentAnalysisTone(
  status: DeploymentTopologyAnalysisStatus,
): 'success' | 'danger' | 'warning' | 'neutral' {
  return ANALYSIS_PRESENTATION[status].tone
}

export function observationStatusLabel(status: DeploymentTopologyObservationStatus): string {
  return OBSERVATION_PRESENTATION[status].label
}

export function observationTone(
  status: DeploymentTopologyObservationStatus,
): 'success' | 'danger' | 'warning' {
  return OBSERVATION_PRESENTATION[status].tone
}
