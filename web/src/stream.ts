import { useEffect, useSyncExternalStore } from 'react'
import type { StreamRecord } from './types'

const MAX_BUFFER = 500

type Listener = () => void

/** A record's place on the broker: unique, and the same when the gateway replays it. */
export function recordKey(r: StreamRecord): string {
  return `${r.topic}-${r.partition}-${r.offset}`
}

/**
 * One shared connection to GET /api/v1/stream for the whole app, opened once and kept for the tab's
 * life. Needs the bearer header, so fetch + ReadableStream, not EventSource. The token is only
 * checked when the stream opens, so a renewed token waits for the next reconnect rather than forcing
 * one. Reconnects with backoff; keeps a capped ring buffer.
 */
class LedgerStream {
  private records: StreamRecord[] = []
  private listeners = new Set<Listener>()
  private status: 'connecting' | 'open' | 'closed' = 'closed'
  private backoffMs = 500
  private token = ''
  private retry: ReturnType<typeof setTimeout> | undefined

  // arrow properties: stable identities, so useSyncExternalStore doesn't resubscribe every render
  getRecords = () => this.records
  getStatus = () => this.status
  subscribe = (fn: Listener) => {
    this.listeners.add(fn)
    return () => {
      this.listeners.delete(fn)
    }
  }

  private notify() {
    for (const l of this.listeners) l()
  }

  /** The first call opens the stream; later calls only hand it a fresher token for its next reconnect. */
  start(token: string) {
    this.token = token
    if (this.status === 'closed') this.connect()
  }

  private async connect() {
    clearTimeout(this.retry)
    this.status = 'connecting'
    this.notify()
    try {
      const res = await fetch('/api/v1/stream', {
        headers: { Authorization: `Bearer ${this.token}`, Accept: 'text/event-stream' },
      })
      if (!res.ok || !res.body) throw new Error(`stream ${res.status}`)
      this.status = 'open'
      this.backoffMs = 500
      this.notify()
      await this.pump(res.body)
    } catch {
      this.status = 'closed'
      this.notify()
      this.retry = setTimeout(() => this.connect(), this.backoffMs)
      this.backoffMs = Math.min(this.backoffMs * 2, 10_000)
    }
  }

  private async pump(body: ReadableStream<Uint8Array>) {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    for (;;) {
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
      const key = recordKey(record)
      // every reconnect replays the gateway's last 200, most of which this buffer already holds
      if (this.records.some((r) => recordKey(r) === key)) return
      this.records = [...this.records, record].slice(-MAX_BUFFER)
      this.notify()
    } catch {
      // a malformed record is dropped, not fatal to the connection
    }
  }
}

export const ledgerStream = new LedgerStream()

/** Starts the one app-wide stream (idempotent), subscribes for re-renders. */
export function useLedgerStream(token: string | undefined) {
  useEffect(() => {
    if (token) ledgerStream.start(token)
  }, [token])

  const records = useSyncExternalStore(ledgerStream.subscribe, ledgerStream.getRecords)
  const status = useSyncExternalStore(ledgerStream.subscribe, ledgerStream.getStatus)
  return { records, status }
}
