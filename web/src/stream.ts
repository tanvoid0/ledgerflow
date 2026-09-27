import { useEffect, useSyncExternalStore } from 'react'
import type { StreamRecord } from './types'

const MAX_BUFFER = 500

type Listener = () => void

/**
 * One shared connection to GET /api/v1/stream for the whole app. Needs the bearer header, so
 * fetch + ReadableStream, not EventSource. Reconnects with backoff; keeps a capped ring buffer.
 */
class LedgerStream {
  private records: StreamRecord[] = []
  private listeners = new Set<Listener>()
  private controller: AbortController | null = null
  private status: 'connecting' | 'open' | 'closed' = 'closed'
  private backoffMs = 500

  getRecords() {
    return this.records
  }

  getStatus() {
    return this.status
  }

  subscribe(fn: Listener) {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  private notify() {
    for (const l of this.listeners) l()
  }

  async connect(token: string) {
    this.controller?.abort()
    const controller = new AbortController()
    this.controller = controller
    this.status = 'connecting'
    this.notify()
    try {
      const res = await fetch('/api/v1/stream', {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal,
      })
      if (!res.ok || !res.body) throw new Error(`stream ${res.status}`)
      this.status = 'open'
      this.backoffMs = 500
      this.notify()
      await this.pump(res.body, controller.signal)
    } catch {
      if (controller.signal.aborted) return
      this.status = 'closed'
      this.notify()
      const wait = this.backoffMs
      this.backoffMs = Math.min(this.backoffMs * 2, 10_000)
      setTimeout(() => this.connect(token), wait)
    }
  }

  private async pump(body: ReadableStream<Uint8Array>, signal: AbortSignal) {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      if (done) throw new Error('stream ended')
      buf += decoder.decode(value, { stream: true })
      let sep
      while ((sep = buf.indexOf('\n\n')) >= 0) {
        this.handleEvent(buf.slice(0, sep))
        buf = buf.slice(sep + 2)
      }
    }
  }

  private handleEvent(chunk: string) {
    const dataLine = chunk.split('\n').find((l) => l.startsWith('data:'))
    if (!dataLine) return
    try {
      const record: StreamRecord = JSON.parse(dataLine.slice(5).trim())
      this.records = [...this.records, record].slice(-MAX_BUFFER)
      this.notify()
    } catch {
      // a malformed record is dropped, not fatal to the connection
    }
  }

  disconnect() {
    this.controller?.abort()
    this.status = 'closed'
  }
}

export const ledgerStream = new LedgerStream()

/** Connects once per token change, subscribes for re-renders. One instance serves every page. */
export function useLedgerStream(token: string | undefined) {
  useEffect(() => {
    if (token) ledgerStream.connect(token)
    return () => ledgerStream.disconnect()
  }, [token])

  const records = useSyncExternalStore(ledgerStream.subscribe.bind(ledgerStream), ledgerStream.getRecords.bind(ledgerStream))
  const status = useSyncExternalStore(ledgerStream.subscribe.bind(ledgerStream), ledgerStream.getStatus.bind(ledgerStream))
  return { records, status }
}
