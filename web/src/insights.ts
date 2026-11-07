import { post } from './api'

// The AI assistant endpoints on the gateway (docs/ai/0001). Phase 1 answers read-only questions as a
// stream; Phase 4 proposes a refund the user approves - the propose call moves nothing, the fire is a
// separate ledger-write POST to the ordinary refund endpoint, re-validated server-side.

/** The proposal the model emits, already narrowed by the gateway's guard: a refund_order with a real id, or a decline. */
export interface Proposal {
  action: 'refund_order' | null
  orderId: string | null
  reason: string | null
  decline: string | null
}

/** Phase 4: ask for a refund proposal. Plain JSON; nothing fires here. */
export async function proposeRefund(question: string, token: string): Promise<Proposal> {
  const { data } = await post<Proposal>('/api/v1/insights/propose', token, { question })
  return data
}

/** Phase 4: fire an approved refund down the step-35 path. ledger-write only (the gateway/ticketing enforce it). */
export async function fireRefund(orderId: string, token: string): Promise<void> {
  await post<void>(`/api/v1/orders/${orderId}/refund`, token, {})
}

/**
 * Phase 1: stream a read-only answer token by token. SseEmitter sends one event per delta; a delta that
 * itself holds newlines arrives as several `data:` lines, joined back with \n per the SSE spec. Resolves
 * when the stream completes; rejects only on a non-2xx open (the body is where the "unavailable" text comes).
 */
export async function askLedger(
  question: string,
  token: string,
  onDelta: (text: string) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch('/api/v1/insights', {
    method: 'POST',
    headers: { 'content-type': 'application/json', Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
    body: JSON.stringify({ question }),
    signal,
  })
  if (!res.ok || !res.body) throw new Error(`insights ${res.status}`)
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let sep
    while ((sep = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, sep)
      buf = buf.slice(sep + 2)
      const data = chunk
        .split('\n')
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.slice(5).replace(/^ /, ''))
        .join('\n')
      if (data) onDelta(data)
    }
  }
}
