CREATE TABLE ticket_types (
    id                    CHAR(36)     NOT NULL PRIMARY KEY,
    event_id              CHAR(36)     NOT NULL,
    name                  VARCHAR(120) NOT NULL,
    description           VARCHAR(500) NULL,
    template              VARCHAR(20)  NOT NULL,   -- SINGLE | MULTI_DAY | N_USES | CUSTOM (solo para reabrir el editor)
    price_minor           BIGINT       NOT NULL,   -- unidad menor de la moneda; 0 = gratis
    currency              CHAR(3)      NOT NULL,
    quota                 INT          NULL,       -- null = sin límite
    max_per_order         INT          NOT NULL,
    sales_start           DATETIME(6)  NULL,       -- UTC
    sales_end             DATETIME(6)  NULL,       -- UTC
    -- regla de acceso (ADR 0004)
    access_scope          VARCHAR(20)  NOT NULL,   -- ALL_SESSIONS | SELECTED_SESSIONS
    max_total_uses        INT          NULL,
    max_uses_per_session  INT          NULL,
    early_entry_minutes   INT          NOT NULL,
    active                BOOLEAN      NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    CONSTRAINT fk_ticket_types_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    INDEX ix_ticket_types_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_type_sessions (
    ticket_type_id CHAR(36) NOT NULL,
    session_id     CHAR(36) NOT NULL,
    PRIMARY KEY (ticket_type_id, session_id),
    CONSTRAINT fk_tts_type    FOREIGN KEY (ticket_type_id) REFERENCES ticket_types (id) ON DELETE CASCADE,
    CONSTRAINT fk_tts_session FOREIGN KEY (session_id)     REFERENCES event_sessions (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
