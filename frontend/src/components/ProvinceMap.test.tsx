import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { OSTRA, VARN, gameStateFixture, mapFixture } from '../test/fixtures'
import type { Perspective } from '../lib/diplomacy'
import type { Map3DProps } from './Map3D'
import { ProvinceMap, type DiplomaticView, type NationStyle, type TargetKind, type UnitCounts } from './ProvinceMap'

const webgl = vi.hoisted(() => ({ available: false }))
vi.mock('../lib/mapGeometry', async (original) => ({
  ...(await original<typeof import('../lib/mapGeometry')>()),
  webglAvailable: () => webgl.available,
}))
// jsdom has no WebGL: the 3D view is replaced by a stub that exposes what it receives.
vi.mock('./Map3D', () => ({
  default: (props: Map3DProps) => (
    <div data-testid="map-3d" data-fill-varnhold={props.fills.get('varnhold')} data-view-w={props.view.w}>
      <button type="button" onClick={() => props.onSelect('sablewater')}>
        3D Sablewater
      </button>
      <button type="button" onClick={() => props.onCommand?.('sablewater')}>
        3D order Sablewater
      </button>
      <button type="button" onClick={() => props.onZoom(1.5)}>
        3D zoom
      </button>
    </div>
  ),
}))

const nations = new Map<string, NationStyle>([
  [VARN, { name: 'Kingdom of Varn', color: '#3f6fb5' }],
  [OSTRA, { name: 'Ostran Confederacy', color: '#b5503a' }],
])
const provinces = new Map(gameStateFixture().provinces.map((p) => [p.key, p]))

function renderMap(props: Partial<Parameters<typeof ProvinceMap>[0]> = {}) {
  render(
    <ProvinceMap
      map={mapFixture}
      provinces={provinces}
      nations={nations}
      selectedKey={null}
      onSelect={vi.fn()}
      diplomacy={diplomaticView()}
      income={new Map([['varnhold', 12000], ['elmsford', 3000]])}
      {...props}
    />,
  )
  return screen.getByRole('group', { name: /^Map of/ })
}

/** Varn sees Ostra as an enemy; another reference can be passed in. */
function diplomaticView(overrides: Partial<DiplomaticView> = {}): DiplomaticView {
  return {
    referenceId: VARN,
    yourNationId: VARN,
    standing: (id: string): Perspective => (id === VARN ? 'SELF' : 'WAR'),
    onReference: vi.fn(),
    ...overrides,
  }
}

function shape(svg: HTMLElement, name: RegExp) {
  return within(svg).getByRole('button', { name })
}

describe('Map modes (UI-B)', () => {
  it('switches between political, diplomatic, terrain and economy colouring with a legend', async () => {
    const svg = renderMap()
    const modes = screen.getByRole('group', { name: 'Map mode' })
    expect(svg).toHaveAttribute('data-mode', 'political')
    expect(within(modes).getByRole('button', { name: 'Political' })).toHaveAttribute('aria-pressed', 'true')
    expect(shape(svg, /^Varnhold/)).toHaveAttribute('fill', '#3f6fb5')

    await userEvent.click(within(modes).getByRole('button', { name: 'Diplomatic' }))
    expect(svg).toHaveAttribute('data-mode', 'diplomatic')
    expect(shape(svg, /^Varnhold/)).toHaveAttribute('data-standing', 'self')
    expect(shape(svg, /^Sablewater/)).toHaveAttribute('data-standing', 'war')
    expect(within(screen.getByRole('list', { name: 'Map legend' })).getByText('At war with you')).toBeInTheDocument()

    await userEvent.click(within(modes).getByRole('button', { name: 'Terrain' }))
    expect(shape(svg, /^Varnhold/).getAttribute('fill')).not.toBe(shape(svg, /^Sablewater.*mountains/).getAttribute('fill'))

    await userEvent.click(within(modes).getByRole('button', { name: 'Economy' }))
    expect(shape(svg, /^Varnhold/)).toHaveAttribute('data-income', '4')
    expect(shape(svg, /^Elmsford/)).toHaveAttribute('data-income', '1')
    expect(shape(svg, /^Sablewater/)).toHaveAttribute('data-income', '0')
  })

  it('offers no diplomatic mode without diplomacy', () => {
    renderMap({ diplomacy: undefined })
    expect(within(screen.getByRole('group', { name: 'Map mode' })).queryByRole('button', { name: 'Diplomatic' })).not.toBeInTheDocument()
  })
})

