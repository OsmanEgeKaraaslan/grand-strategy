package com.reach.strategy.identity.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Read access to public player information for other modules. */
public interface PlayerDirectory {

	/** Display names by player id; unknown ids are absent from the result. */
	Map<UUID, String> displayNames(Collection<UUID> playerIds);

}
