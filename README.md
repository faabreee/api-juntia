# api-joinify

Backend de Juntia: monolito modular en Spring Boot (Java 21, Gradle, MySQL).

## Estructura

```
src/main/java/api/joinify/   # un paquete por módulo (identity, events, ...)
src/main/resources/          # configuración y migraciones (db/migration)
src/test/java/api/joinify/   # pruebas (incluirá ModularityTests)
docs/adr/                    # registros de decisiones de arquitectura
```

## Infraestructura local

```bash
docker compose up -d --wait
```

- MySQL 8.4 en `localhost:3306` (db/usuario/clave por defecto: `juntia`)
- Mailpit: SMTP `localhost:1025`, interfaz http://localhost:8025

Valores configurables copiando `.env.example` a `.env`.

## Roadmap

Etapa 0 (Fundaciones) en curso: docker compose, migración V1, actuator, Swagger, CI.

## Decisiones (ADR)

Ver [docs/adr](docs/adr/README.md).
