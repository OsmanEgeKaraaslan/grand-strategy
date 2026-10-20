// Generates the "Grand Campaign" map template (Phase 10) as a Flyway seed migration.
//
//   node tools/mapgen/grand-campaign.mjs            -> writes the migration and prints a report
//   node tools/mapgen/grand-campaign.mjs --check    -> prints the report only
//
// Everything here is original data: a hand-drawn, Europe-inspired continent outline and hand-placed
// province seeds with fictional names. No external map data is used, so there is nothing to license.
//
// Method (deterministic, no dependencies):
//   1. Rasterize the land outline on a 2-unit grid.
//   2. Assign every land cell to the nearest province seed after a smooth, seeded domain warp
//      (borders become irregular instead of straight Voronoi lines).
//   3. Keep only the connected part of each province that holds its seed; give stray fragments to
//      the neighbouring province they touch most.
//   4. Trace every border once, split it into chains between junctions, simplify and smooth each
//      chain once and reuse it for both sides, so neighbouring provinces share exactly the same
//      border points.
//   5. Two provinces are adjacent when their shared land border is at least MIN_BORDER units long
//      (a touching corner does not count; the sea never creates adjacency).
//
// A seed migration must never change after it has been applied: to change the map, publish a new
// map version (new key/version) instead of re-running this script over an applied migration.

import { writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const WIDTH = 1600
const HEIGHT = 1100
const CELL = 2
const MIN_BORDER = 16
const WARP = 26
const SEED = 1789

// --- continent outline (clockwise) -------------------------------------------------------------

const LAND = [
  [300, 330], [360, 300], [430, 310], [500, 270], [560, 240], [600, 200], [660, 170], [720, 160], [780, 175],
  [840, 150], [920, 140], [1000, 150], [1080, 135], [1160, 120], [1240, 110], [1320, 95], [1400, 90], [1480, 100],
  [1560, 140], [1580, 220], [1585, 330], [1580, 450], [1560, 560], [1520, 640], [1460, 660], [1420, 700],
  [1440, 760], [1470, 820], [1500, 880], [1480, 950], [1430, 1010], [1380, 1050], [1330, 1070], [1290, 1040],
  [1260, 990], [1220, 940], [1190, 880], [1150, 820], [1110, 770], [1070, 730], [1040, 760], [1060, 820],
  [1090, 880], [1120, 950], [1110, 1000], [1080, 1020], [1040, 1050], [1000, 1020], [990, 960], [960, 900],
  [920, 840], [880, 790], [840, 740], [790, 720], [740, 730], [690, 740], [640, 760], [600, 780], [560, 790],
  [540, 840], [520, 900], [490, 960], [450, 1010], [400, 1040], [330, 1050], [260, 1040], [200, 1000],
  [150, 950], [130, 880], [140, 800], [150, 730], [170, 680], [230, 650], [300, 650], [370, 660], [420, 640],
  [440, 580], [420, 520], [400, 460], [360, 420], [310, 380],
]

// --- factions ----------------------------------------------------------------------------------
// tier: POWER (balanced, human-selectable), MAJOR, REGIONAL, MINOR. Balance numbers per tier live
// in the ruleset (economy.json / military.json / siege.json), not here.

const NATIONS = [
  { key: 'castavar', name: 'Kingdom of Castavar', color: '#c0892f', tier: 'POWER', playable: true },
  { key: 'aurelia', name: 'Aurelian Empire', color: '#5b6fb8', tier: 'MAJOR', playable: false },
  { key: 'frisia', name: 'Frisian Free Cities', color: '#4f9d9a', tier: 'MINOR', playable: false },
  { key: 'nordmark', name: 'Margraviate of Nordmark', color: '#7a8a99', tier: 'POWER', playable: true },
  { key: 'helvaria', name: 'Helvarian Cantons', color: '#b24a64', tier: 'MINOR', playable: false },
  { key: 'serenna', name: 'Most Serene Republic of Serenna', color: '#3f8f4f', tier: 'REGIONAL', playable: false },
  { key: 'aestia', name: 'Aestian Duchy', color: '#9a7bc0', tier: 'MINOR', playable: false },
  { key: 'vostrava', name: 'Tsardom of Vostrava', color: '#a8452f', tier: 'POWER', playable: true },
  { key: 'karpathia', name: 'Kingdom of Karpathia', color: '#c4a73a', tier: 'REGIONAL', playable: false },
  { key: 'dravonia', name: 'Dravonian Despotate', color: '#2f6f8f', tier: 'POWER', playable: true },
]

// [key, province name, city name, nation, x, y, terrain, population, capital?]
const PROVINCES = [
  // Castavar — the western peninsula, behind the Sierra Vel passes.
  ['galmar', 'Galmar Coast', 'Port Galmar', 'castavar', 230, 715, 'PLAINS', 6000],
  ['leomir', 'Leomir', 'Leomir', 'castavar', 345, 720, 'PLAINS', 5000],
  ['sierravel', 'Sierra Vel', 'Velhaven', 'castavar', 470, 700, 'MOUNTAINS', 4000],
  ['lusande', 'Lusande', 'Lusande', 'castavar', 215, 880, 'PLAINS', 7000],
  ['meseta', 'Meseta', 'Castavar', 'castavar', 350, 845, 'PLAINS', 12000, true],
  ['valcaro', 'Valcaro', 'Valcaro', 'castavar', 470, 860, 'PLAINS', 8000],
  ['andarra', 'Andarra', 'Andarra', 'castavar', 330, 980, 'PLAINS', 4000],
  // Aurelia — the great western plain, the strongest realm.
  ['armorel', 'Armorel', 'Kerval', 'aurelia', 365, 385, 'PLAINS', 5000],
  ['caudry', 'Caudry', 'Caudry', 'aurelia', 480, 335, 'PLAINS', 6000],
  ['picardel', 'Picardel', 'Amberon', 'aurelia', 570, 300, 'PLAINS', 6000],
  ['ligerais', 'Ligerais', 'Tourelle', 'aurelia', 450, 470, 'PLAINS', 7000],
  ['aurel', 'Aurel Basin', 'Aurel', 'aurelia', 560, 415, 'PLAINS', 14000, true],
  ['champeaux', 'Champeaux', 'Remont', 'aurelia', 670, 375, 'PLAINS', 7000],
  ['aquivelle', 'Aquivelle', 'Bourdelle', 'aurelia', 450, 600, 'PLAINS', 7000],
  ['limosin', 'Limosin', 'Limoges-sur-Vaire', 'aurelia', 565, 540, 'PLAINS', 5000],
  ['bourgogne', 'Bourgonde', 'Dijonne', 'aurelia', 680, 495, 'PLAINS', 7000],
  ['occitane', 'Occitane', 'Tolosa', 'aurelia', 560, 685, 'PLAINS', 6000],
  ['provincia', 'Provincia', 'Massalie', 'aurelia', 690, 670, 'PLAINS', 5000],
  ['rhenanie', 'Rhenanie', 'Strassel', 'aurelia', 755, 425, 'PLAINS', 5000],
  // Frisia — low coastal city-states.
  ['westerdijk', 'Westerdijk', 'Amstelburg', 'frisia', 640, 235, 'PLAINS', 7000, true],
  ['oostergo', 'Oostergo', 'Leeuwen', 'frisia', 715, 205, 'PLAINS', 5000],
  ['brabantia', 'Brabantia', 'Antwerp-Haven', 'frisia', 665, 300, 'PLAINS', 5000],
  ['geldermark', 'Geldermark', 'Arnheim', 'frisia', 760, 270, 'PLAINS', 4000],
  // Nordmark — the northern plain.
  ['holmgard', 'Holmgard', 'Holmgard', 'nordmark', 845, 205, 'PLAINS', 6000],
  ['wendland', 'Wendland', 'Lubeck-Wend', 'nordmark', 935, 190, 'PLAINS', 7000],
  ['pomerel', 'Pomerel', 'Stettin-Pomer', 'nordmark', 1025, 195, 'PLAINS', 5000],
  ['westfal', 'Westfal', 'Munsterau', 'nordmark', 850, 325, 'PLAINS', 4000],
  ['nordheim', 'Nordheim', 'Nordheim', 'nordmark', 950, 300, 'PLAINS', 12000, true],
  ['marchia', 'Marchia', 'Brandenau', 'nordmark', 1040, 320, 'PLAINS', 8000],
  ['harzland', 'Harzland', 'Goslar', 'nordmark', 905, 415, 'MOUNTAINS', 4000],
  // Helvaria — Alpine cantons and the Bohemian basin.
  ['valsaine', 'Valsaine', 'Genfelden', 'helvaria', 790, 560, 'MOUNTAINS', 4000],
  ['bernalp', 'Bernalp', 'Bernegg', 'helvaria', 880, 520, 'MOUNTAINS', 7000, true],
  ['boemark', 'Boemark', 'Prague-Moldau', 'helvaria', 965, 455, 'PLAINS', 6000],
  ['moravel', 'Moravel', 'Brunnau', 'helvaria', 1040, 520, 'PLAINS', 4000],
  ['tirolis', 'Tirolis', 'Innsbrugge', 'helvaria', 900, 615, 'MOUNTAINS', 3000],
  // Serenna — the long southern peninsula.
  ['pedemont', 'Pedemont', 'Taurino', 'serenna', 830, 690, 'MOUNTAINS', 4000],
  ['lombarda', 'Lombarda', 'Milara', 'serenna', 915, 700, 'PLAINS', 10000, true],
  ['venetia', 'Venetia', 'Laguna', 'serenna', 1000, 712, 'PLAINS', 7000],
  ['liguria', 'Ligurena', 'Genova Alta', 'serenna', 930, 810, 'PLAINS', 5000],
  ['emilia', 'Emilia', 'Bononia', 'serenna', 1000, 800, 'PLAINS', 6000],
  ['latiara', 'Latiara', 'Urbs Serena', 'serenna', 995, 885, 'PLAINS', 6000],
  ['aprutia', 'Aprutia', 'Teramo', 'serenna', 1058, 880, 'MOUNTAINS', 3000],
  ['campagna', 'Campagna', 'Neapolis', 'serenna', 1045, 960, 'PLAINS', 5000],
  ['calavria', 'Calavria', 'Regium', 'serenna', 1050, 1008, 'PLAINS', 3000],
  // Aestia — the amber coast.
  ['kurland', 'Kurland', 'Libau', 'aestia', 1120, 190, 'PLAINS', 4000],
  ['aestmark', 'Aestmark', 'Rigava', 'aestia', 1210, 170, 'PLAINS', 7000, true],
  ['livonia', 'Livonia', 'Dorpat', 'aestia', 1300, 150, 'PLAINS', 4000],
  ['zemgale', 'Zemgale', 'Mitau', 'aestia', 1150, 280, 'PLAINS', 4000],
  ['samogit', 'Samogit', 'Kaunas-Nemel', 'aestia', 1260, 265, 'PLAINS', 4000],
  // Vostrava — the eastern steppe and forests.
  ['ilmen', 'Ilmen', 'Novgrad', 'vostrava', 1400, 165, 'PLAINS', 6000],
  ['tverskaya', 'Tverskaya', 'Tver', 'vostrava', 1500, 205, 'PLAINS', 5000],
  ['polotsk', 'Polotsk', 'Polotsk', 'vostrava', 1360, 300, 'PLAINS', 4000],
  ['vostrov', 'Vostrov', 'Vostrava', 'vostrava', 1470, 320, 'PLAINS', 12000, true],
  ['ryazan', 'Ryazan', 'Ryazan', 'vostrava', 1540, 425, 'PLAINS', 7000],
  ['volhyn', 'Volhyn', 'Lutsk', 'vostrava', 1385, 430, 'PLAINS', 8000],
  ['severia', 'Severia', 'Chernigov', 'vostrava', 1480, 530, 'PLAINS', 4000],
  // Karpathia — the basin inside the eastern mountains.
  ['silesa', 'Silesa', 'Vratislav', 'karpathia', 1100, 455, 'PLAINS', 5000],
  ['galicja', 'Galicja', 'Krakova', 'karpathia', 1195, 440, 'PLAINS', 6000],
  ['tatrany', 'Tatrany', 'Zakopol', 'karpathia', 1295, 455, 'MOUNTAINS', 3000],
  ['pannonia', 'Pannonia', 'Vindobona', 'karpathia', 1120, 565, 'PLAINS', 6000],
  ['magyaria', 'Magyaria', 'Budavar', 'karpathia', 1220, 545, 'PLAINS', 10000, true],
  ['transylva', 'Transylva', 'Kolozsvar', 'karpathia', 1320, 560, 'MOUNTAINS', 4000],
  ['drava', 'Drava Marches', 'Zagrebo', 'karpathia', 1150, 660, 'PLAINS', 5000],
  ['banat', 'Banat', 'Temesvar', 'karpathia', 1250, 650, 'PLAINS', 5000],
  ['valahia', 'Valahia', 'Bucurest', 'karpathia', 1360, 665, 'PLAINS', 5000],
  // Dravonia — the south-eastern peninsula.
  ['dalmara', 'Dalmara', 'Spalatum', 'dravonia', 1180, 775, 'MOUNTAINS', 4000],
  ['rascia', 'Rascia', 'Belogard', 'dravonia', 1290, 760, 'PLAINS', 8000],
  ['moesia', 'Moesia', 'Tarnovo', 'dravonia', 1400, 785, 'PLAINS', 6000],
  ['dardania', 'Dardania', 'Dravonopolis', 'dravonia', 1245, 865, 'PLAINS', 12000, true],
  ['thrakia', 'Thrakia', 'Philippola', 'dravonia', 1365, 870, 'PLAINS', 7000],
  ['epiros', 'Epiros', 'Ioanna', 'dravonia', 1295, 960, 'MOUNTAINS', 4000],
  ['makedon', 'Makedon', 'Thessalona', 'dravonia', 1400, 960, 'PLAINS', 5000],
]

// Borders drawn as rivers (they keep the river attack penalty). Every pair must be adjacent.
const RIVERS = [
  ['valcaro', 'sierravel'],
  ['ligerais', 'limosin'],
  ['aurel', 'champeaux'],
  ['rhenanie', 'westfal'],
  ['champeaux', 'brabantia'],
  ['marchia', 'zemgale'],
  ['pannonia', 'drava'],
  ['drava', 'dalmara'],
  ['banat', 'rascia'],
  ['valahia', 'moesia'],
  ['lombarda', 'liguria'],
  ['volhyn', 'tatrany'],
]

// --- deterministic noise -----------------------------------------------------------------------

function hash(ix, iy, salt) {
  let h = (Math.imul(ix, 374761393) + Math.imul(iy, 668265263) + Math.imul(salt, 2246822519) + SEED) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  h ^= h >>> 16
  return (h >>> 0) / 4294967295
}

function valueNoise(x, y, scale, salt) {
  const fx = x / scale
  const fy = y / scale
  const ix = Math.floor(fx)
  const iy = Math.floor(fy)
  const tx = fx - ix
  const ty = fy - iy
  const sx = tx * tx * (3 - 2 * tx)
  const sy = ty * ty * (3 - 2 * ty)
  const a = hash(ix, iy, salt)
  const b = hash(ix + 1, iy, salt)
  const c = hash(ix, iy + 1, salt)
  const d = hash(ix + 1, iy + 1, salt)
  return (a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy) * 2 - 1
}

function warp(x, y) {
  const dx = valueNoise(x, y, 90, 1) * 0.7 + valueNoise(x, y, 35, 3) * 0.3
  const dy = valueNoise(x, y, 90, 2) * 0.7 + valueNoise(x, y, 35, 4) * 0.3
  return [x + dx * WARP, y + dy * WARP]
}

// --- rasterize ---------------------------------------------------------------------------------

function insidePolygon(x, y, poly) {
  let inside = false
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i]
    const [xj, yj] = poly[j]
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

const GW = WIDTH / CELL
const GH = HEIGHT / CELL
const SEA = -1
const label = new Int32Array(GW * GH).fill(SEA)
const at = (gx, gy) => (gx < 0 || gy < 0 || gx >= GW || gy >= GH ? SEA : label[gy * GW + gx])

for (let gy = 0; gy < GH; gy++) {
  for (let gx = 0; gx < GW; gx++) {
    const x = (gx + 0.5) * CELL
    const y = (gy + 0.5) * CELL
    // The coast is warped too, so it is as irregular as the borders.
    const cx = x + (valueNoise(x, y, 60, 5) * 0.6 + valueNoise(x, y, 22, 6) * 0.4) * 18
    const cy = y + (valueNoise(x, y, 60, 7) * 0.6 + valueNoise(x, y, 22, 8) * 0.4) * 18
    if (!insidePolygon(cx, cy, LAND)) continue
    const [wx, wy] = warp(x, y)
    let best = 0
    let bestD = Infinity
    PROVINCES.forEach((p, i) => {
      const d = (p[4] - wx) ** 2 + (p[5] - wy) ** 2
      if (d < bestD) {
        bestD = d
        best = i
      }
    })
    label[gy * GW + gx] = best
  }
}

// --- one connected piece per province ----------------------------------------------------------

function components(index) {
  const seen = new Uint8Array(GW * GH)
  const parts = []
  for (let c = 0; c < label.length; c++) {
    if (label[c] !== index || seen[c]) continue
    const part = []
    const stack = [c]
    seen[c] = 1
    while (stack.length) {
      const cur = stack.pop()
      part.push(cur)
      const cx = cur % GW
      const cy = (cur / GW) | 0
      for (const [nx, ny] of [[cx + 1, cy], [cx - 1, cy], [cx, cy + 1], [cx, cy - 1]]) {
        if (nx < 0 || ny < 0 || nx >= GW || ny >= GH) continue
        const n = ny * GW + nx
        if (!seen[n] && label[n] === index) {
          seen[n] = 1
          stack.push(n)
        }
      }
    }
    parts.push(part)
  }
  return parts
}

for (let pass = 0; pass < 6; pass++) {
  let changed = false
  PROVINCES.forEach((p, i) => {
    const parts = components(i)
    if (parts.length <= 1) return
    const seedCell = ((p[5] / CELL) | 0) * GW + ((p[4] / CELL) | 0)
    let keep = parts.find((part) => part.includes(seedCell)) ?? parts.reduce((a, b) => (a.length >= b.length ? a : b))
    for (const part of parts) {
      if (part === keep) continue
      const votes = new Map()
      for (const cur of part) {
        const cx = cur % GW
        const cy = (cur / GW) | 0
        for (const [nx, ny] of [[cx + 1, cy], [cx - 1, cy], [cx, cy + 1], [cx, cy - 1]]) {
          const l = at(nx, ny)
          if (l !== SEA && l !== i) votes.set(l, (votes.get(l) ?? 0) + 1)
        }
      }
      const target = [...votes.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0])[0]?.[0]
      if (target === undefined) continue
      for (const cur of part) label[cur] = target
      changed = true
    }
  })
  if (!changed) break
}

