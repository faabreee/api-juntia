-- Comisión de la plataforma: valores por organización (null = usar los valores por defecto de la aplicación).
ALTER TABLE organizations
    ADD COLUMN platform_fee_mode        VARCHAR(20) NULL,   -- PASSED_TO_BUYER | ABSORBED
    ADD COLUMN platform_fee_bps         INT         NULL,   -- puntos básicos (500 = 5 %)
    ADD COLUMN platform_fee_fixed_minor BIGINT      NULL;

-- Las órdenes guardan su desglose: total = subtotal + comisión (si la paga el comprador).
ALTER TABLE orders
    ADD COLUMN organization_id CHAR(36)    NULL,
    ADD COLUMN subtotal_minor  BIGINT      NULL,
    ADD COLUMN fee_minor       BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN fee_mode        VARCHAR(20) NOT NULL DEFAULT 'PASSED_TO_BUYER';
UPDATE orders o JOIN events e ON e.id = o.event_id SET o.organization_id = e.organization_id, o.subtotal_minor = o.total_minor;
ALTER TABLE orders
    MODIFY organization_id CHAR(36) NOT NULL,
    MODIFY subtotal_minor  BIGINT   NOT NULL,
    ADD CONSTRAINT fk_orders_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE RESTRICT,
    ADD INDEX ix_orders_org (organization_id, created_at);

CREATE TABLE payments (
    id                  CHAR(36)     NOT NULL PRIMARY KEY,
    order_id            CHAR(36)     NOT NULL,
    provider            VARCHAR(30)  NOT NULL,
    provider_payment_id VARCHAR(100) NULL,
    status              VARCHAR(20)  NOT NULL,   -- CREATED | PENDING | SUCCEEDED | FAILED
    amount_minor        BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,
    refunded_minor      BIGINT       NOT NULL DEFAULT 0,
    failure_code        VARCHAR(100) NULL,
    failure_message     VARCHAR(255) NULL,
    idempotency_key     VARCHAR(64)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT uq_payments_order_key UNIQUE (order_id, idempotency_key),
    CONSTRAINT uq_payments_provider_id UNIQUE (provider, provider_payment_id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    INDEX ix_payments_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refunds (
    id                 CHAR(36)     NOT NULL PRIMARY KEY,
    payment_id         CHAR(36)     NOT NULL,
    order_id           CHAR(36)     NOT NULL,
    amount_minor       BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL,   -- PENDING | SUCCEEDED | FAILED
    reason             VARCHAR(30)  NOT NULL,
    note               VARCHAR(255) NULL,
    provider_refund_id VARCHAR(100) NULL,
    failure_message    VARCHAR(255) NULL,
    requested_by       CHAR(36)     NULL,       -- null = automático (pago tardío o duplicado)
    idempotency_key    VARCHAR(64)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    CONSTRAINT uq_refunds_payment_key UNIQUE (payment_id, idempotency_key),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Libro de movimientos (ADR 0008). Importes con signo; la suma por pago es lo que aún no se devolvió.
CREATE TABLE ledger_entries (
    id              CHAR(36)    NOT NULL PRIMARY KEY,
    organization_id CHAR(36)    NOT NULL,
    order_id        CHAR(36)    NOT NULL,
    payment_id      CHAR(36)    NOT NULL,
    refund_id       CHAR(36)    NULL,
    kind            VARCHAR(30) NOT NULL,   -- ORGANIZER_PAYABLE | PLATFORM_FEE
    amount_minor    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    source_key      VARCHAR(100) NOT NULL,  -- impide registrar dos veces el mismo movimiento
    created_at      DATETIME(6) NOT NULL,
    CONSTRAINT uq_ledger_source UNIQUE (source_key),
    CONSTRAINT fk_ledger_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_payment FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE RESTRICT,
    INDEX ix_ledger_org (organization_id, created_at),
    INDEX ix_ledger_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Webhooks recibidos: la restricción única (proveedor, evento) los vuelve idempotentes y deja auditoría.
CREATE TABLE webhook_events (
    id           CHAR(36)    NOT NULL PRIMARY KEY,
    provider     VARCHAR(30) NOT NULL,
    event_id     VARCHAR(100) NOT NULL,
    event_type   VARCHAR(60) NOT NULL,
    payload      MEDIUMTEXT  NOT NULL,
    outcome      VARCHAR(120) NULL,
    received_at  DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    CONSTRAINT uq_webhook_event UNIQUE (provider, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
