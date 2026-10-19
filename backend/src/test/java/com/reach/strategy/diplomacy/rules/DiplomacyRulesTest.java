package com.reach.strategy.diplomacy.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.diplomacy.rules.Diplomacy.AllianceBlocker;
import com.reach.strategy.diplomacy.rules.Diplomacy.AllianceKind;
import com.reach.strategy.diplomacy.rules.Diplomacy.Pair;
import com.reach.strategy.diplomacy.rules.Diplomacy.Standing;
import com.reach.strategy.diplomacy.rules.Diplomacy.WarBlocker;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Code;
import com.reach.strategy.diplomacy.rules.PeaceTerms.PopulationTransfer;
import com.reach.strategy.diplomacy.rules.PeaceTerms.ProvinceCession;
import com.reach.strategy.diplomacy.rules.PeaceTerms.ProvinceFact;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Reparations;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Term;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Tribute;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Violation;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.siege.rules.ControlDecision;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure diplomacy rules with the ruleset v5 values: relationships for any number of nations, the
 * defensive response, war score, peace-term validation, tribute arithmetic and the round-limit
 * victory. The worked examples are those of docs/DIPLOMACY.md.
 */
class DiplomacyRulesTest {

	static final DiplomacyRules V5 = new DiplomacyRules("v5", 10, 3, 10,
			new DiplomacyRules.MilitaryAccess(true, false, true),
			new DiplomacyRules.WarScoreRules(100, 200, 5, 10, 40, 10, 30, 50), new DiplomacyRules.TributeRules(1000, 10),
			new DiplomacyRules.PopulationTransferRules(1000, 2000, 2000, 1000), new DiplomacyRules.ReparationRules(1_000_000),
			new DiplomacyRules.VictoryRules(30, 20, 1));

	static final UUID A = new UUID(0, 1);
	static final UUID B = new UUID(0, 2);
	static final UUID C = new UUID(0, 3);
	static final UUID D = new UUID(0, 4);
	static final UUID E = new UUID(0, 5);
	static final List<UUID> NATIONS = List.of(A, B, C, D, E);

	static Standing standing(Set<Pair> wars, Map<Pair, AllianceKind> alliances, Set<Pair> pending,
			Map<Pair, Integer> truces) {
		return new Standing(wars, alliances, pending, truces);
	}

	static final Standing PEACE = standing(Set.of(), Map.of(), Set.of(), Map.of());

	// --- ruleset --------------------------------------------------------------------------------

	@Nested
	class Ruleset {

		@Test
		void theV5FileLoadsWithTheDocumentedValues() throws IOException {
			try (InputStream in = getClass().getResourceAsStream("/rulesets/v5/diplomacy.json")) {
				DiplomacyRules loaded = JsonMapper.builder().build().readValue(in, DiplomacyRules.class);
				assertThat(loaded).isEqualTo(V5);
			}
		}

		@Test
		void v5ChangesNoBalanceOfEarlierPhasesOnlyTheVersion() throws IOException {
			for (String file : List.of("economy", "military", "siege", "research")) {
				String v4 = resource("/rulesets/v4/" + file + ".json").replace("\"v4\"", "\"vX\"");
				String v5 = resource("/rulesets/v5/" + file + ".json").replace("\"v5\"", "\"vX\"");
				assertThat(v5).as(file).isEqualTo(v4);
			}
		}

