import type { AxiosInstance } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { createTroubleshootingApi } from '../troubleshooting-client'

describe('workspace Guance recording batch client', () => {
  it('reads the current workspace batch from the v2 projection without scope parameters', async () => {
    const get = vi.fn().mockResolvedValue({ data: null })
    const api = createTroubleshootingApi({ get } as unknown as AxiosInstance)

    await api.currentGuanceRecordingBatch()

    expect(get).toHaveBeenCalledWith(
      '/troubleshooting/evidence/guance/recording-batches/current',
      { baseURL: '/api/v2' },
    )
  })
})
