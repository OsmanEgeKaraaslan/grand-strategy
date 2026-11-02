import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { DiplomacyOverview } from '../api/types'
import { fakeApi, renderPage, showPanel } from '../test/render'
import { battalionFixture, battleFixture, economyFixture, gameStateFixture, mapFixture, militaryFixture } from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

/**
 * Varn (you) has two ready infantry battalions in Elmsford; Elmsford borders Varnhold (own) and,
 * across a river, Sablewater, where an Ostran battalion stands (at war: older games have no peace).
 */
function renderGame(overrides = {}, state = gameStateFixture()) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(state),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture({ treasuryMg: 42500, projectedNetMg: -1500 })),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    ...overrides,
  })
  renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
  return api
}

function renderGameWithContainer() {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(gameStateFixture()),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

async function map() {
  const svg = await screen.findByRole('group', { name: /^Map of/ })
  await within(svg).findByRole('button', { name: /^Select your army in Elmsford/ })
  return svg
}

describe('Full-viewport campaign screen', () => {
  it('lays the interface over a full-screen map: top bar, toolbar, map, no window open at first', async () => {
    const { container } = renderGameWithContainer()
    await map()
    expect(container.querySelector('.game-shell')).not.toBeNull()
    // The old dashboard frame (page header, centred column) is gone from the game screen.
    expect(container.querySelector('.shell__main')).toBeNull()
    expect(screen.getByRole('main', { name: 'Province map' })).toBeInTheDocument()
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Game panels' })).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Management window' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /^(Army|Forces) in/ })).not.toBeInTheDocument()
  })

  it('keeps the nation, round, turn, resources and End turn in the top bar', async () => {
    renderGame()
    const top = await screen.findByRole('banner')
    expect(within(top).getByText('Kingdom of Varn')).toBeInTheDocument()
    expect(within(top).getByText('Round 1')).toBeInTheDocument()
    expect(within(top).getByText('Your turn')).toBeInTheDocument()
    expect(within(top).getByRole('button', { name: 'End turn' })).toBeInTheDocument()
    expect(within(top).getByRole('link', { name: 'Back to dashboard' })).toHaveAttribute('href', '/')
    const bar = within(top).getByRole('region', { name: 'Resources' })
    expect(await within(bar).findByText('42.5')).toBeInTheDocument()
    expect(within(bar).getByText('−1.5')).toHaveClass('negative')
    expect(within(bar).getByText('20,000')).toBeInTheDocument() // people in Varnhold and Elmsford
    expect(await within(bar).findByText(/2 bn · 1,650 men/)).toBeInTheDocument()
  })

  it('opens one management window at a time from the toolbar and closes it again', async () => {
    renderGame()
    const toolbar = await screen.findByRole('navigation', { name: 'Game panels' })

    await showPanel('economy')
    const window = screen.getByRole('complementary', { name: 'Management window' })
    expect(within(toolbar).getByRole('button', { name: 'Show economy' })).toHaveAttribute('aria-pressed', 'true')
    expect(await within(window).findByRole('region', { name: 'Treasury' })).toBeInTheDocument()
    expect(within(window).getByRole('region', { name: 'Your provinces' })).toBeInTheDocument()

    await showPanel('players')
    expect(within(window).getByRole('region', { name: 'Players' })).toBeInTheDocument()
    expect(within(window).queryByRole('region', { name: 'Treasury' })).not.toBeInTheDocument()
    expect(within(toolbar).getByRole('button', { name: 'Show economy' })).toHaveAttribute('aria-pressed', 'false')

    // Clicking the active icon again closes the window; so does its close button.
    await showPanel('players')
    expect(screen.queryByRole('complementary', { name: 'Management window' })).not.toBeInTheDocument()
    await showPanel('nation')
    await userEvent.click(screen.getByRole('button', { name: 'Close window' }))
    expect(screen.queryByRole('complementary', { name: 'Management window' })).not.toBeInTheDocument()
  })

  it('keeps the map usable while a window is open: selecting a province opens its window beside it', async () => {
    renderGame()
    await showPanel('economy')
    const svg = await map()
    await userEvent.click(within(svg).getByRole('button', { name: /^Varnhold,/ }))
    expect(screen.getByRole('region', { name: 'Varnhold' })).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: 'Management window' })).toBeInTheDocument()
  })

  it('switches the province window to another province clicked on the map, and closes with Escape', async () => {
    renderGame()
    const svg = await map()
    await userEvent.click(within(svg).getByRole('button', { name: /^Varnhold,/ }))
    expect(screen.getByRole('region', { name: 'Varnhold' })).toBeInTheDocument()

    await userEvent.click(within(svg).getByRole('button', { name: /^Sablewater,/ }))
    expect(screen.getByRole('region', { name: 'Sablewater' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Varnhold' })).not.toBeInTheDocument()

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('region', { name: 'Sablewater' })).not.toBeInTheDocument()
  })

  it('opens a management window from the economy list onto the map selection', async () => {
    renderGame()
    await showPanel('economy')
    const provincesList = await screen.findByRole('region', { name: 'Your provinces' })
    await userEvent.click(within(provincesList).getByRole('button', { name: 'Elmsford' }))
    expect(screen.getByRole('region', { name: 'Elmsford' })).toBeInTheDocument()
  })

  it('lists armies in the military window and opens one on the map', async () => {
    renderGame()
    await showPanel('military')
    const armies = await screen.findByRole('region', { name: 'Armies' })
    expect(within(armies).getByText(/2 battalions · 1,650 men · 2 ready/)).toBeInTheDocument()
    await userEvent.click(within(armies).getByRole('button', { name: 'Elmsford' }))
    expect(screen.getByRole('region', { name: 'Army in Elmsford' })).toBeInTheDocument()
  })

  it('offers only the 2D map to players (the 3D view is not ready)', async () => {
    renderGame()
    await map()
    expect(screen.queryByRole('button', { name: '3D' })).not.toBeInTheDocument()
    // Map modes are icon tabs with names and tooltips.
    const political = screen.getByRole('button', { name: 'Political' })
    expect(political).toHaveAttribute('aria-pressed', 'true')
    expect(political).toHaveAttribute('title', expect.stringContaining('owner'))
  })

  it('flags diplomatic business waiting for the player on the toolbar', async () => {
    const diplomacy = {
      gameId: 'g1',
      available: true,
      rulesetVersion: 'v7',
      round: 1,
      yourTurn: true,
      yourNationId: 'nation-varn',
      rules: null,
      nations: [],
      wars: [],
      alliances: [],
      proposals: [],
      treaties: [],
      tributes: [],
      events: [],
      gifts: [],
      calls: [{ id: 'c1', warId: 'w1', callerNationId: 'x', allyNationId: 'nation-varn', status: 'PENDING', createdRound: 1, answeredRound: null, canAnswer: true }],
      conferences: [],
    } as unknown as DiplomacyOverview
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacy) })
    const toolbar = await screen.findByRole('navigation', { name: 'Game panels' })
    expect(await within(toolbar).findByRole('button', { name: 'Show diplomacy (1 waiting)' })).toBeInTheDocument()
  })
})

