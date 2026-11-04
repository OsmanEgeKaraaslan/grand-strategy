import type { GameStatus } from '../api/types'

const LABELS: Record<GameStatus, string> = {
  WAITING_FOR_PLAYERS: 'Waiting for players',
  READY: 'Ready',
  IN_PROGRESS: 'In progress',
  FINISHED: 'Finished',
  ABANDONED: 'Closed',
}

export function StatusBadge({ status }: { status: GameStatus }) {
  return <span className={`badge badge--${status.toLowerCase()}`}>{LABELS[status]}</span>
}