describe('Army markers and movement preview (UI-B)', () => {
  it('shows own, allied and enemy stacks with their soldiers', () => {
    const units = new Map<string, UnitCounts>([
      ['elmsford', { friendly: 2, friendlyMen: 1700, hostile: 0, allied: 1, alliedMen: 400 }],
      ['sablewater', { friendly: 0, hostile: 1, hostileMen: 1000 }],
    ])
    const svg = renderMap({ units, onSelectArmy: vi.fn() })
    expect(svg.querySelector('[data-units="friendly"]')).toHaveTextContent('21.7k')
    expect(svg.querySelector('[data-units="allied"]')).toHaveTextContent('1400')
    expect(svg.querySelector('[data-units="hostile"]')).toHaveTextContent('11.0k')
    expect(shape(svg, /^Elmsford.*1 allied battalions/)).toBeInTheDocument()
  })

  it('draws no arrows without a selected army', () => {
    const svg = renderMap({ targets: new Map([['varnhold', 'MOVE' as TargetKind]]), origin: null })
    expect(svg.querySelectorAll('[data-arrow]')).toHaveLength(0)
  })

  it('draws an arrow from the selected army to every legal destination', () => {
    const targets = new Map<string, TargetKind>([
      ['varnhold', 'MOVE'],
      ['sablewater', 'ATTACK'],
    ])
    const svg = renderMap({ targets, origin: 'elmsford' })
    expect(svg.querySelectorAll('[data-arrow="move"]')).toHaveLength(1)
    expect(svg.querySelectorAll('[data-arrow="attack"]')).toHaveLength(1)
  })
})

