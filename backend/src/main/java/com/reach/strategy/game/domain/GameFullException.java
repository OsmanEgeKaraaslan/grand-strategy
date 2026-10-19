package com.reach.strategy.game.domain;

import java.util.UUID;

public class GameFullException extends RuntimeException {

	public GameFullException(UUID gameId) {
		super("Game " + gameId + " has no free player slot");
	}

}
