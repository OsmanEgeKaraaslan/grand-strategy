import type { Emblem } from '../api/types'

/** The predefined profile emblems, in display order (no uploaded images, Phase 12A). */
export const EMBLEMS: Emblem[] = ['crown', 'shield', 'swords', 'tower', 'flag', 'scholar', 'mountain', 'wheat']

export const EMBLEM_LABELS: Record<Emblem, string> = {
  crown: 'Crown',
  shield: 'Shield',
  swords: 'Crossed swords',
  tower: 'Tower',
  flag: 'Banner',
  scholar: 'Scholar',
  mountain: 'Mountain',
  wheat: 'Wheat',
}
