-- Dispositivos de validación: cada puerta registra el suyo y toda lectura queda atribuida a él.
CREATE TABLE validation_devices (
    id              CHAR(36)    NOT NULL PRIMARY KEY,
    organization_id CHAR(36)    NOT NULL,
    name            VARCHAR(60) NOT NULL,
    registered_by   CHAR(36)    NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    last_seen_at    DATETIME(6) NULL,
    revoked_at      DATETIME(6) NULL,
    revoked_by      CHAR(36)    NULL,
    CONSTRAINT fk_devices_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_devices_user FOREIGN KEY (registered_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX ix_devices_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Registro de TODOS los intentos de ingreso (aceptados y rechazados): es la auditoría y también el historial
-- que alimenta a AccessPolicy (los aceptados no revertidos).
CREATE TABLE check_ins (
    id                CHAR(36)     NOT NULL PRIMARY KEY,
    scan_id           VARCHAR(64)  NOT NULL,   -- idempotencia: reintentar la misma lectura no cuenta dos veces
    organization_id   CHAR(36)     NOT NULL,
    event_id          CHAR(36)     NOT NULL,
    ticket_id         CHAR(36)     NULL,       -- null si el código no correspondía a ningún boleto
    session_id        CHAR(36)     NULL,
    device_id         CHAR(36)     NOT NULL,
    validator_user_id CHAR(36)     NOT NULL,
    result            VARCHAR(10)  NOT NULL,   -- ACCEPTED | REJECTED
    reason            VARCHAR(40)  NULL,
    method            VARCHAR(10)  NOT NULL,   -- TOKEN | CODE | MANUAL
    created_at        DATETIME(6)  NOT NULL,
    reverted_at       DATETIME(6)  NULL,
    reverted_by       CHAR(36)     NULL,
    revert_reason     VARCHAR(255) NULL,
    CONSTRAINT uq_check_ins_scan UNIQUE (scan_id),
    CONSTRAINT fk_check_ins_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_check_ins_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_check_ins_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_check_ins_device FOREIGN KEY (device_id) REFERENCES validation_devices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_check_ins_user FOREIGN KEY (validator_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX ix_check_ins_ticket (ticket_id, result, reverted_at),
    INDEX ix_check_ins_event (event_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Primer ingreso de un boleto: a partir de ahí su nombre de asistente ya no se puede cambiar.
ALTER TABLE tickets ADD COLUMN first_checkin_at DATETIME(6) NULL;
