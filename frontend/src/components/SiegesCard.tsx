import type { SiegeOverview } from '../api/types'
import type { NationStyle } from './ProvinceMap'

/** Active sieges with their progress, and how recent sieges ended. */
export function SiegesCard({
  sieges,
  nations,
  onSelect,
}: {
  sieges: SiegeOverview
  nations: Map<string, NationStyle>
  onSelect: (provinceKey: string) => void
}) {
  if (!sieges.fortificationsAvailable && sieges.activeSieges.length === 0 && sieges.recentSieges.length === 0) {
    return null
  }
  return (
    <section className="card sieges" aria-labelledby="sieges-title">
      <h2 id="sieges-title">Sieges</h2>
      {sieges.activeSieges.length === 0 ? (
        <p className="muted">No city is under siege.</p>
      ) : (
        <ul className="battle-list">
          {sieges.activeSieges.map((s) => (
            <li key={s.id}>
              <button type="button" className="link-button" onClick={() => onSelect(s.provinceKey)}>
                {s.cityName}
              </button>{' '}
              <span className="muted">
                besieged by {nations.get(s.besiegerNationId)?.name ?? 'an enemy'} · {s.progress}/{s.requiredProgress}
                {s.estimatedRoundsLeft !== null && ` · about ${s.estimatedRoundsLeft} rounds left`}
              </span>
            </li>
          ))}
        </ul>
      )}
      {sieges.recentSieges.length > 0 && (
        <>
          <h3>Recent results</h3>
          <ul className="battle-list">
            {sieges.recentSieges.map((s) => (
              <li key={s.id}>
                {s.cityName}:{' '}
                {s.status === 'SUCCEEDED'
                  ? `taken by ${nations.get(s.besiegerNationId)?.name ?? 'the besieger'} in round ${s.endedRound}${s.artillerySiege ? ' with artillery (fortress and a workshop damaged)' : ' by starvation'}`
                  : `siege lifted in round ${s.endedRound}`}
                <span className="muted"> · {s.populationLost.toLocaleString('en-US')} civilians lost</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
