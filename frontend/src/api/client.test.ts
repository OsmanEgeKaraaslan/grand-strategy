import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient, describeError } from './client'

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('createApiClient', () => {
  it('sends the Supabase access token as a bearer token to the versioned API', async () => {
    const fetch = vi.fn().mockResolvedValue(jsonResponse(200, { id: 'p1', displayName: 'Aldric' }))
    const api = createApiClient({ baseUrl: 'http://api.test', accessToken: async () => 'token-123', fetch })

    await expect(api.getMe()).resolves.toMatchObject({ displayName: 'Aldric' })

    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('http://api.test/api/v1/me')
    expect(init.headers.Authorization).toBe('Bearer token-123')
  })

  it('posts JSON bodies', async () => {
    const fetch = vi.fn().mockResolvedValue(jsonResponse(200, { id: 'g1' }))
    const api = createApiClient({ baseUrl: 'http://api.test', accessToken: async () => 't', fetch })

    await api.joinGame('ABCD-EFGH-JKLM')

    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('http://api.test/api/v1/games/join')
    expect(init.method).toBe('POST')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(init.body)).toEqual({ inviteCode: 'ABCD-EFGH-JKLM' })
  })

  it('turns problem responses into ApiErrors with the stable code', async () => {
    const fetch = vi.fn().mockResolvedValue(
      jsonResponse(409, { status: 409, code: 'GAME_FULL', detail: 'This game has no free player slot.', requestId: 'r1' }),
    )
    const api = createApiClient({ baseUrl: 'http://api.test', accessToken: async () => 't', fetch })

    const error = await api.joinGame('X').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, code: 'GAME_FULL', requestId: 'r1' })
    expect(describeError(error)).toBe('This game is already full.')
  })

  it('reports rejected tokens so the session can be ended', async () => {
    const onUnauthorized = vi.fn()
    const fetch = vi.fn().mockResolvedValue(jsonResponse(401, { status: 401, code: 'UNAUTHENTICATED' }))
    const api = createApiClient({ baseUrl: 'http://api.test', accessToken: async () => 'expired', onUnauthorized, fetch })

    await expect(api.getMe()).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('ends the session of a deleted account like a rejected token', async () => {
    const onUnauthorized = vi.fn()
    const fetch = vi.fn().mockResolvedValue(jsonResponse(403, { status: 403, code: 'ACCOUNT_DELETED' }))
    const api = createApiClient({ baseUrl: 'http://api.test', accessToken: async () => 'still-valid', onUnauthorized, fetch })

    await expect(api.getMe()).rejects.toMatchObject({ code: 'ACCOUNT_DELETED' })
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('explains rate limiting and other Phase 15 refusals in player-facing words', async () => {
    const fetch = vi.fn().mockResolvedValue(jsonResponse(429, { status: 429, code: 'RATE_LIMITED', detail: 'Too many requests.' }))
    const api = createApiClient({ baseUrl: 'http://api.test', accessToken: async () => 't', fetch })

    const error = await api.searchPlayers('abc').catch((e: unknown) => e)
    expect(describeError(error)).toBe('You are doing that too often. Please wait a moment and try again.')
    expect(describeError(new ApiError({ status: 403, code: 'TERMS_NOT_ACCEPTED' }))).toMatch(/accept the current Terms/)
    expect(describeError(new ApiError({ status: 409, code: 'ACCOUNT_DELETION_BLOCKED', detail: 'Seated in 1 unfinished game.' })))
      .toBe('Seated in 1 unfinished game.')
    expect(describeError(new ApiError({ status: 503, code: 'GAME_CAPACITY_REACHED' }))).toMatch(/try again later/)
  })

  it('omits the Authorization header when signed out', async () => {
    const fetch = vi.fn().mockResolvedValue(jsonResponse(401, { status: 401, code: 'UNAUTHENTICATED' }))
    const api = createApiClient({ baseUrl: 'http://api.test', accessToken: async () => null, fetch })

    await api.getMe().catch(() => undefined)

    expect(fetch.mock.calls[0][1].headers.Authorization).toBeUndefined()
  })

  it('maps network failures to a readable error', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const api = createApiClient({ baseUrl: 'http://api.test', accessToken: async () => 't', fetch })

    await expect(api.listGames()).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })
})
