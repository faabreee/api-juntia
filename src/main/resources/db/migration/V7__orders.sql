-- Inventario: "taken" = entradas reservadas (órdenes pendientes) + vendidas (órdenes pagadas).
-- Se modifica solo con UPDATE condicional atómico, nunca con lectura-y-escritura.
ALTER TABLE ticket_types ADD COLUMN taken INT NOT NULL DEFAULT 0;

CREATE TABLE orders (
    id              CHAR(36)     NOT NULL PRIMARY KEY,
    event_id        CHAR(36)     NOT NULL,
    buyer_user_id   CHAR(36)     NOT NULL,
    status          VARCHAR(20)  NOT NULL,   -- PENDING | PAID | EXPIRED | CANCELLED
    currency        CHAR(3)      NOT NULL,
    total_minor     BIGINT       NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    expires_at      DATETIME(6)  NULL,       -- solo órdenes pendientes
    paid_at         DATETIME(6)  NULL,
    closed_at       DATETIME(6)  NULL,       -- expirada o cancelada
    CONSTRAINT uq_orders_buyer_key UNIQUE (buyer_user_id, idempotency_key),
    CONSTRAINT fk_orders_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_orders_buyer FOREIGN KEY (buyer_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX ix_orders_status_expires (status, expires_at),
    INDEX ix_orders_buyer (buyer_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_items (
    id               CHAR(36)     NOT NULL PRIMARY KEY,
    order_id         CHAR(36)     NOT NULL,
    ticket_type_id   CHAR(36)     NOT NULL,
    ticket_type_name VARCHAR(120) NOT NULL,   -- foto del nombre al comprar
    quantity         INT          NOT NULL,
    unit_price_minor BIGINT       NOT NULL,   -- foto del precio al comprar
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_type  FOREIGN KEY (ticket_type_id) REFERENCES ticket_types (id) ON DELETE RESTRICT,
    INDEX ix_order_items_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
