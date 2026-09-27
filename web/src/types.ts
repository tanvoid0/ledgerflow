// Shapes mirror the service records exactly (see the controllers named in the lane brief).
// Money is always minor units; format with formatMoney before rendering.

export interface Money {
  minorUnits: number
  currency: string
}

export interface Wallet {
  id: string
  label: string
}

export interface Account {
  id: string
  name: string
  wallets: Wallet[]
}

export interface WalletBalance {
  label: string
  currency: string
  balanceMinor: number
  heldMinor: number
  availableMinor: number
}

export interface AccountBalances {
  accountId: string
  wallets: WalletBalance[]
}

export interface HeldWallet {
  holdId: string
  wallet: string
}

export type PaymentStep = 'RESERVE' | 'AUTHORIZE' | 'ISSUE'
export type FailureReason = 'WALLETS_UNAVAILABLE' | 'PAYMENT_DECLINED' | 'ISSUE_FAILED' | 'TIMED_OUT'

// Discriminated on `state`, exactly as PaymentState is serialised (JsonTypeInfo SIMPLE_NAME).
export type PaymentState =
  | { state: 'Requested'; paymentId: string; accountId: string; wallets: string[]; amount: Money; held: HeldWallet[] }
  | { state: 'AuthorizationPending'; paymentId: string; accountId: string; held: HeldWallet[]; amount: Money }
  | { state: 'CapturePending'; paymentId: string; accountId: string; held: HeldWallet[]; amount: Money; authorizationId: string }
  | { state: 'Captured'; paymentId: string; captures: string[] }
  | { state: 'Failed'; paymentId: string; reason: FailureReason; failedAt: PaymentStep | null }

export const TERMINAL_STATES: PaymentState['state'][] = ['Captured', 'Failed']

export interface RiskCase {
  paymentId: string
  decision: 'REVIEW' | 'BLOCK'
  ruleFired: string | null
  score: number
  modelVersion: string
  features: string
  narrative: string | null
  generatedBy: string | null
  decidedAt: string
}

export interface JobExecution {
  jobName: string
  executionId: number
  status: string
  exitCode: string
  startTime: string | null
  endTime: string | null
}

// One record off the gateway's SSE stream: a raw Kafka record wrapping an EventEnvelope.
export interface EventEnvelope {
  eventId: string
  eventType: string
  schemaVersion: number
  aggregateId: string
  aggregateVersion: number
  occurredAt: string
  correlationId: string | null   // null on events no request caused (backfills)
  causationId: string | null
  payload: unknown
}

export interface StreamRecord {
  topic: string
  partition: number
  offset: number
  key: string | null
  timestamp: number   // epoch ms, the broker's record timestamp
  value: EventEnvelope
}
