package com.reach.strategy.ai.application;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.ai.rules.AiAction;
import com.reach.strategy.ai.rules.AiState;
import com.reach.strategy.diplomacy.api.DiplomacyActions;
import com.reach.strategy.diplomacy.api.DiplomacyViews;
import com.reach.strategy.economy.api.EconomyActions;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.MilitaryActions;
import com.reach.strategy.research.api.ResearchActions;
import com.reach.strategy.siege.api.SiegeActions;

import org.springframework.stereotype.Component;

/**
 * Issues one {@link AiAction} through the owning module's port — the same application service, rules
 * and command pipeline as the REST endpoint — as the computer seat's own account. Runs inside the
 * caller's transaction; a refusal surfaces as the service's {@code ApiException}.
 */
@Component
class AiExecutor {

	private final EconomyActions economy;
	private final MilitaryActions military;
	private final ResearchActions research;
	private final SiegeActions sieges;
	private final DiplomacyActions diplomacy;

	AiExecutor(EconomyActions economy, MilitaryActions military, ResearchActions research, SiegeActions sieges,
			DiplomacyActions diplomacy) {
		this.economy = economy;
		this.military = military;
		this.research = research;
		this.sieges = sieges;
		this.diplomacy = diplomacy;
	}

	void execute(CurrentPlayer actor, UUID gameId, AiAction action, UUID key) {
		switch (action) {
			case AiAction.BuildWorkshop a -> economy.buildWorkshop(actor, gameId, a.cityId(), a.tier(), key);
			case AiAction.UpgradeWorkshop a -> economy.upgradeWorkshop(actor, gameId, a.cityId(), a.workshopId(), key);
			case AiAction.RepairWorkshop a -> economy.repairWorkshop(actor, gameId, a.cityId(), a.workshopId(), key);
			case AiAction.BuildFortress a -> sieges.buildFortress(actor, gameId, a.cityId(), a.targetLevel(), key);
			case AiAction.BuildInstitution a -> research.buildInstitution(actor, gameId, a.cityId(), a.type(), key);
			case AiAction.UnlockTechnology a -> research.unlockTechnology(actor, gameId, a.track(), a.targetLevel(), key);
			case AiAction.UnlockDoctrine a -> research.unlockDoctrine(actor, gameId, a.doctrine(), key);
			case AiAction.Recruit a -> military.recruit(actor, gameId, a.cityId(), a.unitType(), key);
			case AiAction.Reinforce a -> military.reinforce(actor, gameId, a.battalionId(), a.soldiers(), key);
			case AiAction.Move a -> military.move(actor, gameId, a.battalionIds(), a.to(), key);
			case AiAction.DeclareWar a -> diplomacy.declareWar(actor, gameId, a.target(), key);
			case AiAction.ProposeAlliance a -> diplomacy.proposeAlliance(actor, gameId, a.nation(), a.kind(), key);
			case AiAction.AnswerAlliance a -> {
				if (a.accept()) {
					diplomacy.acceptAlliance(actor, gameId, a.allianceId(), key);
				}
				else {
					diplomacy.rejectAlliance(actor, gameId, a.allianceId(), key);
				}
			}
			case AiAction.EndAlliance a -> diplomacy.endAlliance(actor, gameId, a.allianceId(), key);
			case AiAction.CallToArms a -> diplomacy.callToArms(actor, gameId, a.warId(), a.ally(), key);
			case AiAction.AnswerCall a -> diplomacy.answerCall(actor, gameId, a.callId(), a.honour(), key);
			case AiAction.SendGold a -> diplomacy.sendGold(actor, gameId, a.recipient(), a.amountMg(), key);
			case AiAction.RequestConference a -> diplomacy.requestConference(actor, gameId, a.warId(), a.kind(), key);
			case AiAction.AnswerConference a -> diplomacy.answerConference(actor, gameId, a.conferenceId(), a.accept(), key);
			case AiAction.SubmitDraft a -> diplomacy.submitDraft(actor, gameId, a.conferenceId(), a.expectedRevision(),
					terms(a.terms()), key);
			case AiAction.DecideDraft a -> diplomacy.decideDraft(actor, gameId, a.conferenceId(), a.revision(), a.approve(),
					key);
			case AiAction.CancelConference a -> diplomacy.cancelConference(actor, gameId, a.conferenceId(), key);
		}
	}

	private static List<DiplomacyViews.Term> terms(List<AiState.Term> terms) {
		return terms.stream()
			.map(t -> new DiplomacyViews.Term(t.type(), t.from(), t.to(), t.provinceKey(), t.amountMg(), t.sourceCityId(),
					t.destinationCityId(), t.people(), null, null))
			.toList();
	}

}
