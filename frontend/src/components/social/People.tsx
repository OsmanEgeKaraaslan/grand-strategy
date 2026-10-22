import { Link } from 'react-router'
import type { Emblem as EmblemKey } from '../../api/types'
import { Icon, type IconName } from '../icons'

const EMBLEM_ICONS: Record<EmblemKey, IconName> = {
  crown: 'crown',
  shield: 'army',
  swords: 'military',
  tower: 'fortress',
  flag: 'flag',
  scholar: 'university',
  mountain: 'mountain',
  wheat: 'plains',
}

/** A player's chosen emblem (one of the predefined ones; there are no uploaded images). */
export function EmblemBadge({ emblem, size = 16 }: { emblem: EmblemKey | undefined; size?: number }) {
  return (
    <span className="emblem" aria-hidden="true" style={{ width: size + 10, height: size + 10 }}>
      <Icon name={EMBLEM_ICONS[emblem ?? 'crown'] ?? 'crown'} size={size} />
    </span>
  )
}

/**
 * Emblem, display name and handle, linking to the public profile. Names are rendered as text, never
 * as HTML, so a name can never inject markup.
 */
export function PersonChip({
  username,
  displayName,
  emblem,
  link = true,
}: {
  username: string
  displayName: string
  emblem?: EmblemKey
  link?: boolean
}) {
  const body = (
    <>
      <EmblemBadge emblem={emblem} size={13} />
      <span className="person__names">
        <span className="person__display">{displayName}</span>
        <span className="person__handle">@{username}</span>
      </span>
    </>
  )
  return link ? (
    <Link to={`/players/${encodeURIComponent(username)}`} className="person" aria-label={`${displayName} (@${username}), view profile`}>
      {body}
    </Link>
  ) : (
    <span className="person">{body}</span>
  )
}
