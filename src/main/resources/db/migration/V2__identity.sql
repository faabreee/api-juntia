CREATE TABLE users (
    id                CHAR(36)     NOT NULL PRIMARY KEY,
    email             VARCHAR(255) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    display_name      VARCHAR(100) NOT NULL,
    email_verified_at DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tokens de un solo uso para verificar correo y recuperar contraseña (se guarda solo el hash).
CREATE TABLE auth_tokens (
    token_hash CHAR(64)     NOT NULL PRIMARY KEY,
    user_id    CHAR(36)     NOT NULL,
    type       VARCHAR(20)  NOT NULL,
    expires_at DATETIME(6)  NOT NULL,
    used_at    DATETIME(6)  NULL,
    CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
