import { Link } from 'react-router'
import { describeError } from '../api/client'
import { useGameResult } from '../api/queries'
import { formatGold } from '../lib/gold'
import { Alert } from './Alert'
import { Spinner } from './Spinner'

const STAT_LABELS: Record<string, string> = {
  roundsPlayed: 'Rounds played',
  warsDeclared: 'Wars declared',
  peaceTreaties: 'Peace treaties',
  battlesInWars: 'Battles won in wars',
  provincesCeded: 'Provinces ceded',
  tributePaidMg: 'Tribute paid (gold)',
  nationsEliminated: 'Nations eliminated',
}

const REASON_LABELS: Record<string, string> = {
  ROUND_LIMIT: 'The last round of the game was played.',
  DOMINATION: 'Every other nation was eliminated.',
  NO_HUMAN_PLAYERS: 'No human player was left in the game.',
}

/**
 * The stored final result of a finished game: winner or draw, the final standings by legally owned
 * territory and a few statistics. The map above stays visible as the final, read-only state.
 */
export function GameResultView({ gameId }: { gameId: string }) {
  const result = useGameResult(gameId, true)
  if (result.isPending) return <Spinner label="Counting the realms…" />
  if (result.error) return <Alert>{describeError(result.error)}</Alert>
  const r = result.data

  return (
    <section className="card result" aria-labelledby="result-title">
      <h2 id="result-title">
        {r.outcome === 'DRAW' ? 'The game ended in a draw' : `${r.winnerName ?? 'A nation'} wins`}
      </h2>
      <p className="muted">
        Finished after round {r.finishedRound}. {REASON_LABELS[r.reason] ?? r.reason} Standings count legally owned
        provinces and their people; occupied land counts for its owner.
      </p>
      <table className="result__standings">
        <thead>
          <tr>
            <th scope="col">Rank</th>
            <th scope="col">Nation</th>
            <th scope="col">Player</th>
            <th scope="col">Score</th>
            <th scope="col">Provinces</th>
            <th scope="col">Population</th>
            <th scope="col">Treasury</th>
          </tr>
        </thead>
        <tbody>
          {r.standings.map((s) => (
            <tr key={s.nationId} className={s.you ? 'result__you' : undefined}>
              <td>{s.rank}</td>
              <td>
                <span className="nation-name">
                  <span className="swatch" style={{ background: s.color }} aria-hidden="true" />
                  {s.nationName}
                </span>
              </td>
              <td>
                {s.playerName}
                {s.you && <span className="tag tag--you">You</span>}
              </td>
              <td>{s.score}</td>
              <td>{s.ownedProvinces}</td>
              <td>{s.ownedPopulation.toLocaleString('en-US')}</td>
              <td>{formatGold(s.treasuryMg)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h3>Statistics</h3>
      <dl className="facts">
        {Object.entries(r.statistics).map(([key, value]) => (
          <div key={key}>
            <dt>{STAT_LABELS[key] ?? key}</dt>
            <dd>{key.endsWith('Mg') ? formatGold(value) : value.toLocaleString('en-US')}</dd>
          </div>
        ))}
      </dl>
      <Link to="/" className="button button--primary">
        Return to dashboard
      </Link>
    </section>
  )
}