		private String resource(String path) throws IOException {
			try (InputStream in = getClass().getResourceAsStream(path)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}

		@Test
		void malformedValuesStopTheApplicationFromStarting() {
			assertThatThrownBy(() -> new DiplomacyRules("v9", -1, 3, 10, V5.militaryAccess(), V5.warScore(), V5.tribute(),
					V5.populationTransfer(), V5.reparations(), V5.victory()))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new DiplomacyRules("v9", 10, 3, 10, V5.militaryAccess(), V5.warScore(),
					new DiplomacyRules.TributeRules(20_000, 10), V5.populationTransfer(), V5.reparations(), V5.victory()))
				.hasMessageContaining("tribute");
			assertThatThrownBy(() -> new DiplomacyRules("v9", 10, 3, 10, V5.militaryAccess(), V5.warScore(), V5.tribute(),
					new DiplomacyRules.PopulationTransferRules(2000, 1000, 2000, 1000), V5.reparations(), V5.victory()))
				.hasMessageContaining("population");
			assertThatThrownBy(() -> new DiplomacyRules("v9", 10, 3, 10, V5.militaryAccess(),
					new DiplomacyRules.WarScoreRules(0, 200, 5, 10, 40, 10, 30, 50), V5.tribute(), V5.populationTransfer(),
					V5.reparations(), V5.victory()))
				.hasMessageContaining("war score");
			assertThatThrownBy(() -> new DiplomacyRules("v9", 10, 3, 10, null, V5.warScore(), V5.tribute(),
					V5.populationTransfer(), V5.reparations(), V5.victory()))
				.hasMessageContaining("section");
		}

	}

	// --- relationships --------------------------------------------------------------------------

	@Nested
	class Relationships {