describe('Map orders (UI-A)', () => {
  it('selects an army from its marker and highlights where it may go', async () => {
    renderGame()
    const svg = await map()
    await userEvent.click(within(svg).getByRole('button', { name: 'Select your army in Elmsford (2 battalions)' }))

    // The army window (not the province window) opens, showing the same selection the map uses.
    const panel = screen.getByRole('region', { name: 'Army in Elmsford' })
    expect(screen.queryByRole('region', { name: 'Elmsford' })).not.toBeInTheDocument()
    expect(within(panel).getByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' })).toBeChecked()
    expect(within(panel).getByRole('checkbox', { name: 'Select Infantry, 650 of 1,000 men' })).toBeChecked()
    expect(within(svg).getByRole('button', { name: /^Varnhold.*move target/ })).toBeInTheDocument()
    expect(within(svg).getByRole('button', { name: /^Sablewater.*attack target/ })).toBeInTheDocument()
    expect(screen.getByText(/2 battalions selected in Elmsford/)).toBeInTheDocument()
  })

  it('moves the selected army with a right-click on a highlighted province', async () => {
    const move = vi.fn().mockResolvedValue({
      outcome: 'MOVED',
      toProvinceKey: 'varnhold',
      battalions: [battalionFixture({ id: 'b1', provinceKey: 'varnhold' }), battalionFixture({ id: 'b2', provinceKey: 'varnhold' })],
      battle: null,
    })
    renderGame({ move })
    const svg = await map()
    await userEvent.click(within(svg).getByRole('button', { name: /^Select your army in Elmsford/ }))
    fireEvent.contextMenu(within(svg).getByRole('button', { name: /^Varnhold/ }))

    await waitFor(() => expect(move).toHaveBeenCalledWith('g1', ['b1', 'b2'], 'varnhold', expect.stringMatching(/^[0-9a-f-]{36}$/)))
    expect(await screen.findByText('Moved 2 battalions to Varnhold.')).toBeInTheDocument()
    // The selection is cleared after the order.
    expect(screen.queryByText(/battalions selected/)).not.toBeInTheDocument()
  })

  it('asks before attacking and only attacks after confirmation', async () => {
    const move = vi.fn().mockResolvedValue({
      outcome: 'ATTACK_WON',
      toProvinceKey: 'sablewater',
      battalions: [battalionFixture({ provinceKey: 'sablewater', eligibility: 'ALREADY_MOVED' })],
      battle: battleFixture(),
    })
    renderGame({ move })
    const svg = await map()
    await userEvent.click(within(svg).getByRole('button', { name: /^Select your army in Elmsford/ }))
    fireEvent.contextMenu(within(svg).getByRole('button', { name: /^Sablewater/ }))

    const dialog = await screen.findByRole('alertdialog', { name: 'Confirm attack' })
    expect(move).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(move).not.toHaveBeenCalled()

    fireEvent.contextMenu(within(svg).getByRole('button', { name: /^Sablewater/ }))
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Attack Sablewater' }))
    await waitFor(() => expect(move).toHaveBeenCalledWith('g1', ['b1', 'b2'], 'sablewater', expect.any(String)))
    expect(await screen.findByRole('region', { name: 'Battle of Sablewater' })).toBeInTheDocument()
  })

  it('gives no order for an illegal destination, on another player\'s turn, or after Escape', async () => {
    const move = vi.fn()
    renderGame({ move })
    const svg = await map()
    await userEvent.click(within(svg).getByRole('button', { name: /^Select your army in Elmsford/ }))
    // Elmsford itself is not a destination.
    fireEvent.contextMenu(within(svg).getByRole('button', { name: /^Elmsford/ }))
    expect(await screen.findByText('Elmsford is not a legal destination for the selected battalions.')).toBeInTheDocument()

    // Escape steps back one layer at a time: first the selection, then the window.
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByText(/battalions selected/)).not.toBeInTheDocument()
    expect(within(svg).queryByRole('button', { name: /move target/ })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Army in Elmsford' })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('region', { name: 'Army in Elmsford' })).not.toBeInTheDocument()
    expect(move).not.toHaveBeenCalled()
  })

  it('shows no destinations and explains why when it is not the player\'s turn', async () => {
    const move = vi.fn()
    renderGame({ move }, gameStateFixture({ activeSeat: 1, yourTurn: false }))
    const svg = await map()
    await userEvent.click(within(svg).getByRole('button', { name: /^Select your army in Elmsford/ }))
    expect(within(svg).queryByRole('button', { name: /move target|attack target/ })).not.toBeInTheDocument()
    expect(await screen.findByText(/Orders can be given on your turn/)).toBeInTheDocument()
    expect(move).not.toHaveBeenCalled()
  })

  it('orders with the keyboard: M on a focused highlighted province', async () => {
    const move = vi.fn().mockResolvedValue({ outcome: 'MOVED', toProvinceKey: 'varnhold', battalions: [], battle: null })
    renderGame({ move })
    const svg = await map()
    within(svg).getByRole('button', { name: /^Select your army in Elmsford/ }).focus()
    await userEvent.keyboard('{Enter}')
    within(svg).getByRole('button', { name: /^Varnhold/ }).focus()
    await userEvent.keyboard('m')
    await waitFor(() => expect(move).toHaveBeenCalledWith('g1', ['b1', 'b2'], 'varnhold', expect.any(String)))
  })
})
