import type {
  ConstructionBlocker,
  DisbandBlocker,
  Eligibility,
  FortressBlocker,
  RecruitBlocker,
  ReinforceBlocker,
  WorkshopRepairBlocker,
  WorkshopUpgradeBlocker,
} from '../api/types'

/** Player-facing reasons for the server's blocker codes. The server decides; these only explain. */
export const CONSTRUCTION_BLOCKER_TEXT: Record<ConstructionBlocker, string> = {
  NOT_YOUR_TURN: 'You can build on your turn.',
  NOT_OWNER: 'Only the owning nation can build here.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  TIER_LOCKED: 'This tier needs civilian technology.',
  WORKSHOP_LIMIT_REACHED: 'Workshop limit reached.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

export const WORKSHOP_REPAIR_BLOCKER_TEXT: Record<WorkshopRepairBlocker, string> = {
  NOT_YOUR_TURN: 'You can repair on your turn.',
  NOT_OWNER: 'Only the owning nation can repair here.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  NOT_DAMAGED: 'Nothing to repair.',
  NOT_AVAILABLE_IN_RULESET: 'This game has no repairs.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

export const WORKSHOP_UPGRADE_BLOCKER_TEXT: Record<WorkshopUpgradeBlocker, string> = {
  NOT_YOUR_TURN: 'You can upgrade on your turn.',
  NOT_OWNER: 'Only the owning nation can upgrade here.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  TECHNOLOGY_REQUIRED: 'Requires the civilian technology that unlocks Tier 2.',
  NOT_ELIGIBLE: 'This workshop cannot be upgraded now.',
  NOT_AVAILABLE_IN_RULESET: 'This game has no upgrades.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

export const INSTITUTION_BLOCKER_TEXT: Record<string, string> = {
  NOT_YOUR_TURN: 'You can build on your turn.',
  NOT_OWNER: 'Only the owning nation can build here.',
  OCCUPIED: 'Occupied by another nation.',
  BESIEGED: 'The city is under siege.',
  ALREADY_EXISTS: 'Already built or under construction.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

export const FORTRESS_BLOCKER_TEXT: Record<FortressBlocker, string> = {
  NOT_YOUR_TURN: 'You can order fortress work on your turn.',
  NOT_OWNER: 'Only the nation that legally owns this city can fortify it.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  PROJECT_IN_PROGRESS: 'Another fortress project is under way.',
  FORTRESS_DAMAGED: 'Repair the fortress first.',
  NOT_DAMAGED: 'The fortress is intact.',
  MAX_LEVEL_REACHED: 'Already at the highest level.',
  NOT_AVAILABLE_IN_RULESET: 'This game has no fortifications.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

export const RECRUIT_BLOCKER_TEXT: Record<RecruitBlocker, string> = {
  NOT_YOUR_TURN: 'You can recruit on your turn.',
  NOT_OWNER: 'Only the owning nation can recruit here.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_POPULATION: 'Not enough civilians left in this city.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

export const REINFORCE_BLOCKER_TEXT: Record<ReinforceBlocker, string> = {
  NOT_YOUR_TURN: 'You can reinforce on your turn.',
  AT_FULL_STRENGTH: 'Already at full strength.',
  EXCEEDS_MAXIMUM_MANPOWER: 'A battalion cannot exceed its maximum manpower.',
  TURN_LIMIT_REACHED: 'This battalion has received the maximum of 500 replacements this turn.',
  NOT_OWNED_CITY: 'Only in a city your nation owns — move the battalion home first.',
  OCCUPIED: 'This city is occupied by another nation.',
  BESIEGED: 'This city is under siege.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_POPULATION: 'Not enough civilians left in this city.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

export const DISBAND_BLOCKER_TEXT: Record<DisbandBlocker, string> = {
  NOT_YOUR_TURN: 'You can disband on your turn.',
  NOT_FRIENDLY_TERRITORY: 'Only in a province your nation controls.',
  BESIEGED: 'Not inside a province under siege.',
}

export const ELIGIBILITY_TEXT: Record<Eligibility, string> = {
  READY: 'Ready',
  RECRUITED_THIS_TURN: 'Recruited this turn — defends, cannot move yet',
  ALREADY_MOVED: 'Moved this turn',
  REINFORCED_THIS_TURN: 'Reinforced this turn — defends, cannot move',
}