		@Test
		void pairsAreUnorderedAndNeverTheSameNationTwice() {
			assertThat(Pair.of(A, B)).isEqualTo(Pair.of(B, A));
			assertThat(Pair.of(B, A).a()).isEqualTo(A);
			assertThat(Pair.of(A, B).other(A)).isEqualTo(B);
			assertThatThrownBy(() -> Pair.of(A, A)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> Pair.of(A, B).other(C)).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void atPeaceAnyNationMayDeclareWarOnAnyOther() {
			for (UUID x : NATIONS) {
				for (UUID y : NATIONS) {
					List<WarBlocker> blockers = Diplomacy.warBlockers(x, y, NATIONS, PEACE, 1);
					assertThat(blockers).isEqualTo(x.equals(y) ? List.of(WarBlocker.SELF) : List.of());
				}
			}
		}

		@Test
		void noDuplicateWarNoWarOnAnAllyNoWarDuringATruce() {
			Standing s = standing(Set.of(Pair.of(A, B)), Map.of(Pair.of(A, C), AllianceKind.NORMAL), Set.of(),
					Map.of(Pair.of(A, D), 12));
			assertThat(Diplomacy.warBlockers(A, B, NATIONS, s, 5)).containsExactly(WarBlocker.ALREADY_AT_WAR);
			assertThat(Diplomacy.warBlockers(B, A, NATIONS, s, 5)).containsExactly(WarBlocker.ALREADY_AT_WAR);
			assertThat(Diplomacy.warBlockers(A, C, NATIONS, s, 5)).containsExactly(WarBlocker.ALLIED);
			assertThat(Diplomacy.warBlockers(D, A, NATIONS, s, 12)).as("last truce round").containsExactly(WarBlocker.TRUCE);
			assertThat(Diplomacy.warBlockers(D, A, NATIONS, s, 13)).as("truce over").isEmpty();
			assertThat(Diplomacy.warBlockers(A, new UUID(9, 9), NATIONS, s, 5)).containsExactly(WarBlocker.UNKNOWN_NATION);
		}

		@Test
		void noAllianceWithAnEnemyAndNoContradictoryAlliances() {
			Standing s = standing(Set.of(Pair.of(A, B), Pair.of(C, D)), Map.of(Pair.of(A, E), AllianceKind.DEFENSIVE),
					Set.of(Pair.of(B, C)), Map.of());
			assertThat(Diplomacy.allianceBlockers(A, B, NATIONS, s, true)).containsExactly(AllianceBlocker.AT_WAR);
			assertThat(Diplomacy.allianceBlockers(A, E, NATIONS, s, true)).containsExactly(AllianceBlocker.ALREADY_ALLIED);
			assertThat(Diplomacy.allianceBlockers(B, C, NATIONS, s, true)).containsExactly(AllianceBlocker.ALREADY_PROPOSED);
			assertThat(Diplomacy.allianceBlockers(C, B, NATIONS, s, false)).as("accepting the pending one").isEmpty();
			// E is A's ally and A fights B: E cannot also ally with B.
			assertThat(Diplomacy.allianceBlockers(E, B, NATIONS, s, true)).containsExactly(AllianceBlocker.ENEMY_OF_ALLY);
			assertThat(Diplomacy.allianceBlockers(B, E, NATIONS, s, true)).containsExactly(AllianceBlocker.ENEMY_OF_ALLY);
			assertThat(Diplomacy.allianceBlockers(A, A, NATIONS, s, true)).containsExactly(AllianceBlocker.SELF);
		}

		@Test
		void theDefensiveResponseBringsTheDefendersAlliesInDeterministically() {
			// B is attacked by A. C (defensive) and D (normal) are B's allies; E is allied with both A and B.
			Standing s = standing(Set.of(),
					Map.of(Pair.of(B, D), AllianceKind.NORMAL, Pair.of(B, C), AllianceKind.DEFENSIVE, Pair.of(B, E),
							AllianceKind.DEFENSIVE, Pair.of(A, E), AllianceKind.NORMAL),
					Set.of(), Map.of());
			assertThat(Diplomacy.defensiveJoiners(A, B, s, 4)).containsExactly(C, D);
		}

		@Test
		void allyInTruceWithTheAttackerOrAlreadyFightingItStaysOut() {
			Standing s = standing(Set.of(Pair.of(A, D)),
					Map.of(Pair.of(B, C), AllianceKind.DEFENSIVE, Pair.of(B, D), AllianceKind.NORMAL), Set.of(),
					Map.of(Pair.of(A, C), 6));
			assertThat(Diplomacy.defensiveJoiners(A, B, s, 6)).isEmpty();
			assertThat(Diplomacy.defensiveJoiners(A, B, s, 7)).containsExactly(C);
		}

		@Test
		void nationsFightingOnTheSameSideCannotTurnOnEachOther() {
			// A and C fight together against B; D is allied with C and attacked by A.
			Standing s = new Standing(Set.of(Pair.of(A, B), Pair.of(C, B)), Map.of(Pair.of(C, D), AllianceKind.NORMAL),
					Set.of(), Map.of(), Set.of(Pair.of(A, C)));
			assertThat(Diplomacy.warBlockers(A, C, NATIONS, s, 3)).containsExactly(WarBlocker.FIGHTING_TOGETHER);
			assertThat(Diplomacy.warBlockers(C, A, NATIONS, s, 3)).containsExactly(WarBlocker.FIGHTING_TOGETHER);
			assertThat(Diplomacy.defensiveJoiners(A, D, s, 3)).as("C will not attack its own co-belligerent").isEmpty();
			assertThat(Diplomacy.defensiveJoiners(E, D, s, 3)).containsExactly(C);
		}

		@Test
		void anAllianceCannotEndWhileItsMembersFightTogether() {
			assertThat(Diplomacy.terminationBlockers(true, false)).isEmpty();
			assertThat(Diplomacy.terminationBlockers(true, true)).containsExactly(Diplomacy.TerminationBlocker.FIGHTING_TOGETHER);
			assertThat(Diplomacy.terminationBlockers(false, false)).containsExactly(Diplomacy.TerminationBlocker.NOT_ACTIVE);
		}

		@Test
		void proposalsExpireAfterTheConfiguredRounds() {
			assertThat(Diplomacy.expires(4, 6, V5)).isFalse();
			assertThat(Diplomacy.expires(4, 7, V5)).isTrue();
		}

		@Test
		void onlyNormalAlliancesGrantMilitaryAccessInV5() {
			assertThat(V5.militaryAccess().grantedBy(AllianceKind.NORMAL)).isTrue();
			assertThat(V5.militaryAccess().grantedBy(AllianceKind.DEFENSIVE)).isFalse();
		}

	}

	// --- hostility as the military module sees it ------------------------------------------------

	@Nested
	class Access {

