import { Link } from 'react-router'
import { describeError } from '../../api/client'
import { useMyGames } from '../../api/queries'
import type { GameSummary } from '../../api/types'
import { Alert } from '../Alert'
import { Icon } from '../icons'
import { Spinner } from '../Spinner'
import { StatusBadge } from '../StatusBadge'

function destination(game: GameSummary): string {
  return game.status === 'IN_PROGRESS' || game.status === 'FINISHED' ? `/games/${game.id}/play` : `/games/${game.id}`
}

/**
 * "My active games": every unfinished game with what is needed to resume it (map, faction, round,
 * whose turn), then the finished ones. Leaving a game never forfeits it; it waits here.
 */
export function ActiveGames({ currentGameId, title = 'My active games' }: { currentGameId?: string; title?: string }) {
  const games = useMyGames()
  const active = (games.data ?? []).filter((g) => g.status !== 'FINISHED')
  const finished = (games.data ?? []).filter((g) => g.status === 'FINISHED')
  return (
    <section className="card active-games" aria-labelledby="active-games-title">
      <h2 id="active-games-title">{title}</h2>
      {games.isPending && <Spinner label="Loading your games…" />}
      {games.error && <Alert>{describeError(games.error)}</Alert>}
      {games.data && games.data.length === 0 && (
        <p className="empty">You are not in any game yet. Start a Quick Match, create a game or join with an invitation code.</p>
      )}
      {active.length > 0 && (
        <ul className="game-list">
          {active.map((game) => (
            <GameRow key={game.id} game={game} current={game.id === currentGameId} />
          ))}
        </ul>
      )}
      {finished.length > 0 && (
        <details className="active-games__finished">
          <summary>Finished games ({finished.length})</summary>
          <ul className="game-list">
            {finished.map((game) => (
              <GameRow key={game.id} game={game} current={game.id === currentGameId} />
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}

function GameRow({ game, current }: { game: GameSummary; current: boolean }) {
  const running = game.status === 'IN_PROGRESS'
  const meta = running
    ? [
        game.mapName,
        game.currentRound ? `Round ${game.currentRound}` : null,
        `${game.humanPlayers ?? game.playerCount} human${(game.humanPlayers ?? game.playerCount) === 1 ? '' : 's'}${
          game.computerPlayers ? ` · ${game.computerPlayers} computer` : ''
        }`,
      ]
    : [game.mapName, `${game.playerCount}/${game.maxPlayers} players · ${game.youAreHost ? 'Host' : `Seat ${game.yourSeat + 1}`}`]
  return (
    <li>
      <Link to={destination(game)} className={`game-list__item ${game.yourTurn ? 'game-list__item--turn' : ''}`}>
        <span className="game-list__name">
          {game.yourNation && <span className="swatch" style={{ background: game.yourNation.color }} aria-hidden="true" />}
          {game.name}
          {current && <span className="tag tag--you">Open now</span>}
        </span>
        <span className="game-list__meta">
          {game.yourNation && <span>{game.yourNation.name} · </span>}
          {meta.filter(Boolean).join(' · ')}
        </span>
        {running && game.yourTurn && <span className="tag tag--active">Your turn</span>}
        {running && game.paused && <span className="tag">Peace conference</span>}
        {running && game.youAreEliminated && <span className="tag tag--warning">Eliminated</span>}
        <StatusBadge status={game.status} />
        {!current && (
          <span className="game-list__resume" aria-hidden="true">
            <Icon name="play" size={12} /> {running ? 'Resume' : game.status === 'FINISHED' ? 'View' : 'Open'}
          </span>
        )}
      </Link>
    </li>
  )
}
