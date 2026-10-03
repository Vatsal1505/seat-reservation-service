CREATE TABLE shows (
    id           uuid        PRIMARY KEY,
    name         text        NOT NULL,
    price_paise  bigint      NOT NULL CHECK (price_paise > 0),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id               uuid        PRIMARY KEY,
    show_id          uuid        NOT NULL REFERENCES shows (id),
    user_id          text        NOT NULL,
    idempotency_key  text        NOT NULL,
    seat_labels      text[]      NOT NULL,
    total_paise      bigint      NOT NULL CHECK (total_paise > 0),
    status           text        NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservations_idempotency UNIQUE (show_id, user_id, idempotency_key)
);

CREATE TABLE seats (
    show_id         uuid NOT NULL REFERENCES shows (id),
    seat_label      text NOT NULL,
    status          text NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    reservation_id  uuid REFERENCES reservations (id),
    user_id         text,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, seat_label),
    CONSTRAINT ck_seats_owner CHECK (
        (status = 'AVAILABLE' AND reservation_id IS NULL AND user_id IS NULL)
        OR (status <> 'AVAILABLE' AND reservation_id IS NOT NULL AND user_id IS NOT NULL)
    )
);

CREATE INDEX idx_seats_show_user ON seats (show_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_seats_reservation ON seats (reservation_id) WHERE reservation_id IS NOT NULL;
CREATE INDEX idx_reservations_held_expiry ON reservations (expires_at) WHERE status = 'HELD';
