package com.reach.strategy.game.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameRepository extends JpaRepository<Game, UUID> {

	/**
	 * {@code SELECT … FOR UPDATE} on the game row. Every lobby mutation takes this lock first, so
	 * mutations of one game are serialized (docs/TURN_SYSTEM.md §4.1).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from Game g where g.inviteCode = :inviteCode")
	Optional<Game> findByInviteCodeForUpdate(@Param("inviteCode") String inviteCode);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from Game g where g.id = :id")
	Optional<Game> findByIdForUpdate(@Param("id") UUID id);

	boolean existsByInviteCode(String inviteCode);

	/** Games the player is a member of, newest first. */
	@Query("""
			select g from Game g
			where exists (select 1 from GamePlayer m where m.gameId = g.id and m.playerId = :playerId)
			order by g.createdAt desc, g.id
			""")
	List<Game> findAllForMember(@Param("playerId") UUID playerId);

	/** Lobbies and running games the player is seated in (bounded per player, Phase 15B). */
	@Query("""
			select count(g) from Game g
			where g.status <> com.reach.strategy.game.domain.GameStatus.FINISHED
			  and exists (select 1 from GamePlayer m where m.gameId = g.id and m.playerId = :playerId)
			""")
	long countUnfinishedForMember(@Param("playerId") UUID playerId);

}
