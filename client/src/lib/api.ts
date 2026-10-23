import type {
  AdminInfo,
  AdminTicketDetail,
  AdminTicketPatch,
  AdminTicketSummary,
  ApiErrorBody,
  Attachment,
  Book,
  ClientConfig,
  CreateTicketInput,
  FieldDetail,
  LoginResponse,
  Page,
  QueueFilters,
  QueueStats,
  TicketDetail,
  TicketSummary,
  SessionResponse,
} from '../types/api.ts'

// Only the API address is configured here. It is not a secret; nothing else belongs in VITE_ variables.
const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
const API = `${BASE_URL}/api/v1`

/** A failed API call, with the server's error code, field messages and request id when it sent them. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: FieldDetail[]
  readonly requestId: string | undefined

  constructor(status: number, code: string, message: string, details: FieldDetail[] = [], requestId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
    this.requestId = requestId
  }
}

// The session itself is an HttpOnly cookie the browser sends on its own; scripts never see it. Only the CSRF
// token (which the server hands out at sign-in and from /auth/session) is kept, in memory, and echoed on writes.
let csrfToken: string | null = null

/** Remember (or forget) the CSRF token for the current session. */
export function setCsrfToken(token: string | null): void {
  csrfToken = token
}

let unauthorizedHandler: (() => void) | null = null

/** Called when the API rejects the session (expired, signed out elsewhere or invalid) so the app can sign the user out. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

/**
 * Where the browser downloads an attachment from. It is a plain link: the session cookie goes along with it,
 * and the server answers with a download (never a page the browser would run).
 */
export function attachmentUrl(role: 'author' | 'admin', ticketId: string, attachmentId: string): string {
  return `${API}/${role}/tickets/${encodeURIComponent(ticketId)}/attachments/${encodeURIComponent(attachmentId)}`
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  return new ApiError(0, 'UNKNOWN', 'Something went wrong. Please try again.')
}

async function request<T>(
  method: string,
  path: string,
  options: { body?: unknown; form?: FormData; signal?: AbortSignal } = {},
): Promise<T> {
  const hadSession = csrfToken !== null
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (csrfToken && method !== 'GET') headers['X-CSRF-Token'] = csrfToken

  let response: Response
  try {
    const init: RequestInit = { method, headers, credentials: 'include' }
    if (options.body !== undefined) init.body = JSON.stringify(options.body)
    // For a file upload the browser sets the multipart Content-Type (with its boundary) itself.
    if (options.form) init.body = options.form
    if (options.signal) init.signal = options.signal
    response = await fetch(`${API}${path}`, init)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server. Check your connection and try again.')
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  let parsed: unknown = null
  if (text) {
    try {
      parsed = JSON.parse(text)
    } catch {
      parsed = null
    }
  }

  if (!response.ok) {
    const body = parsed as ApiErrorBody | null
    if (response.status === 401 && hadSession && path !== '/auth/login') {
      csrfToken = null
      unauthorizedHandler?.()
    }
    throw new ApiError(
      response.status,
      body?.error?.code ?? 'HTTP_ERROR',
      body?.error?.message ?? 'Something went wrong. Please try again.',
      body?.error?.details ?? [],
      body?.requestId,
    )
  }
  return parsed as T
}

export const api = {
  getConfig: (signal?: AbortSignal) => request<ClientConfig>('GET', '/config', signal ? { signal } : {}),
  login: (email: string, password: string) =>
    request<LoginResponse>('POST', '/auth/login', { body: { email, password } }),
  /** Who is signed in (if anyone): asked at startup, since the cookie is invisible to scripts. */
  session: (signal?: AbortSignal) => request<SessionResponse>('GET', '/auth/session', signal ? { signal } : {}),
  logout: () => request<void>('POST', '/auth/logout'),

  listBooks: (signal?: AbortSignal) => request<Book[]>('GET', '/author/books', signal ? { signal } : {}),
  listTickets: (page: number, size: number, signal?: AbortSignal) =>
    request<Page<TicketSummary>>('GET', `/author/tickets?page=${page}&size=${size}`, signal ? { signal } : {}),
  getTicket: (id: string, signal?: AbortSignal) =>
    request<TicketDetail>('GET', `/author/tickets/${encodeURIComponent(id)}`, signal ? { signal } : {}),
  createTicket: (input: CreateTicketInput) => request<TicketDetail>('POST', '/author/tickets', { body: input }),
  /** A follow-up message on the author's own ticket (reopens a resolved ticket). */
  authorReply: (id: string, body: string) =>
    request<TicketDetail>('POST', `/author/tickets/${encodeURIComponent(id)}/replies`, { body: { body } }),

  /** Attaches one file to the author's own ticket. */
  uploadAttachment: (ticketId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<Attachment>('POST', `/author/tickets/${encodeURIComponent(ticketId)}/attachments`, { form })
  },

  // ---- admin ----
  adminListTickets: (filters: QueueFilters, page: number, size: number, signal?: AbortSignal) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    Object.entries(filters).forEach(([key, value]) => {
      if (value) query.set(key, value)
    })
    return request<Page<AdminTicketSummary>>('GET', `/admin/tickets?${query}`, signal ? { signal } : {})
  },
  adminListAdmins: (signal?: AbortSignal) => request<AdminInfo[]>('GET', '/admin/admins', signal ? { signal } : {}),
  adminStats: (signal?: AbortSignal) => request<QueueStats>('GET', '/admin/tickets/summary', signal ? { signal } : {}),
  adminGetTicket: (id: string, signal?: AbortSignal) =>
    request<AdminTicketDetail>('GET', `/admin/tickets/${encodeURIComponent(id)}`, signal ? { signal } : {}),
  adminUpdateTicket: (id: string, patch: AdminTicketPatch) =>
    request<AdminTicketDetail>('PATCH', `/admin/tickets/${encodeURIComponent(id)}`, { body: patch }),
  adminAddNote: (id: string, body: string) =>
    request<AdminTicketDetail>('POST', `/admin/tickets/${encodeURIComponent(id)}/notes`, { body: { body } }),
  adminReply: (id: string, body: string, status?: string) =>
    request<AdminTicketDetail>('POST', `/admin/tickets/${encodeURIComponent(id)}/replies`, {
      body: status ? { body, status } : { body },
    }),
  /** Returns a suggested reply for the admin to edit. Nothing is saved or sent. */
  adminDraft: (id: string) =>
    request<{ draft: string }>('POST', `/admin/tickets/${encodeURIComponent(id)}/ai-draft`),
}
