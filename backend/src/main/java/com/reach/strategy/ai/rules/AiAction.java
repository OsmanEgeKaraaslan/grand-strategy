package com.reach.strategy.ai.rules;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One command the computer wants to issue. Every action maps one-to-one onto an existing game
 * command; the server validates it like a human's. {@link #signature()} identifies the intent so a
 * rejected action is not retried in the same turn; {@link #reason()} is the short explanation kept
 * in the decision log.
 */
public sealed interface AiAction {

	String signature();

	String reason();

	/** Whether the outcome cannot be predicted (a battle): the computer looks at the board again after it. */
	default boolean reobserve() {
		return false;
	}

	/** Major decisions are written to the decision log (development diagnostics). */
	default boolean major() {
		return false;
	}

	record BuildWorkshop(UUID cityId, int tier, String reason) implements AiAction {

		public String signature() {
			return "BUILD_WORKSHOP:" + cityId;
		}

		public boolean major() {
			return true;
		}

	}

	record UpgradeWorkshop(UUID cityId, UUID workshopId, String reason) implements AiAction {

		public String signature() {
			return "UPGRADE_WORKSHOP:" + workshopId;
		}

		public boolean major() {
			return true;
		}

	}

	record RepairWorkshop(UUID cityId, UUID workshopId, String reason) implements AiAction {

		public String signature() {
			return "REPAIR_WORKSHOP:" + workshopId;
		}

	}

	record BuildFortress(UUID cityId, int targetLevel, String reason) implements AiAction {

		public String signature() {
			return "BUILD_FORTRESS:" + cityId;
		}

		public boolean major() {
			return true;
		}

	}

	record BuildInstitution(UUID cityId, String type, String reason) implements AiAction {

		public String signature() {
			return "BUILD_INSTITUTION:" + cityId + ":" + type;
		}

		public boolean major() {
			return true;
		}

	}

	record UnlockTechnology(String track, int targetLevel, String reason) implements AiAction {

		public String signature() {
			return "UNLOCK_TECHNOLOGY:" + track + ":" + targetLevel;
		}

		public boolean major() {
			return true;
		}

	}

	record UnlockDoctrine(String doctrine, String reason) implements AiAction {

		public String signature() {
			return "UNLOCK_DOCTRINE:" + doctrine;
		}

		public boolean major() {
			return true;
		}

	}

	/** @param index which recruitment of this kind in this city this turn (keeps signatures distinct) */
	record Recruit(UUID cityId, String unitType, int index, String reason) implements AiAction {

		public String signature() {
			return "RECRUIT:" + cityId + ":" + unitType + ":" + index;
		}

		public boolean major() {
			return true;
		}

	}

	record Reinforce(UUID battalionId, int soldiers, String reason) implements AiAction {

		public String signature() {
			return "REINFORCE:" + battalionId;
		}

	}

	/** @param attack whether the target holds battalions at war with the deciding nation (a battle) */
	record Move(List<UUID> battalionIds, String from, String to, boolean attack, String reason) implements AiAction {

		public Move {
			battalionIds = List.copyOf(battalionIds);
		}

		public String signature() {
			return "MOVE:" + battalionIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(",")) + ">" + to;
		}

		public boolean reobserve() {
			return attack;
		}

		public boolean major() {
			return attack;
		}

	}

	record DeclareWar(UUID target, String reason) implements AiAction {

		public String signature() {
			return "DECLARE_WAR:" + target;
		}

		public boolean reobserve() {
			return true;
		}

		public boolean major() {
			return true;
		}

	}

	record ProposeAlliance(UUID nation, String kind, String reason) implements AiAction {

		public String signature() {
			return "PROPOSE_ALLIANCE:" + nation;
		}

		public boolean major() {
			return true;
		}

	}

	record AnswerAlliance(UUID allianceId, boolean accept, String reason) implements AiAction {

		public String signature() {
			return "ANSWER_ALLIANCE:" + allianceId;
		}

		public boolean major() {
			return true;
		}

	}

	record EndAlliance(UUID allianceId, String reason) implements AiAction {

		public String signature() {
			return "END_ALLIANCE:" + allianceId;
		}

		public boolean reobserve() {
			return true;
		}

		public boolean major() {
			return true;
		}

	}

	record CallToArms(UUID warId, UUID ally, String reason) implements AiAction {

		public String signature() {
			return "CALL_TO_ARMS:" + warId + ":" + ally;
		}

		public boolean major() {
			return true;
		}

	}

	record AnswerCall(UUID callId, boolean honour, String reason) implements AiAction {

		public String signature() {
			return "ANSWER_CALL:" + callId;
		}

		public boolean reobserve() {
			return true;
		}

		public boolean major() {
			return true;
		}

	}

	record SendGold(UUID recipient, long amountMg, String reason) implements AiAction {

		public String signature() {
			return "SEND_GOLD:" + recipient;
		}

		public boolean major() {
			return true;
		}

	}

	record RequestConference(UUID warId, String kind, String reason) implements AiAction {

		public String signature() {
			return "REQUEST_CONFERENCE:" + warId + ":" + kind;
		}

		public boolean reobserve() {
			return true;
		}

		public boolean major() {
			return true;
		}

	}

	record AnswerConference(UUID conferenceId, boolean accept, String reason) implements AiAction {

		public String signature() {
			return "ANSWER_CONFERENCE:" + conferenceId;
		}

		public boolean major() {
			return true;
		}

	}

	record SubmitDraft(UUID conferenceId, int expectedRevision, List<AiState.Term> terms, String reason)
			implements AiAction {

		public SubmitDraft {
			terms = List.copyOf(terms);
		}

		public String signature() {
			return "SUBMIT_DRAFT:" + conferenceId + ":" + expectedRevision;
		}

		public boolean major() {
			return true;
		}

	}

	record DecideDraft(UUID conferenceId, int revision, boolean approve, String reason) implements AiAction {

		public String signature() {
			return "DECIDE_DRAFT:" + conferenceId + ":" + revision;
		}

		public boolean major() {
			return true;
		}

	}

	record CancelConference(UUID conferenceId, String reason) implements AiAction {

		public String signature() {
			return "CANCEL_CONFERENCE:" + conferenceId;
		}

		public boolean major() {
			return true;
		}

	}

}
