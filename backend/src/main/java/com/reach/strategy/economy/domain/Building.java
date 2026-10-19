package com.reach.strategy.economy.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import com.reach.strategy.economy.rules.CityEconomy;
import com.reach.strategy.economy.rules.WorkshopStatus;

import org.springframework.data.domain.Persistable;

/**
 * A building of a city — in Phase 4 always a workshop, whose {@code level} is its tier. A
 * construction project is a building in status UNDER_CONSTRUCTION, so it is persisted like any
 * building and survives restarts.
 */
@Entity
@Table(name = "building")
public class Building implements Persistable<UUID> {

	public static final String WORKSHOP = "WORKSHOP";

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "city_id", nullable = false, updatable = false)
	private UUID cityId;

	@Column(nullable = false, updatable = false)
	private String type;

	@Column(nullable = false)
	private int level;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private WorkshopStatus status;

	@Column(name = "ordered_round", nullable = false, updatable = false)
	private int orderedRound;

	/** Round at whose end the project completes (Q-E8); null for starting buildings. */
	@Column(name = "completes_after_round", updatable = false)
	private Integer completesAfterRound;

	@Column(name = "cost_paid_mg", nullable = false, updatable = false)
	private long costPaidMg;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "repair_ordered_round")
	private Integer repairOrderedRound;

	@Column(name = "repair_completes_after_round")
	private Integer repairCompletesAfterRound;

	@Column(name = "repair_cost_paid_mg", nullable = false)
	private long repairCostPaidMg;

	@Column(name = "upgrade_target_tier")
	private Integer upgradeTargetTier;

	@Column(name = "upgrade_ordered_round")
	private Integer upgradeOrderedRound;

	@Column(name = "upgrade_completes_after_round")
	private Integer upgradeCompletesAfterRound;

	@Column(name = "upgrade_cost_paid_mg", nullable = false)
	private long upgradeCostPaidMg;

	@Transient
	private boolean isNew = true;

	protected Building() {
	}

	/** A workshop that exists when the game starts (round 0, nothing paid). */
	public static Building startingWorkshop(UUID gameId, UUID cityId, int tier, Instant now) {
		return create(gameId, cityId, tier, WorkshopStatus.ACTIVE, 0, null, 0, now);
	}

	/** A paid construction project. */
	public static Building workshopProject(UUID gameId, UUID cityId, int tier, int orderedRound,
			int completesAfterRound, long costPaidMg, Instant now) {
		return create(gameId, cityId, tier, WorkshopStatus.UNDER_CONSTRUCTION, orderedRound, completesAfterRound,
				costPaidMg, now);
	}

	private static Building create(UUID gameId, UUID cityId, int tier, WorkshopStatus status, int orderedRound,
			Integer completesAfterRound, long costPaidMg, Instant now) {
		Building building = new Building();
		building.id = UUID.randomUUID();
		building.gameId = gameId;
		building.cityId = cityId;
		building.type = WORKSHOP;
		building.level = tier;
		building.status = status;
		building.orderedRound = orderedRound;
		building.completesAfterRound = completesAfterRound;
		building.costPaidMg = costPaidMg;
		building.createdAt = now;
		return building;
	}

	/** Round-end completion of a project. */
	public void complete() {
		if (status != WorkshopStatus.UNDER_CONSTRUCTION) {
			throw new IllegalStateException("Building " + id + " is not under construction");
		}
		status = WorkshopStatus.ACTIVE;
	}

	/**
	 * Artillery capture (COMBAT_AND_SIEGE §5.6): the workshop keeps its record but employs nobody
	 * until repaired; its workers return to farming through the employment calculation.
	 */
	public void damage() {
		if (status != WorkshopStatus.ACTIVE) {
			throw new IllegalStateException("Only an ACTIVE workshop can be damaged, " + id + " is " + status);
		}
		status = WorkshopStatus.DAMAGED;
		// A running upgrade is lost with the damage (paid, not refunded; PROVISIONAL, like a fortress
		// upgrade on capture): the workshop is damaged at its current tier and must be repaired first.
		clearUpgrade();
	}

	/** A paid repair (already validated); it completes at the end of {@code completesAfterRound}. */
	public void startRepair(int orderedRound, int completesAfterRound, long costMg) {
		if (status != WorkshopStatus.DAMAGED) {
			throw new IllegalStateException("Building " + id + " is not damaged");
		}
		status = WorkshopStatus.UNDER_REPAIR;
		repairOrderedRound = orderedRound;
		repairCompletesAfterRound = completesAfterRound;
		repairCostPaidMg = costMg;
	}

	/** Round-end completion of a repair. */
	public void completeRepair() {
		if (status != WorkshopStatus.UNDER_REPAIR) {
			throw new IllegalStateException("Building " + id + " is not under repair");
		}
		status = WorkshopStatus.ACTIVE;
	}

	/**
	 * A paid upgrade (already validated). The workshop stays ACTIVE and keeps producing at its
	 * current tier until the upgrade completes at the end of {@code completesAfterRound} (Q-E3).
	 */
	public void startUpgrade(int targetTier, int orderedRound, int completesAfterRound, long costMg) {
		if (status != WorkshopStatus.ACTIVE || upgradeTargetTier != null || targetTier != level + 1) {
			throw new IllegalStateException("Building " + id + " cannot be upgraded now");
		}
		upgradeTargetTier = targetTier;
		upgradeOrderedRound = orderedRound;
		upgradeCompletesAfterRound = completesAfterRound;
		upgradeCostPaidMg = costMg;
	}

	/** Round-end completion: the same workshop is now of the higher tier. */
	public void completeUpgrade() {
		if (upgradeTargetTier == null || status != WorkshopStatus.ACTIVE) {
			throw new IllegalStateException("Building " + id + " has no upgrade to complete");
		}
		level = upgradeTargetTier;
		clearUpgrade();
	}

	private void clearUpgrade() {
		upgradeTargetTier = null;
		upgradeOrderedRound = null;
		upgradeCompletesAfterRound = null;
	}

	public boolean upgrading() {
		return upgradeTargetTier != null;
	}

	public Integer upgradeTargetTier() {
		return upgradeTargetTier;
	}

	public Integer upgradeCompletesAfterRound() {
		return upgradeCompletesAfterRound;
	}

	public long upgradeCostPaidMg() {
		return upgradeCostPaidMg;
	}

	public Integer repairCompletesAfterRound() {
		return repairCompletesAfterRound;
	}

	public long repairCostPaidMg() {
		return repairCostPaidMg;
	}

	public CityEconomy.Workshop asWorkshop() {
		return new CityEconomy.Workshop(level, status);
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		isNew = false;
	}

	public UUID id() {
		return id;
	}

	public UUID gameId() {
		return gameId;
	}

	public UUID cityId() {
		return cityId;
	}

	public int tier() {
		return level;
	}

	public WorkshopStatus status() {
		return status;
	}

	public int orderedRound() {
		return orderedRound;
	}

	public Integer completesAfterRound() {
		return completesAfterRound;
	}

	public long costPaidMg() {
		return costPaidMg;
	}

	public Instant createdAt() {
		return createdAt;
	}

}
