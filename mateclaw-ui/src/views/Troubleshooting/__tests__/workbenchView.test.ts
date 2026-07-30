import { describe, expect, it } from 'vitest'
import {
  DEFAULT_WORKBENCH_VIEW,
  WORKBENCH_CAPABILITY_ACTIONS,
  resolveWorkbenchView,
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
