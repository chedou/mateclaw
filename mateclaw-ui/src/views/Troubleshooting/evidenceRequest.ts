import type { EvidenceChainPreviewRequest } from '@/api'

/** Keeps every Guance Evidence Spine entry point on the same request contract. */
export function normalizeEvidenceChainPreviewRequest(
  request: EvidenceChainPreviewRequest,
): EvidenceChainPreviewRequest {
  return {
    system: request.system.trim(),
    service: request.service.trim(),
    searchTerm: request.searchTerm.trim(),
    window: request.window.trim(),
    occurredAt: request.occurredAt?.trim() || null,
  }
}
