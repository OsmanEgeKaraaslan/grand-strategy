# Frontend overhaul plan (UI-A → UI-D)

Goal: turn the working but panel-heavy game screen into a map-first grand-strategy interface
(Europa Universalis layout, light Total War campaign-map flavour). **Functional UX first, visuals
second.** Gameplay rules, the backend API and the database are not changed by this overhaul; every
change is presentation over the existing TanStack Query hooks and REST commands.

This document is self-contained: a new agent can continue from §5 ("Checkpoint") without any other
conversation. Read `AGENTS.md` §4 (TypeScript/React rules) first.

> **Priority change (2026-10-22, product owner):** after a manual review the screen still felt like
> a dashboard. Before any further UI-C/UI-D work, the game screen became a **full-viewport,
> map-first shell** with floating contextual windows (§5, session 4). That layout supersedes the
> "map card + permanent inspector column" of UI-A; §2 shows the current target. UI-C and UI-D
> continue only on top of it.

## 1. Current state (inspected 2026-10-21)

- Stack: React 19, TypeScript strict, Vite, React Router, TanStack Query, Vitest + Testing
  Library, plain CSS (`frontend/src/index.css`, ~1,500 lines, CSS variables, dark theme). No UI
  library, no state library beyond TanStack Query.
- Server state only (AGENTS.md §4): `api/queries.ts` hooks keyed by `stateVersion`; the game state
  is polled every 3 s (`useGameState`), other views refetch when `stateVersion` changes. All
  commands go through `useGameCommand` → invalidate state. **Keep this model.**
- Game screen: `pages/GamePage.tsx`
  - `GamePage` → `AppShell` (header) → `GameView`.
  - `GameView` holds UI state: `selectedKey` (province), `selectedBattalions`, shown battle.
  - Layout `.game` → `TurnBar` (round, whose turn, End turn, pause banner) → `.game__body` grid:
    map card (left, flexible) + `.game__side` (330 px column with ~10 stacked cards: result,
    Treasury, Research, Diplomacy, battle report, Province (economy, institutions, fortification,
    military panels), Sieges, Forces, Players).
- Map: `components/ProvinceMap.tsx` — SVG from template geometry; pan/zoom (buttons, wheel, drag),
  province click = select, city markers, unit count badges, fortress/siege markers, move targets
  highlighted when battalions are selected.
- Army control today: select a province → `MilitaryPanel` (inside the Province card) lists
  battalions with checkboxes → `MoveControls` buttons "Move to X" / "Attack X" for each neighbour.
  Lending (attach), reinforce, disband, regroup also live there.
- Tests: 18 Vitest files, 127 tests (`pages/*.test.tsx` render `GamePage` with `fakeApi` from
  `test/render.tsx` and fixtures from `test/fixtures.ts`). Tests query by **accessible role/name**
  (e.g. region "Elmsford", button "Attack Sablewater", group "Send gold"), which is what keeps them
  stable across layout changes — **preserve accessible names**.

### Main UX problems
1. Everything is in one long side column: the map scrolls away while you work in a panel.
2. No persistent resources: treasury/income only in the Treasury card.
3. No navigation: finding Diplomacy or Research means scrolling.
4. Army orders need several clicks in a distant panel (select province → scroll → tick boxes →
   find the right "Move to" button); no right-click, no army marker selection.
5. One map mode (political); war/diplomacy/terrain not visible at a glance.
6. Notifications (battles, calls to arms, conference requests) are buried in lists.
7. Panels are long text forms; the peace conference is a dense card.

### Integration risks (all milestones)
- **Accessible names are the test contract.** Renaming a button or region breaks many tests; keep
  names or update tests deliberately in the same change.
- **One source of truth for selection.** `selectedKey`/`selectedBattalions` live in `GameView`;
  moving them (context or a small store) must keep `MilitaryPanel` and the map in sync.
- **Polling + local UI state:** never mirror server data in local state; derive from queries.
- **Out-of-turn actions** (conferences) and **computer turns** (Phase 11: other seats may be
  computers, `players[].computer`) must stay visible and usable.
- **SVG performance** on the 72-province map: avoid re-rendering all paths on every hover; memoize
  path elements; keep hover state local to the map.
- **No new dependencies** without a written justification (AGENTS.md §1). Three.js / React Three
  Fiber (UI-B, optional) needs approval and bundle-size review.

## 2. Target layout (all milestones)

