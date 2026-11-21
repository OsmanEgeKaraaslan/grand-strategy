import type { NationStyle } from './ProvinceMap'

/**
 * The battle being fought, shown the moment an attack is confirmed while the server resolves it.
 * Only public information is shown: both nations and the soldiers on the map (there is no fog of
 * war). The outcome depends on each side's private technology, so it is never guessed here: the
 * server's battle report replaces this view when it arrives.
 */
export function BattleClash({
  provinceName,
  attacker,
  defender,
  attackers,
  defenders,
}: {
  provinceName: string
  attacker: NationStyle | undefined
  defender: NationStyle | undefined
  attackers: number
  defenders: number
}) {
  return (
    <section className="card battle-clash" role="status" aria-live="polite" aria-label={`Battle of ${provinceName} in progress`}>
      <h2 className="battle-clash__title">Battle of {provinceName}</h2>
      <div className="battle-clash__field" aria-hidden="true">
        <Banner side="attacker" nation={attacker} soldiers={attackers} />
        <div className="battle-clash__clash">
          <span className="battle-clash__blade battle-clash__blade--left" />
          <span className="battle-clash__blade battle-clash__blade--right" />
          <span className="battle-clash__spark" />
        </div>
        <Banner side="defender" nation={defender} soldiers={defenders} />
      </div>
      <p className="battle-clash__line">
        {attacker?.name ?? 'Your army'} ({attackers.toLocaleString('en-US')}) attacks {defender?.name ?? 'the defenders'} (
        {defenders.toLocaleString('en-US')})…
      </p>
      <div className="battle-clash__phases" aria-hidden="true">
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <span key={i} style={{ animationDelay: `${i * 160}ms` }} />
        ))}
      </div>
    </section>
  )
}

function Banner({ side, nation, soldiers }: { side: 'attacker' | 'defender'; nation: NationStyle | undefined; soldiers: number }) {
  return (
    <div className={`battle-clash__banner battle-clash__banner--${side}`} style={{ ['--banner' as string]: nation?.color ?? '#888' }}>
      <span className="battle-clash__flag" />
      <span className="battle-clash__name">{nation?.name ?? (side === 'attacker' ? 'Attacker' : 'Defender')}</span>
      <span className="battle-clash__soldiers">{soldiers.toLocaleString('en-US')} soldiers</span>
    </div>
  )
}
