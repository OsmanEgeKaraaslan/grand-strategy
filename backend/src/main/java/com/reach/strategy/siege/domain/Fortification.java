package com.reach.strategy.siege.domain;

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

import com.reach.strategy.siege.rules.FortressWorks;

import org.springframework.data.domain.Persistable;

/**
 * The fortress of one city. At most one project (an upgrade or a repair) runs at a time; the
 * rules that decide what may be ordered live in {@link FortressWorks}, not here.
 */
@Entity
@Table(name = "fortification")
public class Fortification implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "city_id", nullable = false, updatable = false)
	private UUID cityId;

	@Column(nullable = false)
	private int level;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private FortressWorks.Status status;

	@Column(nullable = false)
	private int integrity;

	@Column(name = "max_integrity", nullable = false)
	private int maxIntegrity;

	@Enumerated(EnumType.STRING)
	private FortressWorks.Project project;

	@Column(name = "project_target_level")
	private Integer projectTargetLevel;

	@Column(name = "project_ordered_round")
	private Integer projectOrderedRound;

	@Column(name = "project_completes_after_round")
	private Integer projectCompletesAfterRound;

	@Column(name = "project_cost_mg", nullable = false)
	private long projectCostMg;

	@Column(name = "ruleset_version", nullable = false, updatable = false)
	private String rulesetVersion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = true;

	protected Fortification() {
	}

	/** An intact fortress that exists when the game starts. */
	public static Fortification starting(UUID gameId, UUID cityId, int level, int maxIntegrity, String rulesetVersion,
			Instant now) {
		Fortification f = blank(gameId, cityId, rulesetVersion, now);
		f.level = level;
		f.integrity = maxIntegrity;
		f.maxIntegrity = maxIntegrity;
		return f;
	}

	/** A city without a fortress that orders its first level: level 0 until the project completes. */
	public static Fortification firstLevelProject(UUID gameId, UUID cityId, String rulesetVersion, Instant now) {
		return blank(gameId, cityId, rulesetVersion, now);
	}

	private static Fortification blank(UUID gameId, UUID cityId, String rulesetVersion, Instant now) {
		Fortification f = new Fortification();
		f.id = UUID.randomUUID();
		f.gameId = gameId;
		f.cityId = cityId;
		f.status = FortressWorks.Status.ACTIVE;
		f.rulesetVersion = rulesetVersion;
		f.createdAt = now;
		return f;
	}

	public void startProject(FortressWorks.Project kind, Integer targetLevel, int orderedRound, int completesAfterRound,
			long costMg) {
		if (project != null) {
			throw new IllegalStateException("Fortification " + id + " already has a project");
		}
		project = kind;
		projectTargetLevel = targetLevel;
		projectOrderedRound = orderedRound;
		projectCompletesAfterRound = completesAfterRound;
		projectCostMg = costMg;
	}

	/** Round-end completion: an upgrade raises the level, a repair restores the fortress; both to full integrity. */
	public void completeProject(int newMaxIntegrity) {
		if (project == FortressWorks.Project.UPGRADE) {
			level = projectTargetLevel;
		}
		status = FortressWorks.Status.ACTIVE;
		maxIntegrity = newMaxIntegrity;
		integrity = newMaxIntegrity;
		clearProject();
	}

	/** Artillery capture: the fortress is DAMAGED and an unfinished upgrade is lost (COMBAT_AND_SIEGE §5.6). */
	public void bombarded(int integrityLeft) {
		status = FortressWorks.Status.DAMAGED;
		integrity = Math.max(0, Math.min(integrity, integrityLeft));
		if (project == FortressWorks.Project.UPGRADE) {
			clearProject();
		}
	}

	/** A fall by starvation also ends a running upgrade (paid, not refunded). */
	public void cancelUpgrade() {
		if (project == FortressWorks.Project.UPGRADE) {
			clearProject();
		}
	}

	private void clearProject() {
		project = null;
		projectTargetLevel = null;
		projectOrderedRound = null;
		projectCompletesAfterRound = null;
		projectCostMg = 0;
	}

	public int effectiveLevel() {
		return FortressWorks.effectiveLevel(level, status);
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

	public int level() {
		return level;
	}

	public FortressWorks.Status status() {
		return status;
	}

	public int integrity() {
		return integrity;
	}

	public int maxIntegrity() {
		return maxIntegrity;
	}

	public FortressWorks.Project project() {
		return project;
	}

	public Integer projectTargetLevel() {
		return projectTargetLevel;
	}

	public Integer projectCompletesAfterRound() {
		return projectCompletesAfterRound;
	}

	public long projectCostMg() {
		return projectCostMg;
	}

	public String rulesetVersion() {
		return rulesetVersion;
	}

}