		@Test
		void ownEnemyAndGrantedTerritoryIsOpenNeutralTerritoryIsClosed() {
			Hostility.Relations r = new Hostility.Relations(false, Set.of(Hostility.Pair.of(A, B)),
					Set.of(new Hostility.Access(C, A)));
			assertThat(r.mayEnter(A, A, A)).as("own").isTrue();
			assertThat(r.mayEnter(A, A, B)).as("own, occupied by the enemy").isTrue();
			assertThat(r.mayEnter(A, B, B)).as("enemy at war").isTrue();
			assertThat(r.mayEnter(A, C, C)).as("ally granting access").isTrue();
			assertThat(r.mayEnter(C, A, A)).as("access is directed").isFalse();
			assertThat(r.mayEnter(A, D, D)).as("neutral").isFalse();
			assertThat(r.atWar(A, B)).isTrue();
			assertThat(r.atWar(B, A)).isTrue();
			assertThat(r.atWar(A, A)).isFalse();
			assertThat(r.atWar(A, C)).isFalse();
		}

		@Test
		void olderRulesetsKeepPermanentWarEverywhere() {
			Hostility.Relations r = Hostility.Relations.TOTAL_WAR;
			assertThat(r.atWar(A, B)).isTrue();
			assertThat(r.atWar(A, A)).isFalse();
			assertThat(r.mayEnter(A, D, D)).isTrue();
		}

		@Test
		void occupationAndSiegesNeedWarWithTheController() {
			var atWar = (java.util.function.BiPredicate<UUID, UUID>) (x, y) -> Set.of(Pair.of(A, B)).contains(Pair.of(x, y));
			// An ally standing in a province changes nothing; an enemy alone occupies or besieges.
			assertThat(ControlDecision.decide(B, Set.of(C), 0, null, (x, y) -> !x.equals(y) && atWar.test(x, y)))
				.isEqualTo(new ControlDecision.None());
			assertThat(ControlDecision.decide(B, Set.of(A), 0, null, (x, y) -> !x.equals(y) && atWar.test(x, y)))
				.isEqualTo(new ControlDecision.Occupy(A));
			assertThat(ControlDecision.decide(B, Set.of(A, C), 1, null, (x, y) -> !x.equals(y) && atWar.test(x, y)))
				.as("a neutral bystander does not stop a siege")
				.isEqualTo(new ControlDecision.StartSiege(A));
			assertThat(ControlDecision.decide(B, Set.of(A, B), 1, null, (x, y) -> !x.equals(y) && atWar.test(x, y)))
				.isEqualTo(new ControlDecision.None());
			// After peace the besieger is no longer hostile: the siege is lifted even if it stays.
			assertThat(ControlDecision.decide(B, Set.of(A), 1, A, (x, y) -> false)).isEqualTo(new ControlDecision.LiftSiege());
			assertThat(ControlDecision.decide(B, Set.of(A), 1, A, (x, y) -> !x.equals(y) && atWar.test(x, y)))
				.isEqualTo(new ControlDecision.ContinueSiege());
		}

	}

	// --- war score ------------------------------------------------------------------------------

	@Nested
	class Score {

		@Test
		void tinyBattlesScoreNothingAndOneBattleIsCapped() {
			assertThat(WarScore.battlePoints(199, V5.warScore())).isZero();
			assertThat(WarScore.battlePoints(200, V5.warScore())).isEqualTo(1);
			assertThat(WarScore.battlePoints(999, V5.warScore())).as("floor(999 × 5 / 1000)").isEqualTo(4);
			assertThat(WarScore.battlePoints(1500, V5.warScore())).isEqualTo(7);
			assertThat(WarScore.battlePoints(50_000, V5.warScore())).isEqualTo(10);
		}

		/** docs/DIPLOMACY.md §4 worked example. */
		@Test
		void workedExample() {
			// Varn won battles worth 7 + 4 points and one siege; it occupies 12,000 of Ostra's 46,000 people.
			WarScore.SideFacts varn = new WarScore.SideFacts(11, 10, 12_000, 46_000);
			// Ostra won one battle worth 3 points and occupies nothing.
			WarScore.SideFacts ostra = new WarScore.SideFacts(3, 0, 0, 44_000);
			WarScore.Score score = WarScore.of(varn, ostra, V5.warScore());
			assertThat(score.attacker()).isEqualTo(new WarScore.SideScore(11, 10, 13, 34));
			assertThat(score.defender()).isEqualTo(new WarScore.SideScore(3, 0, 0, 3));
			assertThat(score.net()).isEqualTo(31);
		}

