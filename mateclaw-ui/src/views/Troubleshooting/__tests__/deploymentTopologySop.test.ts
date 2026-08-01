import { describe, expect, it } from 'vitest'
import {
  deploymentTopologyOptionLabel,
  deploymentAnalysisLabel,
  inspectDeploymentTopologySnapshot,
  observationStatusLabel,
  shouldShowDeploymentTopologyProbe,
} from '../deploymentTopologySop'

describe('deployment topology SOP entry', () => {
  it('shows the topology tool only for the selected diagnosis capability', () => {
    expect(shouldShowDeploymentTopologyProbe({
      diagnosisId: 'diag-topology',
      scenarioAffordances: [{ scenarioKey: 'deployment_topology_probe', required: true }],
    }, 'diag-topology')).toBe(true)
  })

  it('hides stale or unavailable topology capabilities', () => {
    expect(shouldShowDeploymentTopologyProbe({
      diagnosisId: 'diag-previous',
      scenarioAffordances: [{ scenarioKey: 'deployment_topology_probe', required: true }],
    }, 'diag-current')).toBe(false)
    expect(shouldShowDeploymentTopologyProbe({
      diagnosisId: 'diag-current',
      scenarioAffordances: [{ scenarioKey: 'deployment_topology_probe', required: false }],
    }, 'diag-current')).toBe(false)
    expect(shouldShowDeploymentTopologyProbe(null, 'diag-current')).toBe(false)
  })

  it('counts only nodes with both a target URL and Guance probe metadata as configured', () => {
    const preview = inspectDeploymentTopologySnapshot({
      schemaVersion: '1.0',
      kind: 'chain-board.runtime-topology-snapshot',
      system: { code: 'csp-deployment', label: 'CSP 部署架构' },
      topology: {
        nodes: [
          {
            key: 'csp-prm-miniapp',
            label: 'PRM 小程序',
            type: 'client',
            url: 'https://example.test',
            guance_url: 'http://dataflux.example.test/cloudDial?query=b64-example',
          },
          { key: 'gateway', label: '网关', type: 'gw', url: '', guance_url: '' },
        ],
        links: [{ source: 'csp-prm-miniapp', target: 'gateway' }],
      },
    })

    expect(preview).toEqual({
      schemaVersion: '1.0',
      system: 'csp-deployment',
      systemLabel: 'CSP 部署架构',
      nodeCount: 2,
      linkCount: 1,
      configuredProbeNodes: 1,
      unconfiguredNodeCount: 1,
    })
  })

  it('rejects the wrong snapshot kind and oversized topology collections before upload', () => {
    expect(() => inspectDeploymentTopologySnapshot({
      schemaVersion: '1.0',
      kind: 'other.snapshot',
      system: { code: 'csp-deployment' },
      topology: { nodes: [], links: [] },
    })).toThrow('不支持的部署图快照类型')

    expect(() => inspectDeploymentTopologySnapshot({
      schemaVersion: '1.0',
      kind: 'chain-board.runtime-topology-snapshot',
      system: { code: 'csp-deployment' },
      topology: {
        nodes: Array.from({ length: 101 }, (_, index) => ({ key: `n-${index}` })),
        links: [],
      },
    })).toThrow('节点数量不能超过 100')

    expect(() => inspectDeploymentTopologySnapshot({
      schemaVersion: '1.0',
      kind: 'chain-board.runtime-topology-snapshot',
      system: { code: 'csp-deployment' },
      topology: {
        nodes: Array.from({ length: 33 }, (_, index) => ({
          key: `n-${index}`,
          url: `https://n-${index}.example.test`,
          guance_url: `https://guance.example.test/task/${index}`,
        })),
        links: [],
      },
    })).toThrow('可执行拨测不能超过 32 个')
  })

  it('keeps partial coverage and evidence identity mismatch explicit in product language', () => {
    expect(deploymentAnalysisLabel('PARTIAL_OBSERVATION')).toContain('覆盖不完整')
    expect(deploymentAnalysisLabel('NO_PROBLEM_OBSERVED')).toContain('已覆盖拨测未发现异常')
    expect(observationStatusLabel('IDENTITY_MISMATCH')).toBe('证据身份不匹配')
    expect(observationStatusLabel('UNAVAILABLE')).toBe('证据不可用')
  })

  it('makes imported workspace topologies understandable in the selector', () => {
    expect(deploymentTopologyOptionLabel({
      topologyId: 'topology-shared',
      name: '马来西亚生产拓扑',
      system: 'csp-deployment',
      systemLabel: 'CSP 部署架构',
      schemaVersion: '1.0',
      exportedAt: '2026-07-30T07:00:43.589Z',
      nodeCount: 21,
      linkCount: 27,
      configuredProbeNodes: 1,
      importedBy: 'alice',
      importedAt: '2026-07-30T10:00:00Z',
    })).toBe('马来西亚生产拓扑 · CSP 部署架构 · 21 节点 / 1 拨测')
  })
})
