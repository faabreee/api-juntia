-- Cola de notificaciones por correo (ADR 0011). Cada fila es un correo por enviar o ya enviado: sirve a la vez como
-- cola con reintentos y como registro de envíos. El contenido NO se guarda: solo el tipo y sus parámetros; el correo
-- se arma justo antes de enviarse, con los datos vigentes.
CREATE TABLE notifications (
    id                CHAR(36)     NOT NULL PRIMARY KEY,
    type              VARCHAR(40)  NOT NULL,   -- ORDER_CONFIRMATION | TICKETS_DELIVERY | EVENT_REMINDER | EVENT_CANCELLED | EVENT_CHANGED
    recipient_email   VARCHAR(255) NOT NULL,
    recipient_user_id CHAR(36)     NULL,
    organization_id   CHAR(36)     NULL,       -- para el registro del organizador
    event_id          CHAR(36)     NULL,
    params            TEXT         NOT NULL,   -- JSON con los datos que necesita la plantilla (ids, no contenido)
    dedupe_key        VARCHAR(150) NOT NULL,   -- el mismo aviso no se encola dos veces
    status            VARCHAR(10)  NOT NULL,   -- PENDING | SENDING | SENT | FAILED | SKIPPED
    attempts          INT          NOT NULL DEFAULT 0,
    next_attempt_at   DATETIME(6)  NOT NULL,
    lease_until       DATETIME(6)  NULL,       -- quien tomó el envío lo tiene "alquilado" hasta esta hora
    subject           VARCHAR(255) NULL,       -- se rellena al armar el correo
    last_error        VARCHAR(500) NULL,
    created_at        DATETIME(6)  NOT NULL,
    sent_at           DATETIME(6)  NULL,
    CONSTRAINT uq_notifications_dedupe UNIQUE (dedupe_key),
    INDEX ix_notifications_due (status, next_attempt_at),
    INDEX ix_notifications_event (event_id, created_at),
    INDEX ix_notifications_org (organization_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