// --- border tracing ----------------------------------------------------------------------------
// Vertices are grid corners (vx, vy). A boundary unit edge separates two cells with different
// labels; each province's boundary is traced counter-clockwise (province on the left).

const vkey = (vx, vy) => vy * (GW + 1) + vx
const vxy = (k) => [k % (GW + 1), Math.floor(k / (GW + 1))]

/** Labels of the four cells around a grid corner. */
function around(vx, vy) {
  return [at(vx - 1, vy - 1), at(vx, vy - 1), at(vx - 1, vy), at(vx, vy)]
}

function isJunction(vk) {
  const [vx, vy] = vxy(vk)
  const a = around(vx, vy)
  const distinct = new Set(a)
  if (distinct.size >= 3) return true
  // Diagonal touch (checkerboard) is also a junction: borders must not pass through it twice.
  return distinct.size === 2 && a[0] === a[3] && a[1] === a[2] && a[0] !== a[1]
}

// Directed edges per province: from -> to, with the neighbour label on the right side.
const edgesOf = new Map()
function addEdge(province, from, to, other) {
  if (!edgesOf.has(province)) edgesOf.set(province, new Map())
  const map = edgesOf.get(province)
  if (!map.has(from)) map.set(from, [])
  map.get(from).push({ from, to, other })
}

