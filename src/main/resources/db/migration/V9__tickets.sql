-- Boleto nominativo: el comprador registra el nombre del asistente de cada boleto (opción del organizador).
ALTER TABLE ticket_types ADD COLUMN nominative BOOLEAN NOT NULL DEFAULT FALSE;

-- Un boleto por cada entrada comprada. Se emite cuando la orden queda pagada (evento de dominio OrderPaid).
CREATE TABLE tickets (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    order_id       CHAR(36)     NOT NULL,
    order_item_id  CHAR(36)     NOT NULL,
    seq            INT          NOT NULL,          -- 1..cantidad dentro de la línea de la orden
    event_id       CHAR(36)     NOT NULL,
    ticket_type_id CHAR(36)     NOT NULL,
    owner_user_id  CHAR(36)     NOT NULL,
    code           VARCHAR(12)  NOT NULL,          -- código corto para búsqueda manual en puerta
    status         VARCHAR(10)  NOT NULL,          -- VALID | VOID
    attendee_name  VARCHAR(120) NULL,
    issued_at      DATETIME(6)  NOT NULL,
    voided_at      DATETIME(6)  NULL,
    void_reason    VARCHAR(30)  NULL,
    -- La emisión es idempotente: emitir dos veces la misma línea nunca duplica boletos.
    CONSTRAINT uq_tickets_item_seq UNIQUE (order_item_id, seq),
    CONSTRAINT uq_tickets_code UNIQUE (code),
    CONSTRAINT fk_tickets_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_item FOREIGN KEY (order_item_id) REFERENCES order_items (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_type FOREIGN KEY (ticket_type_id) REFERENCES ticket_types (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_owner FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX ix_tickets_owner (owner_user_id, issued_at),
    INDEX ix_tickets_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
