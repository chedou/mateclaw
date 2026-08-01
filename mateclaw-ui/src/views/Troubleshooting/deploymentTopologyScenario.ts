import type {
  CreateDeploymentTopologyScenarioRequest,
  IncidentSeverity,
} from '@/api'
import {
  formalIncidentFormErrors,
  type FormalIncidentForm,
} from './incidentReport'

export interface DeploymentTopologyScenarioForm {
  system: string
  service: string
  title: string
  severity: IncidentSeverity
  traceId: string
  rehearsal: boolean
}

export const EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO: DeploymentTopologyScenarioForm = {
  system: '',
  service: '',
  title: '部署拓扑网络连通性异常，需要执行拨测分析',
  severity: 'P2',
  traceId: '',
  rehearsal: true,
}

function asIncidentForm(form: DeploymentTopologyScenarioForm): FormalIncidentForm {
  return { ...form, errorCode: '' }
}

export function deploymentTopologyScenarioFormErrors(
  form: DeploymentTopologyScenarioForm,
): string[] {
  return formalIncidentFormErrors(asIncidentForm(form))
}

export function buildDeploymentTopologyScenarioRequest(
  form: DeploymentTopologyScenarioForm,
): CreateDeploymentTopologyScenarioRequest {
  const errors = deploymentTopologyScenarioFormErrors(form)
  if (errors.length) throw new Error(errors[0])
  const traceId = form.traceId.trim()
  return {
    system: form.system.trim(),
    service: form.service.trim(),
    title: form.title.trim(),
    severity: form.severity,
    ...(traceId ? { traceId } : {}),
    rehearsal: form.rehearsal,
  }
}

export function deploymentTopologyScenarioSelector(system: string): string {
  const normalized = system.trim().toLowerCase()
  return normalized
    ? `${normalized}:scenario:deployment_topology_probe`
    : '等待填写故障系统'
}

export function deploymentTopologyScenarioProjectionLoaded(
  expectedDiagnosisId: string,
  loadedDiagnosisId: string | null | undefined,
  projectionLoaded: boolean,
): boolean {
  return projectionLoaded && expectedDiagnosisId === loadedDiagnosisId
}

export function deploymentTopologyScenarioLoadFailureMessage(
  diagnosisId: string,
): string {
  return `场景 Diagnosis ${diagnosisId} 已创建，但工作台详情或能力投影加载失败；请从排障列表重新打开。`
}