The whole browser viewport is the game (no page margins, no page scroll); the map is the
background layer and everything else floats over it (session 4):

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ← flag nation · Round · gold · net · people · army · research   turn 🔔 End turn │  top bar
├──────────────────────────────────────────────────────────────────────────┤
│┌┐ ┌──────────────┐                                     ┌──────────────┐ │
││▣│ │ management   │        MAP (full viewport,          │ province OR  │ │
││▣│ │ window (one  │        drag/zoom/right-click)       │ army window  │ │
││▣│ │ at a time)   │                                     │ (contextual) │ │
│└┘ └──────────────┘                                     └──────────────┘ │
│ map modes            order prompt / attack confirm         + − ⤢ 3D       │
└──────────────────────────────────────────────────────────────────────────┘
```

## 3. Milestones

### UI-A — Core layout, toolbar, resource bar, inspectors, map-based army controls
- **Existing components:** `GamePage.tsx` (`GameView`, `TurnBar`, `ProvincePanel`, `PlayersCard`),
  `TreasuryPanel`, `ResearchPanel`, `DiplomacyPanel`, `MilitaryPanel`, `ForcesCard`, `SiegesCard`,
  `ProvinceMap`, `index.css` (`.game`, `.turn-bar`, `.game__body`, `.game__side`).
- **Planned changes:**
  1. `ResourceBar` under/inside the turn bar: treasury, net per round (coloured), army
     (battalions/soldiers, upkeep), research points, round. Read-only, from existing queries.
  2. `GameToolbar`: vertical icon toolbar left of the map (Economy, Military, Technology, Diplomacy,
     Province, Players) that brings the matching panel into view (first step), later switches the
     inspector content (step 5).
  3. Map-first grid: toolbar | map | inspector; the map stays visible (sticky) while the inspector
     scrolls; map uses the full height on desktop.
  4. Context inspector: the right column shows *one* panel at a time (selected toolbar panel or the
     selected province), with the others reachable via the toolbar. Only after every panel is
     reachable there may the long stacked column go away.
  5. Map-based army control: left-click a province selects it and its own battalions (army stack);
     right-click an adjacent province with a stack selected issues "Move" or "Attack" through the
     existing `useMove`; a confirmation popover for attacks (odds text from the existing move
     targets); keyboard alternative (Enter on a focused province).
- **Dependencies:** none new.
- **Risks:** right-click must call `preventDefault` only on the map; selection state shared by map
  and `MilitaryPanel`; touchpads (provide a visible "Move here" button in the inspector as well).
- **Tests:** ResourceBar values (gold, net, army) from fixtures; toolbar buttons exist with names and
  bring panels into view; layout keeps every existing accessible name; right-click move calls
  `api.move` with the selected battalions and target; attack asks confirmation; non-adjacent
  right-click does nothing; existing `GameMilitary.test.tsx` still passes.
- **Acceptance:** resources always visible; any panel reachable in one click; a stack can be moved
  or attack with left-click + right-click; no gameplay control lost; all Vitest, tsc, lint, build
  green.

### UI-B — Map visual overhaul, map modes, army markers, camera
- **Existing components:** `ProvinceMap.tsx` (SVG paths, labels, city circles, unit badges,
  fortress markers, `clampView`), CSS `.province*`.
- **Planned changes:** map modes (political, diplomatic — at war/allied/neutral colours from
  `relationsOf`, terrain — plains/mountains/rivers, economy — income heat); army markers as
  per-nation stack tokens with strength; selected-stack highlight and movement-preview arrows;
  hover tooltip (owner, controller, garrison, fortress); smoother camera (zoom to cursor, double-click
  zoom, keyboard pan); optional lightweight 2.5D (CSS/SVG relief or React Three Fiber) **only after
  approval** and only if the SVG version is complete.
- **Dependencies:** `three` + `@react-three/fiber` (+ dev `@types/three`) — **approved by the user
  2026-10-22** for an optional, lazy-loaded 3D map view (see §5 session 3).
- **Risks:** performance (72 paths fine; avoid filters on every path), accessibility of map modes
  (patterns as well as colours), keeping click targets identical for tests.
- **Tests:** map mode switch changes fills (test via class/attribute, not colours), markers show
  counts, tooltip content, camera buttons still work.
- **Acceptance:** modes switchable from the toolbar/map corner; armies readable at a glance; no FPS
  drop when panning on a mid-range laptop.

### UI-C — Gameplay panels
- **Existing components:** `CityEconomyPanel`, `InstitutionsPanel`, `ResearchPanel`,
  `FortificationPanel`, `MilitaryPanel`, `BattalionManagement`, `DiplomacyPanel`, `PeaceConference`,
  `PeaceNegotiation`, `SendGold`, `BattleReportView`, `SiegesCard`, `GameResultView`.
- **Planned changes:** economy overview table (all cities, workshops, income) instead of per-city
  only; research as a two-track tree with doctrines; diplomacy as a nation list with relation chips
  and an action drawer; peace conference as a full-screen modal (sides, revision timeline, term
  builder with drag-free selectors, approval checklist); battle reports as a modal with per-nation
  bars; siege progress bars; notifications centre (battles, calls, conference requests, AI wars).
- **Dependencies:** none new.
- **Risks:** the conference is out-of-turn and pauses the game — modal must reflect the pause and be
  reopenable; many tests target these panels (update deliberately).
- **Tests:** per panel, keep existing behavioural tests; add modal open/close, notifications.
- **Acceptance:** every gameplay action available with fewer clicks than today; peace conference
  usable end to end with a human and a computer.

### UI-D — Polish, sound, accessibility, responsiveness, verification
- **Planned changes:** small transitions (panel slide, marker movement, battle flash) respecting
  `prefers-reduced-motion`; optional basic sound effects (end turn, battle, peace) with a mute
  toggle, off by default for tests; focus management, keyboard shortcuts (E economy, M military,
  D diplomacy, T technology, Enter end turn with confirmation), ARIA landmarks, contrast check;
  layouts for 1280–2560 px wide; a minimal tablet layout; Playwright happy path (AGENTS.md §5).
- **Dependencies:** possibly Playwright (already planned in AGENTS.md).
- **Risks:** audio autoplay policies; animations in tests (disable via reduced motion).
- **Acceptance:** keyboard-only play of a turn; Lighthouse accessibility ≥ 90; all tests green.

## 4. Working rules for whoever continues

- Small, verifiable steps; never remove an existing control before its replacement works.
- Keep accessible names stable or update tests in the same change.
- Run focused tests (`npx vitest run src/pages/GamePage.test.tsx ...`) per step; the full
  `npx vitest run`, `npx tsc -b`, `npm run lint`, `npm run build` at the end of a milestone.
- No backend, schema or rule changes; no commits or pushes without the user's permission.

## 5. Checkpoint

### Session 1 — 2026-10-21 (planning + first UI-A slice)

**Completed (UI-A steps 1–3, first version):**
- `ResourceBar` below the turn bar: nation, gold, net change per round (red when negative), army
  (battalions · soldiers · upkeep), research points per pool (when research exists), "In arrears".
- `GameToolbar`: vertical icon toolbar left of the map (Economy, Province, Military, Sieges,
  Technology, Diplomacy, Players). Each button scrolls the matching existing panel into view and
  focuses its heading (it targets the cards' heading ids). Becomes a horizontal row under 960 px.
- Map-first grid: `.game__body` is now `toolbar | map | 340 px side column`; the map card is sticky
  (stays visible while the side column scrolls) and limited to the viewport height.
- The "Select a province" placeholder card got `id="province-title"` so the toolbar can reach it.
- No panel, control or accessible name was removed or renamed.

**Files added / modified:**
- added `frontend/src/components/ResourceBar.tsx`, `frontend/src/components/GameToolbar.tsx`,
  `frontend/src/pages/GameLayout.test.tsx`, `docs/FRONTEND_OVERHAUL_PLAN.md`
- modified `frontend/src/pages/GamePage.tsx` (renders both; placeholder heading id) and
  `frontend/src/index.css` (game grid, sticky map, resource bar and toolbar styles)
- No backend, schema or API change. Nothing committed.

**Partially implemented:** the toolbar only scrolls/focuses; the side column still shows every panel
stacked. UI-A step 4 (one-panel context inspector) and step 5 (right-click army orders) are not
started. The Treasury card still exists next to the resource bar (intentional until the inspector
replaces the column).

**Tests that actually passed this session:**
- `npx vitest run` (in `frontend/`): 19 files, **130 tests passed** (127 before + 3 new in
  `GameLayout.test.tsx`).
- `npx tsc -b` clean; `npm run lint` 3 warnings, all pre-existing (none new); `npm run build` OK.
- Backend untouched and not re-run (last full run, Phase 11: 366 unit + 200 integration tests green).
- **Not done:** no manual check in a real browser; the sticky map on very short or very tall windows
  is unverified.

**Known issues / risks:** the map's `max-height: calc(100vh - 11rem)` is a guess for typical desktop
sizes (short windows letterbox the map). Toolbar icons are Unicode glyphs (font-dependent); replace
with inline SVG icons later (UI-B/UI-D).

**Exact next task — UI-A step 4, the context inspector:**
1. Add `activePanel` state in `GameView` (`'province' | 'economy' | 'military' | 'sieges' |
   'technology' | 'diplomacy' | 'players'`, default `'province'`); give `GameToolbar` `active` and
   `onSelect` props and mark the active button with `aria-pressed`.
2. Render only the active panel in `.game__side`; the game result and an open battle report stay
   always visible; selecting a province on the map switches to `'province'`.
3. Update the page tests that expect several panels at once (`GameDiplomacy`, `GameResearch`,
   `GameSiege`, `GameCampaign`, `GameCoalition`, `GameEconomy`, `GameLayout`): click the toolbar
   button first. Keep every accessible name.
4. Afterwards step 5: right-click an adjacent province with battalions selected → move/attack via
   `useMove`, with a confirmation for attacks; keep the existing buttons.

**Continuation instruction for the next session:** "Read `docs/FRONTEND_OVERHAUL_PLAN.md` and
`AGENTS.md`. Continue UI-A from 'Exact next task' in §5: implement the one-panel context inspector
driven by `GameToolbar` without removing any gameplay control, keep accessible names stable, update
the affected page tests, run focused tests and then the full frontend checks. Do not start UI-B. Do
not commit."


### Session 2 — 2026-10-22 (UI-A completed, UI-B first pass)

**UI-A — completed:**
- **Context inspector.** The side column (`<aside aria-label="Inspector">`) shows one panel at a time:
  Province (default), Economy (Treasury), Military (Your forces), Sieges, Technology (Research),
  Diplomacy, Players. `GameToolbar` switches it (`aria-pressed` on the active button); selecting a
  province on the map (or a siege in the Sieges list) switches to Province. The game result, an open
  battle report and data errors stay visible above any panel. No panel or control was removed.
- **Waiting-business badge.** The Diplomacy button shows how many things wait for the player
  (conference answers/approvals, calls to arms, alliance and peace proposals) — the accessible name
  becomes "Show diplomacy (n waiting)" — so out-of-turn conferences are not missed.
- **Army selection on the map.** The player's own unit marker is a button ("Select your army in X
  (n battalions)"): it selects the province and every battalion the player commands there that can
  still move. The province panel's checkboxes show and edit the same selection. Esc clears it.
- **Right-click orders.** With battalions selected, legal destinations are highlighted (MOVE dashed
  gold, ATTACK red; computed like the old move buttons: adjacent, enterable, hostile battalions =
  attack). Right-click (or M on a focused province) moves at once; an attack asks for confirmation
  in an alert dialog ("Attack X" / "Cancel"). Illegal destinations, other players' turns and paused
  games only explain why. The server validates every order (`useMove`); errors are shown under the
  map; a battle report opens after an attack. The old Move/Attack buttons in the province panel still
  work.

**UI-B — first pass (SVG; no new dependency):**
- **Map modes** (buttons top-left of the map, `role=group "Map mode"`): Political (default),
  Diplomatic (self/ally incl. co-belligerents/war/peace, from `relationsOf(...).standing`; hidden in
  games without diplomacy), Terrain (plains/mountains, stronger rivers), Economy (own provinces by
  collectible income in 4 levels). A legend explains Diplomatic, Terrain and Economy colours.
- **Army markers** per relation — yours (selectable), allied (green), enemy — with battalion count
  and soldiers ("2 · 1.7k"); the selected army's marker is outlined.
- **Movement preview**: arrows from the selected army to each legal destination (red for attacks).
- **Hover tooltip** (province, terrain, owner/occupier, city population, income in economy mode,
  battalions with soldiers, fortress, siege, "Right-click to move/attack here"). It lives in its own
  component so hovering never re-renders the 72 province shapes.
- **Camera**: arrow keys pan, + / − zoom, 0 resets (on the focused map), double-click zooms in at the
  pointer; buttons, wheel and drag as before.
- **Not done in UI-B:** 2.5D/3D rendering (Three.js / React Three Fiber) — **needs the user's
  approval** as a new dependency (AGENTS.md §1); smooth animated camera; zoom-dependent label
  density; SVG icons instead of Unicode glyphs.

**Files changed this session:** `frontend/src/pages/GamePage.tsx` (inspector, map orders,
`moveTargets`, `diplomacyBusiness`, `MapOrders`, unit counts with men/allied, income),
`frontend/src/components/GameToolbar.tsx` (active/onSelect/badges), `frontend/src/components/ProvinceMap.tsx`
(rewritten: modes, legend, targets map, arrows, markers, tooltip, camera keys),
`frontend/src/lib/diplomacy.ts` (`Relations.standing`, co-belligerents), `frontend/src/index.css`
(UI-A/UI-B styles appended), `frontend/src/test/render.tsx` (`showPanel` helper), tests:
`GameLayout.test.tsx` (rewritten: inspector + map orders), new `components/ProvinceMap.test.tsx`,
and updated `GameDiplomacy`, `GameCoalition`, `GameResearch`, `GameEconomy`, `GameMilitary`,
`GameReinforcement`, `GameSiege`, `GameCampaign`, `GamePage` tests (open the needed panel first;
one assertion changed from "move target" to "attack target" because an enemy-held neighbour is now
correctly an attack target).

**Verification (actually run):** `npx vitest run` — 20 files, **143 tests passed**; `npx tsc -b`
clean; `npm run lint` 3 pre-existing warnings (none new); `npm run build` OK (bundle ~663 kB, the
existing >500 kB chunk warning). Backend untouched (not re-run). **No manual browser check** — the
app needs the backend and a Supabase login; please try the manual steps below.

**Manual check suggested:** start backend + frontend, open a Grand Campaign game: switch panels with
the toolbar; click your army marker, right-click a highlighted neighbour; right-click an enemy army
(confirm dialog); try the map modes, hover tooltip, arrow keys / + / − / 0 and double-click.

*(Session 2's next task and continuation instruction are superseded by session 3 below.)*

### Session 3 — 2026-10-22 (UI-B optional 3D map view)

**Dependency justification (AGENTS.md §1, approved by the user 2026-10-22):** `three` 0.186.1 and
`@react-three/fiber` 9.8.2 (React 19 compatible) as runtime dependencies, `@types/three` 0.186.0 as
a dev dependency, pinned to exact versions; `npm install` reported 0 vulnerabilities. Purpose: an
optional 2.5D view of the campaign map. It is loaded with `React.lazy`, so three.js is in its own
`Map3D-*.js` chunk (~915 kB, ~243 kB gzip) downloaded only when a player presses "3D"; verified that
the main chunk contains no three.js code. No backend, rules or schema change.

**Completed:**
- `lib/mapGeometry.ts`: `parsePolygons` turns the template outlines (absolute M/L/H/V/Z — all the
  seeded maps use M/L/Z) into polygons; `webglAvailable()` feature check.
- `components/Map3D.tsx` (lazy default export): provinces extruded from the outlines (mountains
  taller, the selected province raised), coloured exactly like the active 2D map mode; sea plane;
  army stacks as own/allied/enemy coloured columns whose height follows the battalion count; legal
  destinations tinted gold (move) / red (attack). Same orders as the 2D map: click selects a province,
  clicking your column selects that army, right-click orders it (server-validated as before). Drag
  pans, wheel zooms; the camera follows the shared 2D view, so the zoom buttons, keys and "show the
  whole map" also drive it. `frameloop="demand"` keeps it idle when nothing changes.
- `ProvinceMap.tsx`: a "3D" toggle in the map-view toolbar (2D is the default), disabled with an
  explanation when WebGL is unavailable; "Loading 3D view…" while the chunk loads; the hover
  tooltip, legend and map modes are shared.
- Tests: `lib/mapGeometry.test.ts` (3); `ProvinceMap.test.tsx` +2 (toggle disabled without WebGL;
  toggle swaps in the lazy view — stubbed, jsdom has no WebGL — with the mode colours, selection,
  orders and shared camera, and back to 2D).

**Limitations:** the 3D view is mouse-only (its label says to use the 2D map for keyboard play);
no province labels, fortress/siege markers or movement arrows in 3D yet; the WebGL scene itself is
not unit-tested (jsdom), only its integration.

**Verification (actually run):** `npx vitest run` — 21 files, **148 tests passed**; `npx tsc -b`
clean; `npm run lint` 3 pre-existing warnings (none new); `npm run build` OK (main chunk ~671 kB with
the existing >500 kB warning; separate lazy `Map3D` chunk). **No manual browser check** of the 3D
view — it needs the backend and a Supabase login; please open a game, press "3D", and try clicking,
right-click orders, dragging and the wheel.

**Exact next task:** UI-C (gameplay panels), starting with a full-screen peace conference modal and
a notifications list (battles, calls, conference requests) — see §3 UI-C. Optional 3D follow-ups
(labels, fortresses, arrows) only if the user asks.

**Continuation instruction:** "Read docs/FRONTEND_OVERHAUL_PLAN.md and AGENTS.md. UI-A and UI-B
(SVG pass and optional lazy 3D view) are done (§5, sessions 2–3). Continue with UI-C as described in
§3, keeping accessible names and the `showPanel` test helper; run focused tests, then the full
frontend checks. Do not commit."

*(Session 3's next task and continuation instruction are superseded by session 4 below.)*

### Session 4 — 2026-10-22 (full-viewport campaign map and contextual windows)

**Why:** the product owner found the screen still dashboard-like (small map card, long permanent
text column). This session replaced the page structure. No gameplay rule, API or schema changed,
and no dependency was added (icons are inline SVGs in `components/icons.tsx`, Lucide-style).

**Completed:**
- **Shell (`pages/GamePage.tsx`):** the game screen no longer uses `AppShell`; `.game-shell` is
  `position: fixed; inset: 0` with the map (`main "Province map"`) as the background layer. Over it:
  `TopBar` (back link, nation flag/name, round, `ResourceBar`, turn status, peace-conference button,
  notifications bell, End turn), the left `GameToolbar`, one management window (`aside "Management
  window"`), the contextual window on the right, map orders at the bottom, battle/result in a centre
  overlay, query errors as toasts. Nothing intercepts map clicks except the conference modal.