		@Test
		void everyComponentIsCappedAndTheTotalBounded() {
			WarScore.SideFacts all = new WarScore.SideFacts(1_000, 1_000, 10, 10);
			WarScore.Score score = WarScore.of(all, new WarScore.SideFacts(0, 0, 0, 0), V5.warScore());
			assertThat(score.attacker()).isEqualTo(new WarScore.SideScore(40, 30, 50, 100));
			assertThat(score.defender().total()).isZero();
			assertThat(score.net()).isEqualTo(100);
		}

		@Test
		void scoreStaysWithinBoundsForEveryCombinationAndIsDeterministic() {
			long[] points = { 0, 1, 39, 40, 41, 500 };
			long[] populations = { 0, 1, 999, 46_000 };
			for (long battles : points) {
				for (long sieges : points) {
					for (long enemy : populations) {
						for (long occupied : populations) {
							if (occupied > enemy) {
								continue;
							}
							WarScore.SideFacts facts = new WarScore.SideFacts(battles, sieges, occupied, enemy);
							WarScore.Score s = WarScore.of(facts, facts, V5.warScore());
							assertThat(s.attacker().total()).isBetween(0, 100);
							assertThat(s.net()).isZero();
							assertThat(WarScore.of(facts, facts, V5.warScore())).isEqualTo(s);
						}
					}
				}
			}
		}

		@Test
		void inconsistentFactsAreRejected() {
			assertThatThrownBy(() -> new WarScore.SideFacts(0, 0, 10, 5)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new WarScore.SideFacts(-1, 0, 0, 5)).isInstanceOf(IllegalArgumentException.class);
		}

	}

	// --- peace terms ----------------------------------------------------------------------------

	@Nested
	class Terms {

		static final UUID WINNER = A;
		static final UUID LOSER = B;
		static final UUID W_CITY = new UUID(1, 1);
		static final UUID L_CITY = new UUID(1, 2);
		static final UUID L_CAPITAL = new UUID(1, 3);
		static final UUID SMALL = new UUID(1, 4);

		static final PeaceTerms.Facts FACTS = new PeaceTerms.Facts(WINNER, LOSER,
				List.of(new ProvinceFact("elmsford", WINNER, W_CITY, 8000, false),
						new ProvinceFact("sablewater", LOSER, L_CITY, 9000, false),
						new ProvinceFact("ostrava", LOSER, L_CAPITAL, 12000, true),
						new ProvinceFact("marrowfield", LOSER, SMALL, 5000, false)),
				Map.of(WINNER, 50_000L, LOSER, 120_000L));

		static List<Code> codes(List<Term> terms) {
			return PeaceTerms.validate(terms, FACTS, V5).stream().map(Violation::code).toList();
		}

		@Test
		void aWhitePeaceAndATypicalSettlementAreValid() {
			assertThat(codes(List.of())).isEmpty();
			assertThat(codes(List.of(new ProvinceCession(LOSER, WINNER, "sablewater"),
					new Reparations(LOSER, WINNER, 120_000), new Tribute(LOSER, WINNER),
					new PopulationTransfer(LOSER, WINNER, SMALL, W_CITY, 1000)))).isEmpty();
		}

		@Test
		void provincesMustBeOwnedByTheGiverOnceAndNeverACapital() {
			assertThat(codes(List.of(new ProvinceCession(LOSER, WINNER, "elmsford")))).containsExactly(Code.PROVINCE_NOT_OWNED);
			assertThat(codes(List.of(new ProvinceCession(LOSER, WINNER, "nowhere")))).containsExactly(Code.PROVINCE_NOT_FOUND);
			assertThat(codes(List.of(new ProvinceCession(LOSER, WINNER, "sablewater"),
					new ProvinceCession(LOSER, WINNER, "sablewater")))).containsExactly(Code.PROVINCE_DUPLICATE);
			assertThat(codes(List.of(new ProvinceCession(LOSER, WINNER, "ostrava")))).containsExactly(Code.CAPITAL_NOT_CEDABLE);
		}

