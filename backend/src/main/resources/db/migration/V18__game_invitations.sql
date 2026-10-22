-- Phase 13A: direct invitations of friends into private lobbies (docs/DATA_MODEL.md §3.5).
--
-- PENDING → ACCEPTED (invitee joined) | DECLINED (invitee) | CANCELLED (inviter, or a block) |
-- INVALIDATED (on acceptance the lobby was full, started, gone or no longer hosted by the inviter;
-- reason says which). Invitations do not reserve seats: capacity is checked under the game row
-- lock when one is accepted. At most one pending invitation per (game, invitee).
CREATE TABLE game_invitation (
    id           uuid        PRIMARY KEY,
    game_id      uuid        NOT NULL REFERENCES game (id),
    inviter_id   uuid        NOT NULL REFERENCES player (id),
    invitee_id   uuid        NOT NULL REFERENCES player (id),
    status       varchar(12) NOT NULL,
    reason       varchar(32),
    created_at   timestamptz NOT NULL,
    responded_at timestamptz,
    CONSTRAINT game_invitation_not_self_ck CHECK (inviter_id <> invitee_id),
    CONSTRAINT game_invitation_status_ck CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'INVALIDATED')),
    CONSTRAINT game_invitation_responded_ck CHECK ((status = 'PENDING') = (responded_at IS NULL))
);

CREATE UNIQUE INDEX game_invitation_pending_uq ON game_invitation (game_id, invitee_id) WHERE status = 'PENDING';
CREATE INDEX game_invitation_invitee_idx ON game_invitation (invitee_id, status);
CREATE INDEX game_invitation_game_idx ON game_invitation (game_id, status);

ALTER TABLE game_invitation ENABLE ROW LEVEL SECURITY;