- **State model (`GameView`):** `selectedKey` + `inspector: 'province' | 'army' | null` +
  `selectedBattalions` + `panel: PanelKey | null` + `pendingAttack` + conference-minimised and
  result-hidden flags. Clicking a province opens its window (replacing the one shown); clicking your
  army marker opens the army window and selects its ready battalions; Escape steps back one layer
  (attack prompt → battalion selection → window → management window). Server state stays in
  TanStack Query.
- **Province window (`components/ProvinceInspector.tsx`, ~360 px):** header (owner swatch, name,
  capital star, city, owner, Occupied / Under siege tags, close); stat chips (population, income,
  terrain, workshops); owner/controller line when occupied; forces line with "Forces in X"; icon
  actions with price and a hover/focus card (`CompactAction` in `components/Floating.tsx`: name,
  costs, timing, effects, the server's blockers): Buildings (Tier 1 workshop, institutions),
  Workshops (per-workshop upgrade/repair), Recruit (infantry/cavalry/artillery, one click
  recruits), Fortress (build/raise/repair, level and integrity, siege block); Economy and Neighbours
  as expandable details. Replaces `CityEconomyPanel`, `InstitutionsPanel` and `FortificationPanel`
  (deleted; their blocker texts moved to `lib/blockers.ts`).
- **Army window (`components/ArmyInspector.tsx`):** "Army in X" (or "Forces in X" when none of yours
  stand there): men, battalions, movement availability; composition chips (infantry, cavalry,
  artillery counts); icon commands: select all ready, Reinforce, Group as army, Lend to an ally,
  Disband (each explains in its title why it is disabled; Reinforce/Disband open the existing
  `BattalionManagement` in a `mode`, disbanding keeps its confirmation); a scrollable
  one-line-per-battalion list (checkbox, unit icon, type #n, status tag, men / max with a strength
  meter from the real `manpower` / `maxManpower`); lent battalions with Recall; allied/neutral and
  enemy groups read-only; destination buttons that call the same `command()` as a right-click, so
  attacks get the same confirmation. Replaces `MilitaryPanel` (deleted). **Split / merge:** the
  backend has no split or merge command; a stack is split by moving a subset of the selection
  (stated in the window) and merged with "Group as army" (regroup). No new mechanic was invented.
- **Management windows (toolbar):** Nation (summary), Economy (Treasury + "Your provinces" table that
  opens a province), Military ("Armies" list that opens an army, Your forces, Sieges), Technology,
  Diplomacy (wide), Players, Notifications (waiting diplomacy, arrears, recent battles with reports,
  conference pause), Settings (dashboard link, log out). Clicking the active icon or × closes it.
  "Sieges" is no longer a separate toolbar entry (it lives in Military); wars and peace stay inside
  Diplomacy (no duplicate entry).
- **Peace conference:** when a conference pauses the game it opens as a modal (`dialog "Peace
  conference"`, the only blocking layer) with the existing `ConferenceCard`; it can be minimised and
  reopened from the top bar; opening Diplomacy shows the same card there instead.
- **Map (`ProvinceMap.tsx`):** fills the frame (`xMidYMid meet` with the sea around it); drag, wheel
  and double-click convert pointer positions through the letterbox (`fitScale`, `clientToView`).
  Controls float at the bottom (modes left, zoom/3D centre). **Bug fixed:** your army marker sat
  inside `.unit-markers { pointer-events: none }`, so real mouse clicks fell through to the province
  (jsdom tests cannot see this); selectable markers now take pointer events. `Map3D` is unchanged
  and fills the viewport in 3D mode too.
- **Finished games:** the result card can be hidden to look at the map and brought back from the
  top bar.
- Dead CSS of the old stacked layout removed.

**Feature parity audit (old place → new place):** province facts/neighbours → province window;
workshop build/upgrade/repair → province window (Buildings, Workshops); institutions → province
window; recruitment (three types, costs, blockers) → province window (Recruit); fortress
build/raise/repair, integrity and siege state → province window (Fortress); battalion list,
selection and eligibility → army window; move/attack buttons → army window destinations, map
right-click and M; regroup → "Group as army"; lend/recall → army window; reinforce/disband (quote,
cohorts, confirmation) → army window commands; treasury → Economy; forces, upkeep and battle reports
→ Military; sieges list → Military; research, diplomacy, players → their windows; End turn and turn
status → top bar.

**Tests:** `GameLayout.test.tsx` rewritten around the shell (composition, top bar, one window at a
time and closing, map usable with a window open, switching provinces and Escape, economy list →
province, military list → army, 3D toggle, army marker → army window, layered Escape);
`test/render.tsx` gains `openProvince(name)` and `openForces(name)`; the military, reinforcement,
diplomacy, coalition (including the conference modal: open, minimise, reopen), economy, siege and
page tests were updated to the windows; hiding and showing a finished game's result was added.

**Verification (actually run):** `npx vitest run`: 21 files, **155 tests passed**; `npx tsc -b`
clean; `npm run lint`: 3 pre-existing warnings, none new; `npm run build` OK (main chunk ~693 kB with
the existing >500 kB warning; the lazy `Map3D` chunk is unchanged). Backend untouched (not re-run).
**Browser check:** a temporary local harness (deleted afterwards, never committed) rendered the
real `GamePage` with the Grand Campaign geometry from the V13 seed and an in-memory API. Headless
Chrome screenshots at **1920×1080** and **1366×768** showed: the map fills the viewport, no page
scroll (`scrollWidth/Height` equal the viewport), the province window with a hover card, the army
window with composition, commands and one-line battalion rows, a management window beside it, and
the 3D toggle working. Not checked against the real backend and Supabase; please play a turn.

**Known limitations:** the contextual window is pinned to the right edge (not placed next to the
selection); unit markers are still small at whole-map zoom; army commands explain disabled states
by title only (no hover card yet); the two research icons in the top bar look the same (the pools
are named in their titles); no keyboard shortcuts for the toolbar; narrow screens (< 1100 px) are
only lightly adjusted.

**Exact next task:** polish this shell (marker size by zoom, hover cards for army commands, toolbar
shortcuts), then UI-C (notifications feed and the remaining gameplay panels) on top of it.

**Continuation instruction:** "Read docs/FRONTEND_OVERHAUL_PLAN.md and AGENTS.md. The full-viewport
map-first shell is in place (§5, session 4). Keep the floating-window model, the accessible names
and the `showPanel` / `openProvince` / `openForces` test helpers; run focused tests, then the full
frontend checks. Do not commit."

### Session 5 — 2026-10-22 (diplomatic map perspective, focused patch)

**Request:** let the diplomatic map show any nation's relations, not only the viewer's. View-only.

**Data (no backend change):** the diplomacy overview already publishes every **active alliance**
with its kind (only unanswered proposals are private) and every **war with its participants**
(`leftRound` for nations that left). `lib/diplomacy.ts` `perspectiveOf(overview, referenceId)`
derives, for any reference nation: `SELF`, `ALLY` (normal alliance), `DEFENSIVE_ALLY`, `WAR`
(opposite sides of an active war, both still in it), `CO_BELLIGERENT` (same side, not allied —
never turned into an alliance), `NEUTRAL`. For the viewer's own nation it uses the server's
per-nation status (`nations[].status` / `allianceKind`) directly.

**Behaviour:**
- In Diplomatic mode a left-click on a province selects it as usual (the province window opens)
  **and** makes its **legal owner** (not an occupier) the reference nation; this works the same
  from the 3D view. Army markers only select armies; other map modes ignore the reference.
- A compact indicator at the top of the map ("Relations of X") with **Reset to my nation**; the
  legend names the reference ("At war with X") and shows the six categories (defensive allies in
  their own colour).
- **Authorization separation:** the reference is UI state in `GameView`
  (`diplomaticReference`, `DiplomaticView` prop of `ProvinceMap`). Orders, move targets, army
  markers, End turn and every diplomatic command keep using `relationsOf(...)` of the viewer's own
  nation; nothing else reads the reference.

**Files:** `lib/diplomacy.ts` (`Perspective`, `perspectiveOf`), `components/ProvinceMap.tsx`
(`DiplomaticView` replaces the `standing` prop; `choose()`, `PerspectiveBar`, legend labels),
`pages/GamePage.tsx` (reference state), `index.css` (indicator). Tests: `lib/perspective.test.ts`
(3: categories by kind, no alliance inferred from shared wars / ended wars / proposals / separate
peace, own view from the server's relations), `ProvinceMap.test.tsx` +6 (click sets owner as
reference and still selects, no change outside the mode or from army markers, owner not occupier,
indicator + colours + legend + reset, no reset for your own view, 3D), `GameDiplomacy.test.tsx` +1
(page: perspective switch keeps your army selection, attack targets and End turn; reset).

**Verification (actually run):** `npx vitest run` — 22 files, **165 tests passed**; `npx tsc -b`
clean; `npm run lint` 3 pre-existing warnings; `npm run build` OK. Not checked in a browser against
the real backend.

**Next:** unchanged from session 4 (shell polish, then UI-C). Do not commit without the user's
permission.

### Session 6 — 2026-10-29 (playtest feedback: targeted UX cleanup)

Targeted fixes, no redesign; the full-viewport map, inspectors and design language stay.

- **Dashboard**: form grids and fields use `minmax(0, 1fr)` and `min-width: 0`, so a long map name in
  the "Create a private game" dropdown can no longer widen its card into "Join a game"; dropdowns are
  styled like inputs. Checked at 1920×1080, 1366×768, 1050 and 820 px wide: no overlap, no horizontal
  scroll.
- **Diplomacy from the map**: the province window's owner name (the legal owner, not the occupier)
  opens that nation's diplomacy (relations, alliances, wars, actions) in the left window; the
  province stays selected and the camera does not move. The full list stays reachable ("All nations").
- **Orders**: the army window's "Move to …" / "Attack …" list is gone; right-click (or M on a focused
  province for keyboard users) is the order path, and the window explains it.
