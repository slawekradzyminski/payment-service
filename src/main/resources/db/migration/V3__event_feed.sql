-- Event feed consumer state (backend GET /internal/v1/events). The backend no
-- longer calls payment-service; card refunds arrive as card-refund.requested events.
-- One cursor row per consumer: the last feed sequence that was handled.
CREATE TABLE event_cursors (
    consumer varchar(64) PRIMARY KEY,
    last_sequence bigint NOT NULL,
    updated_at timestamp(6) NOT NULL
);

-- Every handled (or skipped) event id, so a redelivered event is never applied twice.
CREATE TABLE processed_events (
    event_id uuid PRIMARY KEY,
    processed_at timestamp(6) NOT NULL
);

-- The cursor row exists before the first poll, so concurrent instances only ever lock it.
INSERT INTO event_cursors (consumer, last_sequence, updated_at) VALUES ('payment-service', 0, CURRENT_TIMESTAMP);
