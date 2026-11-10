-- One row per order that ever had a payment attempt. Creating a payment locks
-- the row (SELECT ... FOR UPDATE), so the "one active payment per order" check
-- and the insert are serialised across every payment-service instance. A
-- partial unique index would need WHERE support, which H2 lacks.
CREATE TABLE order_payment_locks (
    order_id bigint PRIMARY KEY
);