for (let gy = 0; gy < GH; gy++) {
  for (let gx = 0; gx < GW; gx++) {
    const l = at(gx, gy)
    if (l === SEA) continue
    // Counter-clockwise in screen coordinates (y down) keeps the cell on the left of each edge.
    if (at(gx, gy - 1) !== l) addEdge(l, vkey(gx + 1, gy), vkey(gx, gy), at(gx, gy - 1))
    if (at(gx - 1, gy) !== l) addEdge(l, vkey(gx, gy), vkey(gx, gy + 1), at(gx - 1, gy))
    if (at(gx, gy + 1) !== l) addEdge(l, vkey(gx, gy + 1), vkey(gx + 1, gy + 1), at(gx, gy + 1))
    if (at(gx + 1, gy) !== l) addEdge(l, vkey(gx + 1, gy + 1), vkey(gx + 1, gy), at(gx + 1, gy))
  }
}

/** Closed rings of directed edges for one province. */
function rings(province) {
  const out = edgesOf.get(province)
  const used = new Set()
  const result = []
  for (const [start, list] of out) {
    for (const first of list) {
      const id = `${first.from}>${first.to}`
      if (used.has(id)) continue
      const ring = []
      let edge = first
      while (true) {
        used.add(`${edge.from}>${edge.to}`)
        ring.push(edge)
        const nexts = (out.get(edge.to) ?? []).filter((e) => !used.has(`${e.from}>${e.to}`))
        if (nexts.length === 0) break
        // At a pinch point prefer the left-most turn so rings stay simple.
        edge = nexts.length === 1 ? nexts[0] : pickLeft(edge, nexts)
      }
      result.push(ring)
    }
    void start
  }
  return result
}

