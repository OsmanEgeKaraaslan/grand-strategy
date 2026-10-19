package com.reach.strategy.identity.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerRepository extends JpaRepository<Player, UUID> {

	Optional<Player> findBySupabaseUserId(UUID supabaseUserId);

	List<Player> findByIdIn(Collection<UUID> ids);

	@Query("select count(p) > 0 from Player p where lower(p.displayName) = lower(:name) and p.id <> :excludedId")
	boolean isDisplayNameTakenByOther(@Param("name") String name, @Param("excludedId") UUID excludedId);

	/**
	 * Race-free first-login provisioning: concurrent first requests of the same user cannot create
	 * two players, and a display-name collision does not fail the request. Returns 1 if inserted,
	 * 0 if any unique constraint (user id or display name) prevented the insert.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO game.player (id, supabase_user_id, display_name, created_at, updated_at)
			VALUES (:id, :supabaseUserId, :displayName, :now, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("supabaseUserId") UUID supabaseUserId,
			@Param("displayName") String displayName, @Param("now") Instant now);

}
