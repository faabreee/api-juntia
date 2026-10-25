-- Línea base del esquema. Las tablas de cada módulo llegan en sus etapas.
CREATE TABLE app_metadata (
    meta_key   VARCHAR(100) NOT NULL PRIMARY KEY,
    meta_value VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO app_metadata (meta_key, meta_value) VALUES ('schema_baseline', 'V1');
