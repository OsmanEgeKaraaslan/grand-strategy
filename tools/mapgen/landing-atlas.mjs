// Regenerates frontend/src/landing/campaignMap.ts, the static Grand Campaign snapshot shown on the
// signed-out pages, from the seed migrations. Run from the repository root:
//   node tools/mapgen/landing-atlas.mjs
import { readFileSync, writeFileSync } from 'node:fs'

const HEADER = "// GENERATED from backend migrations V13 (Grand Campaign v1 geometry) and V22 (faction names).\n// A static snapshot for the signed-out pages, which cannot call the game API. Never edit by hand.\n\nexport interface AtlasNation { key: string; name: string; color: string; capital: string; tier: 'POWER' | 'MAJOR' | 'REGIONAL' | 'MINOR'; playable: boolean }\nexport interface AtlasProvince { key: string; name: string; terrain: 'PLAINS' | 'MOUNTAINS'; d: string; lx: number; ly: number; city: string; cx: number; cy: number; owner: string }\nexport interface AtlasData { viewBox: string; nations: AtlasNation[]; provinces: AtlasProvince[]; rivers: string[] }\n\n"
const migrations = "backend/src/main/resources/db/migration/"
const v13 = migrations + "V13__seed_grand_campaign_map.sql"
const v22 = migrations + "V22__rename_factions.sql"
const out = "frontend/src/landing/campaignMap.ts"
const sql = readFileSync(v13, 'utf8')
const ren = readFileSync(v22, 'utf8')
const section = (start, end) => sql.slice(sql.indexOf(start), sql.indexOf(end, sql.indexOf(start)))
const tuples = (text) => [...text.matchAll(/^\((.*)\),?$/gm)].map((m) => m[1])
const fields = (t) => [...t.matchAll(/'((?:[^']|'')*)'|(-?\d+(?:\.\d+)?)|NULL|true|false/g)].map((m) => m[1] !== undefined ? m[1].replace(/''/g, "'") : m[2] !== undefined ? Number(m[2]) : m[0] === 'NULL' ? null : m[0] === 'true')
const names = Object.fromEntries([...ren.matchAll(/\('grand-campaign',\s*'(\w+)',\s*'([^']+)'\)/g)].map((m) => [m[1], m[2]]))
const provinces = tuples(section('INSERT INTO province_template', ') AS v (key')).map(fields).map(([key, name, terrain, d, lx, ly, city, , cx, cy]) => ({ key, name, terrain, d, lx, ly, city, cx, cy }))
const rivers = tuples(section('INSERT INTO province_adjacency', ') AS v (a_key')).map(fields).filter((f) => f[3]).map((f) => f[3])
const nations = tuples(section('INSERT INTO nation_template', ') AS v (key, name, color')).map(fields).map(([key, , color, capital, tier, playable]) => ({ key, name: names[key], color, capital, tier, playable }))
const owners = Object.fromEntries(tuples(section('INSERT INTO nation_start_province', ') AS v (nation_key')).map(fields).map(([n, p]) => [p, n]))
for (const p of provinces) p.owner = owners[p.key] ?? null
writeFileSync(out, HEADER + "export const campaignMap: AtlasData = " + JSON.stringify({ viewBox: "0 0 1600 1100", nations, provinces, rivers }) + "\n")
console.log(provinces.length, 'provinces', nations.length, 'nations', rivers.length, 'rivers', provinces.filter((p) => !p.owner).length, 'unowned')
