CREATE TABLE IF NOT EXISTS reconciliation_discrepancies (
    id            IDENTITY PRIMARY KEY,
    account_last4 VARCHAR(4)     NOT NULL,
    occurred_at   VARCHAR(40)    NOT NULL,
    amount        DECIMAL(14, 2) NOT NULL,
    note          VARCHAR(500)   NOT NULL
);
