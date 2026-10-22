/** A closed outline in SVG map coordinates. */
export type Polygon = [number, number][]

/**
 * Parses the province outlines of the map templates into polygons. The templates use absolute
 * move/line/close commands (M, L, H, V, Z); anything else is ignored rather than guessed.
 */
export function parsePolygons(d: string): Polygon[] {
  const polygons: Polygon[] = []
  let current: Polygon = []
  let x = 0
  let y = 0
  const tokens = d.match(/[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?/g) ?? []
  let command = ''
  for (let i = 0; i < tokens.length; ) {
    const token = tokens[i]
    if (/[A-Za-z]/.test(token)) {
      command = token
      i++
      if (command === 'Z' || command === 'z') {
        if (current.length > 2) polygons.push(current)
        current = []
      }
      continue
    }
    const next = () => Number(tokens[i++])
    switch (command) {
      case 'M':
        if (current.length > 2) polygons.push(current)
        x = next()
        y = next()
        current = [[x, y]]
        command = 'L' // further pairs after M are lines
        break
      case 'L':
        x = next()
        y = next()
        current.push([x, y])
        break
      case 'H':
        x = next()
        current.push([x, y])
        break
      case 'V':
        y = next()
        current.push([x, y])
        break
      default:
        i++ // unsupported command: skip its numbers
    }
  }
  if (current.length > 2) polygons.push(current)
  return polygons
}

/** Whether the browser can render the optional 3D map (WebGL). */
export function webglAvailable(): boolean {
  try {
    const canvas = document.createElement('canvas')
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'))
  }
  catch {
    return false
  }
}
