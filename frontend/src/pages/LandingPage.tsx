import { useState } from 'react'
import { Link } from 'react-router'
import { BrandMark } from '../components/BrandMark'
import { Atlas } from '../landing/Atlas'
import { campaignMap, type AtlasNation, type AtlasProvince } from '../landing/campaignMap'
import { LegalLinks } from './LegalPage'
import '../landing/landing.css'

const TIER_LABEL: Record<AtlasNation['tier'], string> = {
  POWER: 'Great power',
  MAJOR: 'Major',
  REGIONAL: 'Regional',
  MINOR: 'Minor',
}

const TIER_ORDER: AtlasNation['tier'][] = ['POWER', 'MAJOR', 'REGIONAL', 'MINOR']

const provinceCount = (nationKey: string) => campaignMap.provinces.filter((p) => p.owner === nationKey).length

const nations = [...campaignMap.nations].sort(
  (a, b) => TIER_ORDER.indexOf(a.tier) - TIER_ORDER.indexOf(b.tier) || provinceCount(b.key) - provinceCount(a.key),
)

const PILLARS = [
  {
    verb: 'Govern',
    text: 'Every province counts its people one by one. Citizens work farms and workshops in priority order, cities grow, and your treasury is kept to the thousandth of a gold piece. Build academies and universities to unlock technology and doctrines.',
  },
  {
    verb: 'Muster',
    text: 'Infantry, cavalry and artillery are raised from a city’s own population, so every battalion has a cost at home. Reinforce them, merge them into armies, or send the soldiers back to their towns.',
  },
  {
    verb: 'Campaign',
    text: 'Battles are resolved the same way every time, shaped by terrain, rivers, flanking and who holds the front line. Fortified cities don’t fall in a turn: besiege them, and they starve.',
  },
  {
    verb: 'Negotiate',
    text: 'Declare war, sign alliances and defensive pacts, call your allies to arms. Wars end at the peace table, where provinces, reparations, tribute and whole populations change hands.',
  },
]

export function LandingPage() {
  const [hovered, setHovered] = useState<AtlasProvince | null>(null)
  const [focusNation, setFocusNation] = useState<string>(nations[0].key)
  const hoveredNation = hovered ? campaignMap.nations.find((n) => n.key === hovered.owner) : undefined
  const focused = campaignMap.nations.find((n) => n.key === focusNation)!

  return (
    <div className="landing">
      <header className="landing__nav">
        <Link to="/" className="brand" aria-label="Blaspi, home">
          <BrandMark />
          <span className="brand__name">Blaspi</span>
        </Link>
        <nav className="landing__nav-links" aria-label="Account">
          <Link to="/login" className="shell__nav-link">
            Log in
          </Link>
          <Link to="/register" className="button button--primary">
            Create account
          </Link>
        </nav>
      </header>

      <section className="hero-map" aria-labelledby="hero-title">
        <Atlas animate onHover={setHovered} className="hero-map__atlas" />
        <div className="hero-map__scrim" aria-hidden="true" />
        <div className="hero-map__copy">
          <h1 id="hero-title">Thirty rounds to remake a continent.</h1>
          <p className="hero-map__lede">
            Blaspi is a turn-based grand strategy game for two to four players. Govern your provinces, raise
            armies from your own cities and settle every war at the peace table. The computer plays every nation nobody
            chose, by the same rules as you.
          </p>
          <div className="hero-map__actions">
            <Link to="/register" className="button button--primary button--large">
              Create an account
            </Link>
            <Link to="/login" className="button button--large">
              Log in
            </Link>
          </div>
        </div>
        <div className="hero-map__readout" aria-hidden="true">
          {hovered && hoveredNation ? (
            <>
              <span className="readout__swatch" style={{ background: hoveredNation.color }} />
              <span className="readout__text">
                <strong>{hovered.name}</strong>
                <span>
                  {hoveredNation.name} · {hovered.terrain === 'MOUNTAINS' ? 'Mountains' : 'Plains'}
                </span>
              </span>
            </>
          ) : (
            <span className="readout__text">
              <strong>The Grand Campaign</strong>
              <span>
                {campaignMap.provinces.length} provinces · {campaignMap.nations.length} nations
              </span>
            </span>
          )}
        </div>
      </section>

      <section className="landing__section pillars" aria-labelledby="pillars-title">
        <div className="landing__intro">
          <h2 id="pillars-title">One round, four decisions</h2>
          <p className="muted">
            Players move one after another, and the round closes when the last of them ends their turn. Thirty rounds
            later the game is decided.
          </p>
        </div>
        <dl className="pillars__list">
          {PILLARS.map((pillar) => (
            <div key={pillar.verb} className="pillars__row">
              <dt>{pillar.verb}</dt>
              <dd>{pillar.text}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="landing__section factions" aria-labelledby="factions-title">
        <div className="factions__map">
          <Atlas highlight={focusNation} />
          <p className="factions__caption">
            <span className="readout__swatch" style={{ background: focused.color }} />
            {focused.name}: {provinceCount(focused.key)} provinces at the start
          </p>
        </div>
        <div className="factions__text">
          <h2 id="factions-title">Ten nations. Four are yours to choose.</h2>
          <p className="muted">
            The four great powers are open to players. The other six, and any power nobody takes, are run by the
            computer. It builds, recruits, fights, allies and makes peace through the same rules you do.
          </p>
          <ul className="factions__list">
            {nations.map((nation) => (
              <li key={nation.key}>
                <button
                  type="button"
                  className="faction-row"
                  aria-pressed={focusNation === nation.key}
                  onClick={() => setFocusNation(nation.key)}
                  onMouseEnter={() => setFocusNation(nation.key)}
                  onFocus={() => setFocusNation(nation.key)}
                >
                  <span className="readout__swatch" style={{ background: nation.color }} />
                  <span className="faction-row__name">{nation.name}</span>
                  <span className="faction-row__tier">
                    {TIER_LABEL[nation.tier]}
                    {nation.playable ? ' · playable' : ''}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="landing__section modes" aria-labelledby="modes-title">
        <h2 id="modes-title" className="landing__intro">
          Two ways to start a game
        </h2>
        <div className="modes__grid">
          <article className="mode">
            <h3>Private game</h3>
            <p>
              Open a lobby, invite friends or share its code, and pick your nations. Two to four players; the computer
              takes every seat that stays empty.
            </p>
          </article>
          <article className="mode">
            <h3>Quick Match</h3>
            <p>
              Join the queue and get paired with another player for an unranked game, with eight computer nations on
              the board.
            </p>
          </article>
        </div>
      </section>

      <section className="landing__cta" aria-labelledby="cta-title">
        <h2 id="cta-title">Choose a nation and take the first turn.</h2>
        <div className="hero-map__actions">
          <Link to="/register" className="button button--primary button--large">
            Create an account
          </Link>
          <Link to="/login" className="button button--large">
            Log in
          </Link>
        </div>
      </section>

      <footer className="landing__footer">
        <span className="brand">
          <BrandMark />
          <span className="brand__name">Blaspi</span>
        </span>
        <p className="muted">A fictional world. No real nations, borders or history.</p>
        <LegalLinks />
      </footer>
    </div>
  )
}
