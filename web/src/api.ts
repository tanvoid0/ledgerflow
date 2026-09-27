// Every write mints its own X-Request-Id; the caller keeps it, it becomes the correlationId
// on every event that write causes (used by the payment detail page's saga timeline).

/** 401 anywhere means the token expired between renewals; pages don't each handle it, App wires this once. */
export let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn
}

export class ApiError extends Error {
  status: number
  path: string
  constructor(status: number, detail: string, path: string) {
    super(`${status} on ${path}${detail ? `: ${detail}` : ''}`)
    this.status = status
    this.path = path
  }
}

/** problem+json's detail and field errors, or Spring's default error body; the status line itself has no reason phrase. */
async function errorDetail(res: Response): Promise<string> {
  const body: { detail?: string; error?: string; errors?: unknown } | null = await res.json().catch(() => null)
  const errors = Array.isArray(body?.errors) ? body.errors.map(String) : []
  return [body?.detail ?? body?.error, ...errors].filter(Boolean).join('; ')
}

async function call<T>(path: string, token: string | undefined, init: RequestInit = {}): Promise<{ data: T; headers: Headers }> {
  let res: Response
  try {
    res = await fetch(path, {
      ...init,
      headers: { ...(init.headers ?? {}), Authorization: token ? `Bearer ${token}` : '' },
    })
  } catch {
    throw new ApiError(0, 'network error - gateway not reachable on 8088', path)
  }
  if (res.status === 401) onUnauthorized()
  if (!res.ok) throw new ApiError(res.status, await errorDetail(res), path)
  return { data: res.status === 204 ? (undefined as T) : await res.json(), headers: res.headers }
}

export async function get<T>(path: string, token: string | undefined): Promise<T> {
  return (await call<T>(path, token)).data
}

/** For the reads that answer partly in a header (X-Projection on a read-your-write). */
export function getWithHeaders<T>(path: string, token: string | undefined): Promise<{ data: T; headers: Headers }> {
  return call<T>(path, token)
}

/**
 * Every write: JSON body, a request id (generated unless the caller wants to keep it - the
 * saga timeline and read-your-write pages need it back to correlate what the write causes).
 */
export async function post<T>(
  path: string,
  token: string | undefined,
  body: unknown,
  extraHeaders: Record<string, string> = {},
): Promise<{ data: T; requestId: string }> {
  const requestId = crypto.randomUUID()
  const { data } = await call<T>(path, token, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'X-Request-Id': requestId, ...extraHeaders },
    body: JSON.stringify(body),
  })
  return { data, requestId }
}
