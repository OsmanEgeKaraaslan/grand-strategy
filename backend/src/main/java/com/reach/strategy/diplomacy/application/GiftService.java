package com.reach.strategy.diplomacy.application;

import java.util.Map;
import java.util.UUID;

import com.reach.strategy.diplomacy.domain.GiftRepository;
import com.reach.strategy.diplomacy.rules.DiplomacyRules;
import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Voluntary gold gifts (Phase 10, docs/DIPLOMACY.md §10): the active player sends gold from its own
 * treasury to another nation of the game; the recipient does not have to approve and the gift is
 * never reversed. One transaction moves exactly the amount (no gold is created), records the gift
 * and a public event; a retried request with the same Idempotency-Key replays the recorded gift.
 * Usable by any nation through the same command pipeline, so a future AI can send gold the same way.
 */
@Service
public class GiftService {

	private final GameCommands gameCommands;
	private final DiplomacyState state;
	private final GiftRepository gifts;
	private final EconomyApi economy;
	private final WorldApi world;
	private final DiplomacyService diplomacy;

	GiftService(GameCommands gameCommands, DiplomacyState state, GiftRepository gifts, EconomyApi economy,
			WorldApi world, DiplomacyService diplomacy) {
		this.gameCommands = gameCommands;
		this.state = state;
		this.gifts = gifts;
		this.economy = economy;
		this.world = world;
		this.diplomacy = diplomacy;
	}

	public DiplomacyViews.GiftResult send(CurrentPlayer me, UUID gameId, UUID recipientNationId, long amountMg,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "SEND_GOLD", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.GiftResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
				DiplomacyRules.GiftRules rules = picture.rules().gifts();
				if (rules == null) {
					throw new ApiException(HttpStatus.CONFLICT, "GIFTS_NOT_AVAILABLE",
							"This game's ruleset has no gold gifts.");
				}
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				UUID sender = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
				WorldApi.NationState recipient = snapshot.nations()
					.stream()
					.filter(n -> n.id().equals(recipientNationId))
					.findFirst()
					.orElseThrow(DiplomacyErrors::nationNotFound);
				if (recipient.id().equals(sender)) {
					throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CANNOT_TARGET_SELF",
							"A nation cannot send gold to itself.");
				}
				if (recipient.eliminated()) {
					throw new ApiException(HttpStatus.CONFLICT, "NATION_ELIMINATED", "That nation has been eliminated.");
				}
				if (amountMg <= 0 || amountMg > rules.maxMg()) {
					throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_GIFT_AMOUNT",
							"A gift must be more than 0 and at most " + rules.maxMg() / 1000 + " gold.");
				}
				// PROVISIONAL: no gifts to a nation one is at war with (configurable per ruleset).
				if (!rules.allowToEnemies() && picture.standing().atWar(sender, recipient.id())) {
					throw new ApiException(HttpStatus.CONFLICT, "GIFT_TO_ENEMY",
							"You cannot send gold to a nation you are at war with.");
				}
				if (economy.treasury(sender).treasuryMg() < amountMg) {
					throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD",
							"Your treasury does not hold that much gold.");
				}
				economy.transfer(sender, recipient.id(), amountMg);
				UUID id = UUID.randomUUID();
				gifts.insert(gameId, new GiftRepository.GiftRow(id, sender, recipient.id(), amountMg, context.round(),
						context.turnNumber(), context.now()));
				diplomacy.event(gameId, context, "GOLD_GIFT", sender, recipient.id(), null,
						Map.of("giftId", id, "amountMg", amountMg));
				return new GameCommands.Outcome<>(result(gameId, sender, id), id);
			}

			@Override
			public DiplomacyViews.GiftResult replay(GameCommands.CommandContext context, UUID resultRef) {
				UUID sender = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
				return result(gameId, sender, resultRef);
			}

		});
	}

	private DiplomacyViews.GiftResult result(UUID gameId, UUID sender, UUID giftId) {
		GiftRepository.GiftRow row = gifts.find(gameId, giftId).orElseThrow();
		return new DiplomacyViews.GiftResult(view(row), economy.treasury(sender).treasuryMg());
	}

	static DiplomacyViews.Gift view(GiftRepository.GiftRow row) {
		return new DiplomacyViews.Gift(row.id(), row.sender(), row.recipient(), row.amountMg(), row.round(),
				row.createdAt());
	}

}
