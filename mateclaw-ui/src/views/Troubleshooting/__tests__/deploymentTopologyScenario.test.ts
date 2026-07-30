import { describe, expect, it } from 'vitest'
import {
  buildDeploymentTopologyScenarioRequest,
  deploymentTopologyScenarioFormErrors,
  deploymentTopologyScenarioLoadFailureMessage,
  deploymentTopologyScenarioProjectionLoaded,
  deploymentTopologyScenarioSelector,
  type DeploymentTopologyScenarioForm,
} from '../deploymentTopologyScenario'

const form: DeploymentTopologyScenarioForm = {
  system: ' CSDP ',
  service: ' csp-prm-miniapp ',
  title: ' 海外客户访问超时 ',
  severity: 'P1',
  traceId: ' trace-safe-1 ',
  rehearsal: true,
}

describe('deployment topology scenario intake', () => {
  it('submits only business context while the server owns scenario and tool routing', () => {
    const request = buildDeploymentTopologyScenarioRequest(form)

    expect(request).toEqual({
      system: 'CSDP',
      service: 'csp-prm-miniapp',
      title: '海外客户访问超时',
      severity: 'P1',
      traceId: 'trace-safe-1',
      rehearsal: true,
    })
    for (const forbidden of [
      'scenarioKey', 'toolKey', 'sopKey', 'playbookId', 'evidence', 'rawInput',
    ]) {
      expect(request).not.toHaveProperty(forbidden)
    }
  })

  it('shows the canonical server selector without treating it as editable input', () => {
    expect(deploymentTopologyScenarioSelector(' CSDP '))
      .toBe('csdp:scenario:deployment_topology_probe')
    expect(deploymentTopologyScenarioSelector('')).toBe('等待填写故障系统')
  })

  it('rejects missing context and raw developer evidence before submission', () => {
    expect(deploymentTopologyScenarioFormErrors({
      ...form,
      system: '',
      title: '2026-07-31 09:00:00 ERROR upstream timeout',
    })).toEqual(expect.arrayContaining([
      '请选择或填写故障系统',
      '故障现象不能包含 DQL、原始日志或堆栈正文',
    ]))
  })

  it('never reports a successfully created Diagnosis as not created when detail loading fails', () => {
    const diagnosisId = 'diag-topology-1'

    expect(deploymentTopologyScenarioProjectionLoaded(
      diagnosisId, null, false,
    )).toBe(false)
    expect(deploymentTopologyScenarioProjectionLoaded(
      diagnosisId, diagnosisId, true,
    )).toBe(true)
    expect(deploymentTopologyScenarioLoadFailureMessage(diagnosisId))
      .toContain(`Diagnosis ${diagnosisId} 已创建`)
    expect(deploymentTopologyScenarioLoadFailureMessage(diagnosisId))
      .not.toContain('场景未创建')
  })
})
