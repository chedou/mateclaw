import { describe, expect, it } from 'vitest'
import routerSource from '../../../router/index.ts?raw'
import troubleshootingLayoutSource from '../TroubleshootingLayout.vue?raw'
import {
  WORKBENCH_CAPABILITY_GROUPS,
  WORKBENCH_PRIMARY_CAPABILITIES,
  legacyEvidenceSynthesisLocation,
  normalizeEvidenceSetupSection,
  normalizeWorkbenchOverlayCapability,
  observabilityAssetsLocation,
  safeTroubleshootingReturnPath,
  workbenchOverlayLocation,
} from '../workbenchCapabilityMenu'

describe('troubleshooting secondary navigation information architecture', () => {
  it('keeps daily navigation small and moves specialist controls under advanced settings', () => {
    const groupedItems = WORKBENCH_CAPABILITY_GROUPS.flatMap(group => group.items)
    const items = [...WORKBENCH_PRIMARY_CAPABILITIES, ...groupedItems]

    expect(WORKBENCH_CAPABILITY_GROUPS.map(group => group.label)).toEqual([
      '高级设置',
      '复盘与沉淀',
    ])
    expect(WORKBENCH_PRIMARY_CAPABILITIES.map(item => ({
      key: item.key,
      label: item.label,
      section: item.section,
    }))).toEqual([
      { key: 'evidence-modules', label: '接入系统', section: 'modules' },
    ])
    expect(items.map(item => item.key)).toEqual([
      'evidence-modules',
      'evidence-tools',
      'evidence-source',
      'playbooks',
      'ledger',
      'case-knowledge',
    ])
    expect(items.filter(item => item.command === 'observability-assets').map(item => ({
      section: item.section,
      label: item.label,
    }))).toEqual([
      { section: 'modules', label: '接入系统' },
      { section: 'tools', label: '取证方法' },
      { section: 'source', label: '数据连接' },
    ])
    expect(items.map(item => item.description)).toEqual([
      '新增系统、模块和资源范围',
      '配置日志、调用链和拨测方法',
      '检查观测云能否正常读取',
      '场景、步骤与判断标准',
      '用真实样本验证效果',
      '沉淀已解决的故障',
    ])
    expect(items.find(item => item.command === 'guance')).toBeUndefined()
  })

  it('keeps each evidence setup menu addressable without losing the return target', () => {
    expect(normalizeEvidenceSetupSection(undefined)).toBe('modules')
    expect(normalizeEvidenceSetupSection('tools')).toBe('tools')
    expect(normalizeEvidenceSetupSection('source')).toBe('source')
    expect(normalizeEvidenceSetupSection('unknown')).toBe('modules')

    expect(observabilityAssetsLocation(
      undefined,
      '/troubleshooting?view=detail&diagnosisId=diag-1',
      'tools',
    )).toEqual({
      path: '/troubleshooting/observability-assets',
      query: {
        section: 'tools',
        returnTo: '/troubleshooting?view=detail&diagnosisId=diag-1',
      },
    })
  })

  it('keeps advanced and learning controls collapsed while opening active deep links', () => {
    expect(troubleshootingLayoutSource).toContain('WORKBENCH_PRIMARY_CAPABILITIES')
    expect(troubleshootingLayoutSource).toContain('aria-expanded="capabilityGroupExpanded(group)"')
    expect(troubleshootingLayoutSource).toContain('v-show="capabilityGroupExpanded(group)"')
    expect(troubleshootingLayoutSource).toContain('return capabilityGroupActive(group) || manuallyExpandedGroups.value.has(group.key)')
  })

  it('maps legacy catalog tabs to the matching setup section', () => {
    expect(routerSource).toContain("tab === 'contracts' || tab === 'routes'")
    expect(routerSource).toContain("tab === 'acceptance'")
    expect(routerSource).toContain("delete query.tab")
    expect(routerSource).toContain("query: { ...query, section }")
  })

  it('redirects the legacy synthesis deep link into diagnosis evaluation', () => {
    expect(legacyEvidenceSynthesisLocation(
      '/troubleshooting?view=detail&diagnosisId=diag-1',
    )).toEqual({
      path: '/troubleshooting',
      query: {
        view: 'detail',
        diagnosisId: 'diag-1',
        capability: 'ledger',
        focus: 'evidence-synthesis',
      },
    })
  })

  it('no longer offers a way to construct a link into the retired catalog page', async () => {
    // 「查询规则说明书」和取证接入调的是同一组接口、渲染同一份数据，只是一个能改
    // 一个只能看，于是配一条规则要在两页之间来回跳。规格已折进取证接入页。
    //
    // 这条断言钉的是「没有人能再造出指向它的链接」——路由里保留的重定向是给旧书签
    // 兜底的，不是给代码继续用的。留着一个 location 构造器，跳转迟早会长回来。
    const menu = await import('../workbenchCapabilityMenu') as Record<string, unknown>

    expect(menu.evidenceCatalogLocation).toBeUndefined()
    expect(menu.normalizeEvidenceCatalogTab).toBeUndefined()
    expect(menu.EVIDENCE_CATALOG_DESTINATIONS).toBeUndefined()
  })

  it('only accepts a local troubleshooting page as the return target', () => {
    expect(safeTroubleshootingReturnPath('/troubleshooting?view=list')).toBe(
      '/troubleshooting?view=list',
    )
    expect(safeTroubleshootingReturnPath('https://example.com')).toBeNull()
    expect(safeTroubleshootingReturnPath('//example.com/troubleshooting')).toBeNull()
    expect(safeTroubleshootingReturnPath('/troubleshooting/evidence-catalog?tab=routes')).toBeNull()
    expect(safeTroubleshootingReturnPath('/troubleshooting/sops')).toBeNull()
    expect(safeTroubleshootingReturnPath('/troubleshooting-other')).toBeNull()
  })

  it('keeps the legacy Guance overlay deep link only as a compatibility action', () => {
    expect(normalizeWorkbenchOverlayCapability('guance')).toBe('guance')
    expect(normalizeWorkbenchOverlayCapability(['ledger'])).toBe('ledger')
    expect(normalizeWorkbenchOverlayCapability('case-knowledge')).toBe('case-knowledge')
    expect(normalizeWorkbenchOverlayCapability('synthesis')).toBeNull()
    expect(normalizeWorkbenchOverlayCapability('unknown')).toBeNull()
  })

  it('returns from a management page to the same diagnosis before opening an overlay', () => {
    expect(workbenchOverlayLocation(
      'ledger',
      '/troubleshooting?view=detail&diagnosisId=diag-1',
    )).toEqual({
      path: '/troubleshooting',
      query: {
        view: 'detail',
        diagnosisId: 'diag-1',
        capability: 'ledger',
      },
    })

    expect(workbenchOverlayLocation('guance', '/troubleshooting/sops')).toEqual({
      path: '/troubleshooting',
      query: {
        view: 'list',
        capability: 'guance',
      },
    })
  })
})
