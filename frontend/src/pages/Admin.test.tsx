import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { AdminGame, AdminPlayer, PlayerProfile } from '../api/types'
import { TermsGate } from '../auth/TermsGate'
import { fakeApi, renderPage } from '../test/render'
import { AdminPage } from './AdminPage'

function me(admin: boolean): PlayerProfile {
  return {
    id: 'admin-1',
    username: 'warden',
    displayName: 'Warden',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    admin,
  }
}

function player(overrides: Partial<AdminPlayer> = {}): AdminPlayer {
  return {
    id: 'p-rude',
    username: 'rude_one',
    displayName: 'Rude One',
    createdAt: '2026-10-01T10:00:00Z',
    deleted: false,
    bannedAt: null,
    banReason: null,
    admin: false,
    ...overrides,
  }
}

const stuckGame: AdminGame = {
  id: 'g-stuck',
  name: 'Stuck campaign',
  status: 'IN_PROGRESS',
  round: 7,
  computerSeats: 8,
  humans: ['Aldric', 'Rude One'],
  createdAt: '2026-10-01T10:00:00Z',
  lastActionAt: '2026-10-02T10:00:00Z',
}

describe('AdminPage', () => {
  it('is only usable by administrators', async () => {
    const api = fakeApi({ getMe: vi.fn().mockResolvedValue(me(false)) })
    renderPage(<AdminPage />, { api })
    expect(await screen.findByText('This page is only for administrators.')).toBeInTheDocument()
    expect(api.adminPlayers).not.toHaveBeenCalled()
  })

  it('suspends a player with a reason and shows the suspension', async () => {
    const banned = player({ bannedAt: '2026-11-03T12:00:00Z', banReason: 'Offensive messages' })
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(me(true)),
      adminPlayers: vi.fn().mockResolvedValueOnce([player()]).mockResolvedValue([banned]),
      adminGames: vi.fn().mockResolvedValue([]),
      banPlayer: vi.fn().mockResolvedValue(banned),
    })
    renderPage(<AdminPage />, { api })

    const row = await screen.findByRole('listitem', { name: 'Rude One (@rude_one)' })
    await userEvent.click(within(row).getByRole('button', { name: 'Ban…' }))
    const submit = within(row).getByRole('button', { name: 'Suspend Rude One' })
    expect(submit).toBeDisabled()
    await userEvent.type(within(row).getByLabelText('Reason (the player sees it)'), 'Offensive messages')
    await userEvent.click(submit)

    expect(api.banPlayer).toHaveBeenCalledWith('p-rude', 'Offensive messages')
    expect(await within(row).findByText(/Suspended .*: Offensive messages/)).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Unban' })).toBeInTheDocument()
  })

  it('searches players, resets a name and offers no actions on other administrators', async () => {
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(me(true)),
      adminPlayers: vi.fn().mockResolvedValue([player(), player({ id: 'p-admin', username: 'other_admin', displayName: 'Other Admin', admin: true })]),
      adminGames: vi.fn().mockResolvedValue([]),
      resetPlayerName: vi.fn().mockResolvedValue(player({ displayName: 'Commander-7KQ2', username: 'commander_7kq2xm' })),
    })
    renderPage(<AdminPage />, { api })

    await userEvent.type(await screen.findByLabelText('Username, display name or player id'), ' rude ')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(api.adminPlayers).toHaveBeenLastCalledWith('rude')

    const row = await screen.findByRole('listitem', { name: 'Rude One (@rude_one)' })
    await userEvent.click(within(row).getByRole('button', { name: 'Reset name' }))
    expect(api.resetPlayerName).toHaveBeenCalledWith('p-rude')

    const adminRow = screen.getByRole('listitem', { name: 'Other Admin (@other_admin)' })
    expect(within(adminRow).queryByRole('button')).not.toBeInTheDocument()
  })

  it('closes a stuck game with a reason', async () => {
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(me(true)),
      adminPlayers: vi.fn().mockResolvedValue([]),
      adminGames: vi.fn().mockResolvedValueOnce([stuckGame]).mockResolvedValue([]),
      abandonGame: vi.fn().mockResolvedValue({ ...stuckGame, status: 'ABANDONED' }),
    })
    renderPage(<AdminPage />, { api })

    const row = await screen.findByRole('listitem', { name: 'Stuck campaign' })
    expect(within(row).getByText(/Aldric, Rude One · 8 computer nations · round 7/)).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: 'Close game…' }))
    await userEvent.type(within(row).getByLabelText('Reason (kept in the moderation log)'), 'Both players left')
    await userEvent.click(within(row).getByRole('button', { name: 'Close Stuck campaign' }))

    expect(api.abandonGame).toHaveBeenCalledWith('g-stuck', 'Both players left')
    expect(await screen.findByText('No open games.')).toBeInTheDocument()
  })
})

describe('a suspended account', () => {
  it('sees the reason and can only log out', async () => {
    const api = fakeApi({
      getMe: vi.fn().mockRejectedValue(
        new ApiError({ status: 403, code: 'ACCOUNT_SUSPENDED', detail: 'This account has been suspended. Reason: Cheating' }),
      ),
    })
    const { auth } = renderPage(
      <TermsGate>
        <p>The game</p>
      </TermsGate>,
      { api },
    )
    expect(await screen.findByRole('heading', { name: 'Account suspended' })).toBeInTheDocument()
    expect(screen.getByText('This account has been suspended. Reason: Cheating')).toBeInTheDocument()
    expect(screen.queryByText('The game')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Log out' }))
    expect(auth.signOut).toHaveBeenCalled()
  })
})