function pickLeft(edge, candidates) {
  const [ax, ay] = vxy(edge.from)
  const [bx, by] = vxy(edge.to)
  const dir = [bx - ax, by - ay]
  const score = (e) => {
    const [cx, cy] = vxy(e.to)
    const d = [cx - bx, cy - by]
    const cross = dir[0] * d[1] - dir[1] * d[0]
    return -cross
  }
  return [...candidates].sort((a, b) => score(b) - score(a))[0]
}

// --- chains, simplification and smoothing ------------------------------------------------------

function dist(a, b) {
  return Math.hypot(a[0] - b[0], a[1] - b[1])
}

function douglasPeucker(points, tolerance) {
  if (points.length <= 2) return points
  const [a, b] = [points[0], points[points.length - 1]]
  let index = -1
  let max = 0
  for (let i = 1; i < points.length - 1; i++) {
    const p = points[i]
    const len = dist(a, b)
    const d =
      len === 0 ? dist(p, a) : Math.abs((b[0] - a[0]) * (a[1] - p[1]) - (a[0] - p[0]) * (b[1] - a[1])) / len
    if (d > max) {
      max = d
      index = i
    }
  }
  if (max <= tolerance) return [a, b]
  return [...douglasPeucker(points.slice(0, index + 1), tolerance).slice(0, -1), ...douglasPeucker(points.slice(index), tolerance)]
}

function chaikin(points, iterations) {
  let pts = points
  for (let k = 0; k < iterations; k++) {
    if (pts.length < 3) return pts
    const next = [pts[0]]
    for (let i = 0; i < pts.length - 1; i++) {
      const [p, q] = [pts[i], pts[i + 1]]
      next.push([0.75 * p[0] + 0.25 * q[0], 0.75 * p[1] + 0.25 * q[1]])
      next.push([0.25 * p[0] + 0.75 * q[0], 0.25 * p[1] + 0.75 * q[1]])
    }
    next.push(pts[pts.length - 1])
    pts = next
  }
  return pts
}

