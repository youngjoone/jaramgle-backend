CREATE TABLE heart_products (
    code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    hearts INT NOT NULL,
    bonus_hearts INT NOT NULL DEFAULT 0,
    price INT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE billing_orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    product_code VARCHAR(64) NOT NULL REFERENCES heart_products(code),
    quantity INT NOT NULL DEFAULT 1,
    price_per_unit INT NOT NULL,
    hearts_per_unit INT NOT NULL,
    bonus_hearts_per_unit INT NOT NULL,
    total_amount INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    payment_key VARCHAR(128),
    pg_provider VARCHAR(64),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMPTZ,
    canceled_at TIMESTAMPTZ,
    metadata TEXT
);

CREATE INDEX idx_billing_orders_user ON billing_orders(user_id, requested_at DESC);

CREATE TABLE heart_wallets (
    user_id BIGINT PRIMARY KEY REFERENCES users(id),
    balance INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE heart_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    order_id BIGINT REFERENCES billing_orders(id),
    amount INT NOT NULL,
    balance_after INT NOT NULL,
    type VARCHAR(32) NOT NULL,
    description TEXT,
    metadata TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_heart_transactions_user_created_at ON heart_transactions(user_id, created_at DESC);

INSERT INTO heart_products (code, name, description, hearts, bonus_hearts, price, sort_order)
VALUES
    ('HEART_PACK_5', '하트 5개', '동화 생성용 하트 5개', 5, 0, 14000, 10),
    ('HEART_PACK_10', '하트 10개', '동화 생성용 하트 10개', 10, 1, 27000, 20),
    ('HEART_PACK_20', '하트 20개', '동화 생성용 하트 20개', 20, 4, 52000, 30);