		@Test
		void goldIsPositiveBoundedAndCoveredByTheTreasuryInTotal() {
			assertThat(codes(List.of(new Reparations(LOSER, WINNER, 0)))).containsExactly(Code.AMOUNT_NOT_POSITIVE);
			assertThat(codes(List.of(new Reparations(LOSER, WINNER, 1_000_001)))).containsExactly(Code.AMOUNT_TOO_LARGE);
			assertThat(codes(List.of(new Reparations(LOSER, WINNER, 120_001)))).containsExactly(Code.INSUFFICIENT_GOLD);
			assertThat(codes(List.of(new Reparations(LOSER, WINNER, 60_000), new Reparations(LOSER, WINNER, 60_001))))
				.containsExactly(Code.INSUFFICIENT_GOLD);
		}

		@Test
		void onlyTheTwoLeadersTakePartAndNeverTowardsThemselves() {
			assertThat(codes(List.of(new Reparations(C, WINNER, 1)))).containsExactly(Code.NOT_A_PARTY);
			assertThat(codes(List.of(new Reparations(LOSER, LOSER, 1)))).containsExactly(Code.SAME_NATION);
			assertThat(codes(List.of(new Tribute(LOSER, WINNER), new Tribute(WINNER, LOSER))))
				.containsExactly(Code.TRIBUTE_DUPLICATE);
		}

		@Test
		void populationTransfersAreBoundedAndKeepTheSourceAlive() {
			assertThat(codes(List.of(new PopulationTransfer(LOSER, WINNER, L_CITY, W_CITY, 999))))
				.containsExactly(Code.PEOPLE_OUT_OF_RANGE);
			assertThat(codes(List.of(new PopulationTransfer(LOSER, WINNER, L_CITY, W_CITY, 2001))))
				.containsExactly(Code.PEOPLE_OUT_OF_RANGE);
			// Marrowfield has 5,000: at most 20% = 1,000 may leave.
			assertThat(codes(List.of(new PopulationTransfer(LOSER, WINNER, SMALL, W_CITY, 1001))))
				.containsExactly(Code.SHARE_EXCEEDED);
			assertThat(codes(List.of(new PopulationTransfer(LOSER, WINNER, L_CITY, W_CITY, 1000),
					new PopulationTransfer(LOSER, WINNER, L_CITY, W_CITY, 1000)))).containsExactly(Code.SHARE_EXCEEDED);
			assertThat(codes(List.of(new PopulationTransfer(LOSER, WINNER, W_CITY, L_CITY, 1000))))
				.containsExactly(Code.SOURCE_NOT_OWNED);
			assertThat(codes(List.of(new PopulationTransfer(LOSER, WINNER, L_CITY, L_CAPITAL, 1000))))
				.containsExactly(Code.DESTINATION_NOT_OWNED);
			assertThat(codes(List.of(new PopulationTransfer(LOSER, WINNER, L_CITY, L_CITY, 1000))))
				.containsExactly(Code.SAME_CITY);
			assertThat(codes(List.of(new PopulationTransfer(LOSER, WINNER, new UUID(7, 7), W_CITY, 1000))))
				.containsExactly(Code.CITY_NOT_FOUND);
		}

		@Test
		void aPopulationTransferMayTargetACityCededByTheSameTreatyButNotLeaveOne() {
			assertThat(codes(List.of(new ProvinceCession(LOSER, WINNER, "sablewater"),
					new PopulationTransfer(LOSER, WINNER, SMALL, L_CITY, 1000)))).isEmpty();
			assertThat(codes(List.of(new ProvinceCession(LOSER, WINNER, "sablewater"),
					new PopulationTransfer(LOSER, WINNER, L_CITY, W_CITY, 1000)))).containsExactly(Code.SOURCE_CEDED);
		}