/** Map coordinates of a chain, simplified and smoothed; endpoints stay fixed. */
function shapeChain(vertexKeys) {
  const pts = vertexKeys.map((k) => {
    const [vx, vy] = vxy(k)
    return [vx * CELL, vy * CELL]
  })
  const simple = douglasPeucker(pts, 2.1)
  const smooth = chaikin(simple, 2)
  const rounded = []
  for (const [x, y] of smooth) {
    const p = [Math.round(x), Math.round(y)]
    const last = rounded[rounded.length - 1]
    if (!last || last[0] !== p[0] || last[1] !== p[1]) rounded.push(p)
  }
  return rounded
}

const chainCache = new Map()
function canonicalChain(vertexKeys) {
  const first = vertexKeys[0]
  const last = vertexKeys[vertexKeys.length - 1]
  const reversed = first > last || (first === last && vertexKeys[1] > vertexKeys[vertexKeys.length - 2])
  const canonical = reversed ? [...vertexKeys].reverse() : vertexKeys
  const key = canonical.join(',')
  if (!chainCache.has(key)) chainCache.set(key, shapeChain(canonical))
  const shaped = chainCache.get(key)
  return reversed ? [...shaped].reverse() : shaped
}

const shapes = new Map() // province index -> list of rings (list of points)
const shared = new Map() // "i|j" -> shared border length (map units)
const sharedChains = new Map() // "i|j" -> list of chains (points) for river drawing

PROVINCES.forEach((_, i) => {
  const result = []
  for (const ring of rings(i)) {
    // Split the ring at junctions and wherever the neighbour changes.
    const breaks = []
    for (let k = 0; k < ring.length; k++) {
      const prev = ring[(k - 1 + ring.length) % ring.length]
      if (isJunction(ring[k].from) || prev.other !== ring[k].other) breaks.push(k)
    }
    if (breaks.length === 0) breaks.push(0)
    const points = []
    for (let b = 0; b < breaks.length; b++) {
      const startIdx = breaks[b]
      const endIdx = b + 1 < breaks.length ? breaks[b + 1] : breaks[0] + ring.length
      const vertexKeys = [ring[startIdx].from]
      for (let k = startIdx; k < endIdx; k++) vertexKeys.push(ring[k % ring.length].to)
      const other = ring[startIdx].other
      const chain = canonicalChain(vertexKeys)
      if (other !== SEA) {
        const pair = i < other ? `${i}|${other}` : `${other}|${i}`
        let len = 0
        for (let k = 1; k < chain.length; k++) len += dist(chain[k - 1], chain[k])
        if (i < other) {
          shared.set(pair, (shared.get(pair) ?? 0) + len)
          if (!sharedChains.has(pair)) sharedChains.set(pair, [])
          sharedChains.get(pair).push(chain)
        }
      }
      points.push(...chain.slice(0, -1))
    }
    result.push(points)
  }
  shapes.set(i, result)
})

// --- adjacency, cities, report -----------------------------------------------------------------

const adjacency = []
for (const [pair, len] of shared) {
  if (len >= MIN_BORDER) {
    const [i, j] = pair.split('|').map(Number)
    adjacency.push([i, j, len])
  }
}

const problems = []
const keyIndex = new Map(PROVINCES.map((p, i) => [p[0], i]))
const riverPairs = new Set()
for (const [a, b] of RIVERS) {
  const i = keyIndex.get(a)
  const j = keyIndex.get(b)
  const pair = i < j ? `${i}|${j}` : `${j}|${i}`
  if (!adjacency.some(([x, y]) => `${x}|${y}` === pair)) problems.push(`River ${a}-${b} is not a land border`)
  riverPairs.add(pair)
}

const cityOf = PROVINCES.map((p, i) => {
  let cx = Math.min(GW - 1, (p[4] / CELL) | 0)
  let cy = Math.min(GH - 1, (p[5] / CELL) | 0)
  if (at(cx, cy) !== i) {
    let best = null
    let bestD = Infinity
    for (let c = 0; c < label.length; c++) {
      if (label[c] !== i) continue
      const d = ((c % GW) - cx) ** 2 + (((c / GW) | 0) - cy) ** 2
      if (d < bestD) {
        bestD = d
        best = c
      }
    }
    if (best === null) {
      problems.push(`Province ${p[0]} has no land`)
      return [p[4], p[5]]
    }
    cx = best % GW
    cy = (best / GW) | 0
  }
  return [cx * CELL + 1, cy * CELL + 1]
})

