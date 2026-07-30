import { describe, expect, it } from 'vitest'
import {
  DEFAULT_WORKBENCH_VIEW,
  WORKBENCH_CAPABILITY_ACTIONS,
  diagnosisSelectionMode,
  isDiagnosisViewMode,
  resolveWorkbenchView,
  shouldShowQueuePanel,
  workbenchViewQuery,
} from '../workbenchView'

describe('troubleshooting workbench view mode', () => {
  it('uses the traditional list as the default view', () => {
    expect(DEFAULT_WORKBENCH_VIEW).toBe('LIST')
    expect(resolveWorkbenchView(undefined, undefined)).toBe('LIST')
  })

  it('keeps diagnosis deep links opening the queue detail view', () => {
    expect(resolveWorkbenchView(undefined, 'diag-123')).toBe('QUEUE')
    expect(resolveWorkbenchView('queue', undefined)).toBe('QUEUE')
  })

  it('opens list records in a full-width detail view without the queue panel', () => {
    expect(resolveWorkbenchView('detail', 'diag-123')).toBe('DETAIL')
    expect(workbenchViewQuery('DETAIL', 'diag-123')).toEqual({
      view: 'detail',
      diagnosisId: 'diag-123',
    })
    expect(shouldShowQueuePanel('DETAIL')).toBe(false)
    expect(shouldShowQueuePanel('QUEUE')).toBe(true)
  })

  it('falls back to the list when a detail route has no diagnosis', () => {
    expect(resolveWorkbenchView('detail', undefined)).toBe('LIST')
    expect(workbenchViewQuery('DETAIL')).toEqual({ view: 'list' })
  })

  it('centralizes which modes own a diagnosis detail', () => {
    expect(isDiagnosisViewMode('LIST')).toBe(false)
    expect(isDiagnosisViewMode('QUEUE')).toBe(true)
    expect(isDiagnosisViewMode('DETAIL')).toBe(true)
    expect(diagnosisSelectionMode('LIST')).toBe('DETAIL')
    expect(diagnosisSelectionMode('QUEUE')).toBe('QUEUE')
    expect(diagnosisSelectionMode('DETAIL')).toBe('DETAIL')
  })

  it('lets an explicit list view override a retained diagnosis id', () => {
    expect(resolveWorkbenchView('list', 'diag-123')).toBe('LIST')
  })

  it('builds stable route query values for both modes', () => {
    expect(workbenchViewQuery('LIST')).toEqual({ view: 'list' })
    expect(workbenchViewQuery('QUEUE', 'diag-123')).toEqual({
      view: 'queue',
      diagnosisId: 'diag-123',
    })
  })

  it('keeps the compact capability menu in one ordered registry', () => {
    const commands = WORKBENCH_CAPABILITY_ACTIONS.map(action => action.command)

    expect(commands).toEqual(['playbooks', 'synthesis', 'guance', 'deployment', 'ledger'])
    expect(new Set(commands).size).toBe(commands.length)
  })
})