describe('Tooltip and camera (UI-B)', () => {
  it('shows province details on hover and hides them when the pointer leaves the map', () => {
    const svg = renderMap({
      units: new Map([['sablewater', { friendly: 0, hostile: 1, hostileMen: 1000 }]]),
      targets: new Map([['sablewater', 'ATTACK' as TargetKind]]),
      fortresses: new Map([['sablewater', { level: 2, damaged: false }]]),
    })
    fireEvent.pointerEnter(shape(svg, /^Sablewater/), { clientX: 100, clientY: 80 })
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip).toHaveTextContent('Sablewater')
    expect(tooltip).toHaveTextContent('Ostran Confederacy')
    expect(tooltip).toHaveTextContent('Enemy battalions: 1 (1,000 men)')
    expect(tooltip).toHaveTextContent('Fortress level 2')
    expect(tooltip).toHaveTextContent('Right-click to attack here')
    fireEvent.pointerLeave(svg)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('pans with the arrow keys, zooms with + / − and double-click, and resets with 0', async () => {
    const svg = renderMap()
    expect(svg.getAttribute('viewBox')).toBe('0 0 1000 640')
    shape(svg, /^Varnhold/).focus()
    await userEvent.keyboard('+')
    const zoomed = svg.getAttribute('viewBox')!.split(' ').map(Number)
    expect(zoomed[2]).toBeLessThan(1000)
    await userEvent.keyboard('{ArrowRight}')
    const panned = svg.getAttribute('viewBox')!.split(' ').map(Number)
    expect(panned[0]).toBeGreaterThan(zoomed[0])
    await userEvent.keyboard('0')
    expect(svg.getAttribute('viewBox')).toBe('0 0 1000 640')
    fireEvent.doubleClick(svg, { clientX: 0, clientY: 0 })
    expect(svg.getAttribute('viewBox')).not.toBe('0 0 1000 640')
  })
})

describe('Optional 3D view (UI-B)', () => {
  it('is disabled with an explanation when the browser has no WebGL', () => {
    webgl.available = false
    renderMap()
    const toggle = screen.getByRole('button', { name: '3D' })
    expect(toggle).toBeDisabled()
    expect(toggle).toHaveAttribute('title', expect.stringMatching(/no WebGL/))
  })

  it('swaps the SVG for the lazily loaded 3D view with the same colours, camera and orders', async () => {
    webgl.available = true
    const onSelect = vi.fn()
    const onCommand = vi.fn()
    renderMap({ onSelect, onCommand })
    await userEvent.click(screen.getByRole('button', { name: '3D' }))

    const view = await screen.findByTestId('map-3d')
    expect(screen.queryByRole('group', { name: /^Map of/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '3D' })).toHaveAttribute('aria-pressed', 'true')
    expect(view).toHaveAttribute('data-fill-varnhold', '#3f6fb5')

    await userEvent.click(screen.getByRole('button', { name: '3D Sablewater' }))
    expect(onSelect).toHaveBeenCalledWith('sablewater')
    await userEvent.click(screen.getByRole('button', { name: '3D order Sablewater' }))
    expect(onCommand).toHaveBeenCalledWith('sablewater')

    // The camera follows the shared view, so the 2D zoom controls drive it too.
    await userEvent.click(screen.getByRole('button', { name: '3D zoom' }))
    expect(Number(view.getAttribute('data-view-w'))).toBeLessThan(1000)

    await userEvent.click(screen.getByRole('button', { name: '3D' }))
    expect(screen.getByRole('group', { name: /^Map of/ })).toBeInTheDocument()
    webgl.available = false
  })
})

describe('Diplomatic perspective', () => {
  async function diplomatic() {
    await userEvent.click(within(screen.getByRole('group', { name: 'Map mode' })).getByRole('button', { name: 'Diplomatic' }))
  }

  it('makes the owner of a clicked province the reference nation, and still selects the province', async () => {
    const onSelect = vi.fn()
    const onReference = vi.fn()
    const svg = renderMap({ onSelect, diplomacy: diplomaticView({ onReference }) })
    await diplomatic()
    await userEvent.click(shape(svg, /^Sablewater/))
    expect(onSelect).toHaveBeenCalledWith('sablewater')
    expect(onReference).toHaveBeenCalledWith(OSTRA)
  })

  it('leaves the reference alone outside the diplomatic mode and when an army marker is clicked', async () => {
    const onReference = vi.fn()
    const onSelectArmy = vi.fn()
    const units = new Map<string, UnitCounts>([['sablewater', { friendly: 1, hostile: 0 }]])
    const svg = renderMap({ units, onSelectArmy, diplomacy: diplomaticView({ onReference }) })
    await userEvent.click(shape(svg, /^Sablewater/))
    expect(onReference).not.toHaveBeenCalled()

    await diplomatic()
    await userEvent.click(shape(svg, /^Select your army in Sablewater/))
    expect(onSelectArmy).toHaveBeenCalledWith('sablewater')
    expect(onReference).not.toHaveBeenCalled()
  })

  it('takes the legal owner of an occupied province, not the occupier', async () => {
    const onReference = vi.fn()
    const occupied = new Map(provinces)
    occupied.set('elmsford', { ...provinces.get('elmsford')!, controllerNationId: OSTRA })
    const svg = renderMap({ provinces: occupied, diplomacy: diplomaticView({ referenceId: OSTRA, onReference }) })
    await diplomatic()
    await userEvent.click(shape(svg, /^Elmsford/))
    expect(onReference).toHaveBeenCalledWith(VARN)
  })

  it('names the reference nation, colours by its relations and resets to the viewer', async () => {
    const onReference = vi.fn()
    const standing = (id: string): Perspective => (id === OSTRA ? 'SELF' : 'DEFENSIVE_ALLY')
    const svg = renderMap({ diplomacy: diplomaticView({ referenceId: OSTRA, standing, onReference }) })
    expect(screen.queryByRole('status', { name: 'Diplomatic perspective' })).not.toBeInTheDocument()
    await diplomatic()

    const bar = screen.getByRole('status', { name: 'Diplomatic perspective' })
    expect(bar).toHaveTextContent('Relations of Ostran Confederacy')
    expect(shape(svg, /^Sablewater/)).toHaveAttribute('data-standing', 'self')
    expect(shape(svg, /^Varnhold/)).toHaveAttribute('data-standing', 'defensive-ally')
    const legend = screen.getByRole('list', { name: 'Map legend' })
    expect(within(legend).getByText('At war with Ostran Confederacy')).toBeInTheDocument()
    expect(within(legend).getByText('Defensive allies')).toBeInTheDocument()
    expect(within(legend).getByText('Same side of a war, not allied')).toBeInTheDocument()

    await userEvent.click(within(bar).getByRole('button', { name: 'Reset to my nation' }))
    expect(onReference).toHaveBeenCalledWith(VARN)
  })

  it('offers no reset while the map shows the viewer’s own relations', async () => {
    renderMap()
    await diplomatic()
    const bar = screen.getByRole('status', { name: 'Diplomatic perspective' })
    expect(bar).toHaveTextContent('Relations of your nation')
    expect(within(bar).queryByRole('button', { name: 'Reset to my nation' })).not.toBeInTheDocument()
  })

  it('picks the reference from the 3D view too', async () => {
    webgl.available = true
    const onReference = vi.fn()
    renderMap({ diplomacy: diplomaticView({ onReference }) })
    await diplomatic()
    await userEvent.click(screen.getByRole('button', { name: '3D' }))
    await userEvent.click(await screen.findByRole('button', { name: '3D Sablewater' }))
    expect(onReference).toHaveBeenCalledWith(OSTRA)
    webgl.available = false
  })
})
