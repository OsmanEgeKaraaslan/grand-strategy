import { describe, expect, it } from 'vitest'
import { parsePolygons } from './mapGeometry'

describe('parsePolygons', () => {
  it('reads the absolute move/line/close outlines of the map templates', () => {
    expect(parsePolygons('M0 0 L100 0 L100 100 L0 100 Z')).toEqual([
      [
        [0, 0],
        [100, 0],
        [100, 100],
        [0, 100],
      ],
    ])
  })

  it('reads several outlines, commas, decimals, implicit lines and H / V', () => {
    const polygons = parsePolygons('M10,10 20.5,10 20.5,30 Z M50 50 H80 V70 H50 Z')
    expect(polygons).toHaveLength(2)
    expect(polygons[0]).toEqual([
      [10, 10],
      [20.5, 10],
      [20.5, 30],
    ])
    expect(polygons[1]).toEqual([
      [50, 50],
      [80, 50],
      [80, 70],
      [50, 70],
    ])
  })

  it('drops degenerate outlines and ignores unsupported commands', () => {
    expect(parsePolygons('M0 0 L10 0 Z')).toEqual([])
    expect(parsePolygons('')).toEqual([])
  })
})
