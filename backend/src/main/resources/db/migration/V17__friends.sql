-- Phase 12B: friend requests, friendships, blocks and in-app notifications (docs/DATA_MODEL.md §3.4).

-- A request from sender to recipient. PENDING → ACCEPTED | DECLINED (recipient) | CANCELLED (sender,
-- or by a block). At most one PENDING request per unordered pair, whoever sent it: two crossed
-- requests cannot both be pending (the second sender's request accepts the first instead).
CREATE TABLE friend_request (
    id           uuid        PRIMARY KEY,
    sender_id    uuid        NOT NULL REFERENCES player (id),
    recipient_id uuid        NOT NULL REFERENCES player (id),
    status       varchar(12) NOT NULL,
    created_at   timestamptz NOT NULL,
    responded_at timestamptz,
    CONSTRAINT friend_request_not_self_ck CHECK (sender_id <> recipient_id),
    CONSTRAINT friend_request_status_ck CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    CONSTRAINT friend_request_responded_ck CHECK ((status = 'PENDING') = (responded_at IS NULL))
);

CREATE UNIQUE INDEX friend_request_pending_pair_uq ON friend_request (least(sender_id, recipient_id),
    greatest(sender_id, recipient_id)) WHERE status = 'PENDING';
CREATE INDEX friend_request_recipient_idx ON friend_request (recipient_id, status);
CREATE INDEX friend_request_sender_idx ON friend_request (sender_id, status);

-- One symmetric row per friendship, stored with the smaller id first, so a pair can only be friends
-- once however the friendship came about.
CREATE TABLE friendship (
    player_low_id  uuid        NOT NULL REFERENCES player (id),
    player_high_id uuid        NOT NULL REFERENCES player (id),
    created_at     timestamptz NOT NULL,
    PRIMARY KEY (player_low_id, player_high_id),
    CONSTRAINT friendship_order_ck CHECK (player_low_id < player_high_id)
);

CREATE INDEX friendship_high_idx ON friendship (player_high_id);

-- blocker does not want contact with blocked: no requests or invitations in either direction.
CREATE TABLE player_block (
    blocker_id uuid        NOT NULL REFERENCES player (id),
    blocked_id uuid        NOT NULL REFERENCES player (id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT player_block_not_self_ck CHECK (blocker_id <> blocked_id)
);

CREATE INDEX player_block_blocked_idx ON player_block (blocked_id);

-- Lightweight in-app notifications, one row per recipient. Polled by the recipient only; read_at marks
-- them read. kind names what happened; actor, game and reference are optional pointers.
CREATE TABLE notification (
    id              uuid        PRIMARY KEY,
    player_id       uuid        NOT NULL REFERENCES player (id),
    kind            varchar(32) NOT NULL,
    actor_player_id uuid        REFERENCES player (id),
    game_id         uuid        REFERENCES game (id),
    reference_id    uuid,
    created_at      timestamptz NOT NULL,
    read_at         timestamptz,
    CONSTRAINT notification_kind_ck CHECK (kind IN ('FRIEND_REQUEST', 'FRIEND_ACCEPTED', 'GAME_INVITATION',
        'INVITATION_ACCEPTED', 'INVITATION_DECLINED', 'MATCH_FOUND', 'GAME_STARTED', 'GAME_FINISHED'))
);

CREATE INDEX notification_player_idx ON notification (player_id, created_at DESC);
CREATE INDEX notification_unread_idx ON notification (player_id) WHERE read_at IS NULL;

ALTER TABLE friend_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE friendship ENABLE ROW LEVEL SECURITY;
ALTER TABLE player_block ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
