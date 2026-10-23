import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, setCsrfToken, setUnauthorizedHandler, toApiError } from './api.ts'

function mockFetch(status: number, body?: unknown) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
  setUnauthorizedHandler(null)
  setCsrfToken(null)
})

describe('api client', () => {
  it('turns the common error shape into an ApiError with field details and request id', async () => {
    mockFetch(400, {
      error: {
        code: 'VALIDATION_ERROR',
        message: 'Please correct the highlighted fields.',
        details: [{ field: 'subject', message: 'Subject is required.' }],
      },
      requestId: 'req-1',
    })
    const error = await api.createTicket({ bookId: null, subject: '', description: '' }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.code).toBe('VALIDATION_ERROR')
    expect(error.details).toEqual([{ field: 'subject', message: 'Subject is required.' }])
    expect(error.requestId).toBe('req-1')
  })

  it('reports an unreachable server with a friendly message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const error = await api.listBooks().catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('NETWORK_ERROR')
    expect(error.message).toMatch(/reach the server/i)
  })

  it('falls back to a generic message when the error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502 })))
    const error = await api.listBooks().catch((e) => e)
    expect(error.status).toBe(502)
    expect(error.message).toMatch(/something went wrong/i)
  })

  it('returns undefined for 204 responses', async () => {
    mockFetch(204)
    await expect(api.logout()).resolves.toBeUndefined()
  })

  it('does not trigger sign-out for a failed login (401 on /auth/login)', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    mockFetch(401, { error: { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password.' } })
    const error = await api.login('a@b.c', 'wrong').catch((e) => e)
    expect(error.code).toBe('INVALID_CREDENTIALS')
    expect(handler).not.toHaveBeenCalled()
  })

  it('sends the cookie, and the CSRF token on writes only', async () => {
    setCsrfToken('csrf-1')
    const read = mockFetch(200, [])
    await api.listBooks()
    const [, getInit] = read.mock.calls[0] as [string, RequestInit]
    expect(getInit.credentials).toBe('include')
    expect((getInit.headers as Record<string, string>)['X-CSRF-Token']).toBeUndefined()

    const write = mockFetch(204)
    await api.logout()
    const [, postInit] = write.mock.calls[0] as [string, RequestInit]
    expect(postInit.credentials).toBe('include')
    expect((postInit.headers as Record<string, string>)['X-CSRF-Token']).toBe('csrf-1')
    expect((postInit.headers as Record<string, string>).Authorization).toBeUndefined()
  })

  it('signs the user out when a signed-in session is rejected, but not before sign-in', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    mockFetch(401, { error: { code: 'UNAUTHENTICATED', message: 'Please sign in to continue.' } })
    await api.listBooks().catch(() => undefined)
    expect(handler).not.toHaveBeenCalled()

    setCsrfToken('csrf-1')
    mockFetch(401, { error: { code: 'UNAUTHENTICATED', message: 'Please sign in to continue.' } })
    await api.listBooks().catch(() => undefined)
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('loads the server config without a token', async () => {
    const fetchMock = mockFetch(200, { brandName: 'Portal', locale: 'en-IN' })
    const config = await api.getConfig()
    expect(config.brandName).toBe('Portal')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toMatch(/\/api\/v1\/config$/)
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined()
  })

  it('wraps unknown errors', () => {
    expect(toApiError(new Error('boom')).code).toBe('UNKNOWN')
    const known = new ApiError(404, 'NOT_FOUND', 'Missing')
    expect(toApiError(known)).toBe(known)
  })
})
