-- Correlation ids (X-Request-Id and W3C traceparent) kept across persisted hand-offs, so a
-- scheduled delivery or settlement logs and sends the ids of the request that caused it.
-- All nullable: rows written before 1.2.0, and work started without a request, have none.

-- The request that created the payment (its X-Request-Id and this service's span of it).
ALTER TABLE payments ADD COLUMN correlation_request_id varchar(128);
ALTER TABLE payments ADD COLUMN traceparent varchar(55);

-- Copied from the payment at enqueue time; sent as X-Request-Id and a child traceparent
-- with the signed result notification. (request_id stays the notification's idempotency key.)
ALTER TABLE payment_outbox ADD COLUMN correlation_request_id varchar(128);
ALTER TABLE payment_outbox ADD COLUMN traceparent varchar(55);

-- The ids of the backend request (cancel or refund) whose card-refund.requested event booked
-- the refund. (request_id stays the backend's refund idempotency key.)
ALTER TABLE refunds ADD COLUMN correlation_request_id varchar(128);
ALTER TABLE refunds ADD COLUMN trace_id varchar(32);