const area = PROVINCES.map((_, i) => label.reduce((n, l) => n + (l === i ? 1 : 0), 0) * CELL * CELL)
area.forEach((a, i) => {
  if (a < 2500) problems.push(`Province ${PROVINCES[i][0]} is tiny (${a})`)
})
PROVINCES.forEach((p, i) => {
  if (!adjacency.some(([x, y]) => x === i || y === i)) problems.push(`Province ${p[0]} has no neighbour`)
  if (!NATIONS.some((n) => n.key === p[3])) problems.push(`Province ${p[0]} has unknown nation ${p[3]}`)
})
for (const n of NATIONS) {
  const capitals = PROVINCES.filter((p) => p[3] === n.key && p[8])
  if (capitals.length !== 1) problems.push(`Nation ${n.key} needs exactly one capital`)
}

// Connected land graph.
const seen = new Set([0])
const queue = [0]
while (queue.length) {
  const cur = queue.shift()
  for (const [i, j] of adjacency) {
    const next = i === cur ? j : j === cur ? i : null
    if (next !== null && !seen.has(next)) {
      seen.add(next)
      queue.push(next)
    }
  }
}
if (seen.size !== PROVINCES.length) problems.push('Province graph is not connected')

const degree = PROVINCES.map((_, i) => adjacency.filter(([x, y]) => x === i || y === i).length)
const nationNeighbours = new Map()
for (const [i, j] of adjacency) {
  const a = PROVINCES[i][3]
  const b = PROVINCES[j][3]
  if (a === b) continue
  for (const [x, y] of [[a, b], [b, a]]) {
    if (!nationNeighbours.has(x)) nationNeighbours.set(x, new Set())
    nationNeighbours.get(x).add(y)
  }
}

console.log(`provinces ${PROVINCES.length}, nations ${NATIONS.length}, borders ${adjacency.length}`)
console.log(`neighbours per province: min ${Math.min(...degree)}, max ${Math.max(...degree)}, >4: ${degree.filter((d) => d > 4).length}`)
console.log(`province area: min ${Math.min(...area)}, max ${Math.max(...area)}`)
for (const n of NATIONS) {
  const own = PROVINCES.filter((p) => p[3] === n.key)
  const pop = own.reduce((s, p) => s + p[7], 0)
  console.log(`${n.key.padEnd(10)} ${n.tier.padEnd(8)} provinces ${String(own.length).padStart(2)} population ${String(pop).padStart(6)} borders ${[...(nationNeighbours.get(n.key) ?? [])].sort().join(', ')}`)
}
const shortBorders = [...shared.entries()].filter(([, len]) => len > 0 && len < MIN_BORDER)
console.log(`corner/short contacts ignored: ${shortBorders.length}`)
if (problems.length) {
  console.error('PROBLEMS:\n' + problems.join('\n'))
  process.exit(1)
}

// --- SQL ---------------------------------------------------------------------------------------

const sql = (s) => `'${String(s).replaceAll("'", "''")}'`
const path = (rings) =>
  rings.map((ring) => 'M' + ring.map(([x, y], k) => `${k === 0 ? '' : 'L'}${x} ${y}`).join(' ') + ' Z').join(' ')
const riverPath = (pair) =>
  sharedChains
    .get(pair)
    .map((chain) => 'M' + chain.map(([x, y], k) => `${k === 0 ? '' : 'L'}${x} ${y}`).join(' '))
    .join(' ')