		@Test
		void theMinimumResidualPopulationIsEnforced() {
			DiplomacyRules strict = new DiplomacyRules("v9", 10, 3, 10, V5.militaryAccess(), V5.warScore(), V5.tribute(),
					new DiplomacyRules.PopulationTransferRules(1000, 2000, 10_000, 8_500), V5.reparations(), V5.victory());
			assertThat(PeaceTerms.validate(List.of(new PopulationTransfer(LOSER, WINNER, L_CITY, W_CITY, 1000)), FACTS,
					strict)).extracting(Violation::code).containsExactly(Code.RESIDUAL_TOO_LOW);
		}

		@Test
		void tooManyTermsAreRejectedAsAWhole() {
			List<Term> terms = new ArrayList<>();
			for (int i = 0; i < 11; i++) {
				terms.add(new Reparations(LOSER, WINNER, 1));
			}
			assertThat(PeaceTerms.validate(terms, FACTS, V5)).containsExactly(new Violation(-1, Code.TOO_MANY_TERMS));
		}

		@Test
		void settlementOrderIsGoldCessionsPopulationTribute() {
			Term tribute = new Tribute(LOSER, WINNER);
			Term people = new PopulationTransfer(LOSER, WINNER, SMALL, L_CITY, 1000);
			Term province = new ProvinceCession(LOSER, WINNER, "sablewater");
			Term gold = new Reparations(LOSER, WINNER, 5);
			assertThat(PeaceTerms.settlementOrder(List.of(tribute, people, province, gold)))
				.containsExactly(gold, province, people, tribute);
		}

		@Test
		void tributeIsTenPercentOfGrossIncomeFlooredAndNeverMoreThanTheTreasury() {
			assertThat(PeaceTerms.tributeDue(38_000, V5.tribute())).isEqualTo(3_800);
			assertThat(PeaceTerms.tributeDue(38_009, V5.tribute())).as("floor to milligold").isEqualTo(3_800);
			assertThat(PeaceTerms.tributeDue(0, V5.tribute())).isZero();
			assertThat(PeaceTerms.tributePaid(3_800, 10_000)).isEqualTo(3_800);
			assertThat(PeaceTerms.tributePaid(3_800, 1_200)).as("shortfall: pays what it has").isEqualTo(1_200);
			assertThat(PeaceTerms.tributePaid(3_800, 0)).isZero();
		}

	}

	// --- victory --------------------------------------------------------------------------------

	@Nested
	class Victories {

		@Test
		void theGameEndsAfterTheLastRound() {
			assertThat(Victory.due(29, V5.victory())).isFalse();
			assertThat(Victory.due(30, V5.victory())).isTrue();
		}

		@Test
		void territoryCountsMostAndTheHighestScoreWins() {
			// 7 provinces with 46,000 people: 140 + 46 = 186; 5 provinces with 51,999 people: 100 + 51 = 151.
			Victory.Outcome outcome = Victory.rank(List.of(new Victory.NationFacts(B, 5, 51_999),
					new Victory.NationFacts(A, 7, 46_000)), V5.victory());
			assertThat(outcome.winnerNationId()).isEqualTo(A);
			assertThat(outcome.standings()).containsExactly(new Victory.Ranked(A, 1, 186, 7, 46_000),
					new Victory.Ranked(B, 2, 151, 5, 51_999));
		}

		@Test
		void equalTopScoresAreADrawAndShareTheRank() {
			Victory.Outcome outcome = Victory.rank(List.of(new Victory.NationFacts(B, 6, 45_000),
					new Victory.NationFacts(A, 6, 45_500), new Victory.NationFacts(C, 1, 0)), V5.victory());
			assertThat(outcome.draw()).isTrue();
			assertThat(outcome.standings()).extracting(Victory.Ranked::rank).containsExactly(1, 1, 3);
			assertThat(outcome.standings()).extracting(Victory.Ranked::nationId).containsExactly(A, B, C);
		}

		@Test
		void aNationWithoutTerritoryStillHasAStanding() {
			Victory.Outcome outcome = Victory.rank(List.of(new Victory.NationFacts(A, 12, 90_000),
					new Victory.NationFacts(B, 0, 0)), V5.victory());
			assertThat(outcome.standings()).hasSize(2);
			assertThat(outcome.standings().get(1).score()).isZero();
		}

	}

}
