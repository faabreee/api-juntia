CREATE TABLE venues (
    id              CHAR(36)     NOT NULL PRIMARY KEY,
    organization_id CHAR(36)     NOT NULL,
    name            VARCHAR(150) NOT NULL,
    address         VARCHAR(255) NULL,
    city            VARCHAR(100) NULL,
    country         CHAR(2)      NOT NULL,
    capacity        INT          NULL,
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT fk_venues_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    INDEX ix_venues_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE events (
    id                 CHAR(36)     NOT NULL PRIMARY KEY,
    organization_id    CHAR(36)     NOT NULL,
    slug               VARCHAR(80)  NOT NULL,
    title              VARCHAR(150) NOT NULL,
    description        TEXT         NULL,
    status             VARCHAR(20)  NOT NULL,   -- DRAFT | PUBLISHED | CANCELLED
    timezone           VARCHAR(64)  NOT NULL,
    cover_key          VARCHAR(100) NULL,
    cover_content_type VARCHAR(50)  NULL,
    cancel_reason      VARCHAR(500) NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    published_at       DATETIME(6)  NULL,
    cancelled_at       DATETIME(6)  NULL,
    CONSTRAINT uq_events_slug UNIQUE (slug),
    CONSTRAINT fk_events_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    INDEX ix_events_org_status (organization_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE event_sessions (
    id         CHAR(36)     NOT NULL PRIMARY KEY,
    event_id   CHAR(36)     NOT NULL,
    name       VARCHAR(120) NOT NULL,
    starts_at  DATETIME(6)  NOT NULL,   -- UTC
    ends_at    DATETIME(6)  NOT NULL,   -- UTC
    venue_id   CHAR(36)     NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT fk_sessions_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_sessions_venue FOREIGN KEY (venue_id) REFERENCES venues (id) ON DELETE RESTRICT,
    INDEX ix_sessions_event (event_id, starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