const lines = []
lines.push(`-- Phase 10 seed: "Grand Campaign", version 1 — a Europe-inspired continent with ${PROVINCES.length} provinces`)
lines.push(`-- and ${NATIONS.length} fictional factions. GENERATED by tools/mapgen/grand-campaign.mjs (original data, no`)
lines.push('-- external map sources). Neighbouring provinces share identical border points; two provinces are')
lines.push(`-- adjacent exactly when they share at least ${MIN_BORDER} units of land border (map_template.min_border_length).`)
lines.push('-- Never edit this migration; publish changes as a new map version.')
lines.push('')
lines.push('INSERT INTO map_template (key, version, name, view_box, max_players, min_border_length)')
lines.push(`VALUES ('grand-campaign', 1, 'Grand Campaign', '0 0 ${WIDTH} ${HEIGHT}', 4, ${MIN_BORDER});`)
lines.push('')
lines.push('INSERT INTO province_template (map_template_id, key, name, terrain, svg_path, label_x, label_y, city_name,')
lines.push('    start_population, city_x, city_y)')
lines.push('SELECT m.id, v.key, v.name, v.terrain, v.svg_path, v.label_x, v.label_y, v.city_name, v.start_population,')
lines.push('    v.city_x, v.city_y')
lines.push('FROM map_template m')
lines.push('CROSS JOIN (VALUES')
lines.push(
  PROVINCES.map((p, i) => {
    const [cx, cy] = cityOf[i]
    return `(${sql(p[0])}, ${sql(p[1])}, ${sql(p[6])}, ${sql(path(shapes.get(i)))}, ${cx}, ${cy + 16}, ${sql(p[2])}, ${p[7]}, ${cx}, ${cy})`
  }).join(',\n'),
)
lines.push(') AS v (key, name, terrain, svg_path, label_x, label_y, city_name, start_population, city_x, city_y)')
lines.push("WHERE m.key = 'grand-campaign' AND m.version = 1;")
lines.push('')
lines.push('INSERT INTO province_adjacency (map_template_id, province_a_id, province_b_id, crossing, river_svg_path)')
lines.push('SELECT m.id, LEAST(a.id, b.id), GREATEST(a.id, b.id), v.crossing, v.river_svg_path')
lines.push('FROM (VALUES')
lines.push(
  adjacency
    .sort((x, y) => (PROVINCES[x[0]][0] + PROVINCES[x[1]][0]).localeCompare(PROVINCES[y[0]][0] + PROVINCES[y[1]][0]))
    .map(([i, j]) => {
      const pair = `${i}|${j}`
      const river = riverPairs.has(pair)
      return `(${sql(PROVINCES[i][0])}, ${sql(PROVINCES[j][0])}, ${river ? "'RIVER'" : "'NONE'"}, ${river ? sql(riverPath(pair)) : 'NULL'})`
    })
    .join(',\n'),
)
lines.push(') AS v (a_key, b_key, crossing, river_svg_path)')
lines.push("JOIN map_template m ON m.key = 'grand-campaign' AND m.version = 1")
lines.push('JOIN province_template a ON a.map_template_id = m.id AND a.key = v.a_key')
lines.push('JOIN province_template b ON b.map_template_id = m.id AND b.key = v.b_key;')
lines.push('')
lines.push('INSERT INTO nation_template (map_template_id, key, name, color, capital_province_id, tier, playable)')
lines.push('SELECT m.id, v.key, v.name, v.color, p.id, v.tier, v.playable')
lines.push('FROM (VALUES')
lines.push(
  NATIONS.map((n) => {
    const capital = PROVINCES.find((p) => p[3] === n.key && p[8])[0]
    return `(${sql(n.key)}, ${sql(n.name)}, ${sql(n.color)}, ${sql(capital)}, ${sql(n.tier)}, ${n.playable})`
  }).join(',\n'),
)
lines.push(') AS v (key, name, color, capital_key, tier, playable)')
lines.push("JOIN map_template m ON m.key = 'grand-campaign' AND m.version = 1")
lines.push('JOIN province_template p ON p.map_template_id = m.id AND p.key = v.capital_key;')
lines.push('')
lines.push('INSERT INTO nation_start_province (map_template_id, nation_template_id, province_template_id)')
lines.push('SELECT m.id, n.id, p.id')
lines.push('FROM (VALUES')
lines.push(PROVINCES.map((p) => `(${sql(p[3])}, ${sql(p[0])})`).join(',\n'))
lines.push(') AS v (nation_key, province_key)')
lines.push("JOIN map_template m ON m.key = 'grand-campaign' AND m.version = 1")
lines.push('JOIN nation_template n ON n.map_template_id = m.id AND n.key = v.nation_key')
lines.push('JOIN province_template p ON p.map_template_id = m.id AND p.key = v.province_key;')
lines.push('')

const previewIndex = process.argv.indexOf('--preview')
if (previewIndex > 0) {
  const color = new Map(NATIONS.map((n) => [n.key, n.color]))
  const svg = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${WIDTH} ${HEIGHT}" width="${WIDTH}" height="${HEIGHT}">`,
    `<rect width="${WIDTH}" height="${HEIGHT}" fill="#1d3550"/>`]
  PROVINCES.forEach((p, i) => {
    svg.push(`<path d="${path(shapes.get(i))}" fill="${color.get(p[3])}" fill-rule="evenodd" stroke="#111" stroke-width="1.5" opacity="${p[6] === 'MOUNTAINS' ? 0.7 : 1}"/>`)
  })
  for (const pair of riverPairs) svg.push(`<path d="${riverPath(pair)}" fill="none" stroke="#6ec1ff" stroke-width="3"/>`)
  PROVINCES.forEach((p, i) => {
    const [cx, cy] = cityOf[i]
    svg.push(`<circle cx="${cx}" cy="${cy}" r="${p[8] ? 6 : 3}" fill="#fff"/><text x="${cx}" y="${cy + 16}" font-size="11" text-anchor="middle" fill="#fff">${p[1]}</text>`)
  })
  svg.push('</svg>')
  writeFileSync(process.argv[previewIndex + 1], svg.join('\n'))
}

if (!process.argv.includes('--check')) {
  const here = dirname(fileURLToPath(import.meta.url))
  const target = resolve(here, '../../backend/src/main/resources/db/migration/V13__seed_grand_campaign_map.sql')
  writeFileSync(target, lines.join('\n'))
  console.log(`written ${target}`)
}
