CREATE TABLE organizations (
    id         CHAR(36)     NOT NULL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    type       VARCHAR(20)  NOT NULL,   -- PERSON | COMPANY
    country    CHAR(2)      NOT NULL,   -- ISO 3166-1 alpha-2
    currency   CHAR(3)      NOT NULL,   -- ISO 4217
    timezone   VARCHAR(64)  NOT NULL,   -- IANA
    created_at DATETIME(6)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE organization_members (
    organization_id CHAR(36)    NOT NULL,
    user_id         CHAR(36)    NOT NULL,
    role            VARCHAR(20) NOT NULL,   -- OWNER | ADMIN | MEMBER
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (organization_id, user_id),
    CONSTRAINT fk_members_org  FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX ix_members_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE organization_invitations (
    id              CHAR(36)     NOT NULL PRIMARY KEY,
    organization_id CHAR(36)     NOT NULL,
    email           VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL,   -- ADMIN | MEMBER
    token_hash      CHAR(64)     NOT NULL,
    invited_by      CHAR(36)     NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    accepted_at     DATETIME(6)  NULL,
    CONSTRAINT uq_invitations_token UNIQUE (token_hash),
    CONSTRAINT fk_invitations_org  FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_invitations_user FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE CASCADE,
    INDEX ix_invitations_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
