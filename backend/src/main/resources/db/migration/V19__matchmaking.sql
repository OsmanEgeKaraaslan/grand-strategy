-- Phase 13B: the unranked Quick Match queue (docs/DATA_MODEL.md §3.6, docs/TURN_SYSTEM.md §9).
--
-- One row per search. WAITING → MATCHED (paired; matched_game_id set) | CANCELLED (by the player) |
-- EXPIRED (no heartbeat within the search timeout; never matched afterwards). A player has at most one
-- WAITING ticket per queue. Matching runs under a per-queue transaction-level advisory lock, so two
-- backends can never pair the same tickets; the unique index on (matched_game_id, player_id) and the
-- MATCHED check are the database backstops.
CREATE TABLE matchmaking_ticket (
    id                 uuid        PRIMARY KEY,
    player_id          uuid        NOT NULL REFERENCES player (id),
    queue              varchar(16) NOT NULL,
    status             varchar(12) NOT NULL,
    created_at         timestamptz NOT NULL,
    heartbeat_at       timestamptz NOT NULL,
    matched_game_id    uuid        REFERENCES game (id),
    opponent_player_id uuid        REFERENCES player (id),
    matched_at         timestamptz,
    acknowledged_at    timestamptz,
    ended_at           timestamptz,
    CONSTRAINT matchmaking_queue_ck CHECK (queue IN ('QUICK_2P')),
    CONSTRAINT matchmaking_status_ck CHECK (status IN ('WAITING', 'MATCHED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT matchmaking_matched_ck CHECK ((status = 'MATCHED') = (matched_game_id IS NOT NULL)),
    CONSTRAINT matchmaking_opponent_ck CHECK (opponent_player_id IS NULL OR opponent_player_id <> player_id)
);

CREATE UNIQUE INDEX matchmaking_waiting_uq ON matchmaking_ticket (player_id, queue) WHERE status = 'WAITING';
CREATE UNIQUE INDEX matchmaking_game_player_uq ON matchmaking_ticket (matched_game_id, player_id)
    WHERE matched_game_id IS NOT NULL;
CREATE INDEX matchmaking_queue_idx ON matchmaking_ticket (queue, created_at) WHERE status = 'WAITING';
CREATE INDEX matchmaking_player_idx ON matchmaking_ticket (player_id, created_at DESC);

ALTER TABLE matchmaking_ticket ENABLE ROW LEVEL SECURITY;