- **3D**: hidden from players (`VITE_ENABLE_3D_MAP=true` in a development build shows it); Map3D and
  three.js stay and are only downloaded when the view is opened.
- **History** window (toolbar): icon bar for past wars, finished conferences, treaties (incl. earlier
  proposals, finished tribute, answered calls), the diplomatic event log (latest 200) and battles,
  one category at a time with expandable entries. Live matters stay in Diplomacy.
- **Top bar**: population with its projected change per round (growth − siege attrition from the
  server's per-city figures; immediate changes such as recruitment are not projected); army shows
  battalions and men only (upkeep stays in the economy window and the gold tooltip).
- **Password fields** have a show/hide eye button (login, registration, account, recovery).
- **Icons**: research pools, tracks, doctrines and institutions have distinct icons; research
  categories, history categories and map modes are icon tabs with names and tooltips; the alliance
  type is a two-option segmented choice; calls to arms are one button per ally. Nation, map, player
  count and peace-term dropdowns stay (long or numeric lists).
- **Army groups** are explained (a label only; no effect on movement or combat) and re-selectable
  from their tag.

### Session 7 — 2026-11-07 (landing page and visual system refresh)

- **Landing page.** `/` now shows a public landing page (`pages/LandingPage.tsx`) to visitors and the
  dashboard to signed-in players (`Home` in `App.tsx`); protected pages still redirect to `/login`.
  The hero and the nations section draw the real Grand Campaign continent from a static snapshot
  (`landing/campaignMap.ts`, generated by `tools/mapgen/landing-atlas.mjs` from migrations V13/V22,
  because signed-out pages cannot call the API). The map code is a lazy chunk, so signed-in players
  never download it. Copy states only documented rules (README, TURN_SYSTEM, AI).
- **Visual system.** Tokens in `index.css` were replaced: warm ink ground, cinnabar accent instead of
  brass, self-hosted Spectral (display) and IBM Plex Sans (body) in `public/fonts` (OFL, licence in
  `public/fonts/OFL.txt`; the CSP's `font-src 'self'` forbids font CDNs). Hard-coded colours were
  mapped to tokens, the ⚜ glyph became an SVG mark (`components/BrandMark.tsx`, also the favicon),
  and the header, auth pages (form beside the map), buttons, inputs, alerts and badges were restyled.
  Gameplay, API and test selectors are unchanged.
- **Tests.** `LandingPage.test.tsx` covers the landing content and the `/` routing for visitors and
  players. Vitest now excludes `tests/` (Playwright end-to-end specs).
