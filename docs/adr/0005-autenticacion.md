# 0005. Autenticación

- Estado: aceptado
- Fecha: 2026-10-25

## Contexto

Juntia tiene un frontend Next.js, un modo validador (PWA) y, más adelante, una app móvil. El backend es un monolito modular en Spring Boot con MySQL. La Etapa 1 necesita registro, login, cierre de sesión, verificación de correo y recuperación de contraseña, con autorización por organización.

Alternativas consideradas:

- **JWT access + refresh:** sirve igual para web y móvil, pero la revocación y la rotación de refresh tokens añaden complejidad.
- **Proveedor externo (Keycloak, Auth0, Clerk):** menos código propio, pero añade dependencia, coste y operación.

## Decisión

Sesión en servidor con **Spring Session JDBC** (almacenada en MySQL) y una cookie de sesión `HttpOnly`, `Secure` y `SameSite=Lax`.

- Contraseñas con BCrypt (o Argon2) mediante `PasswordEncoder` de Spring Security.
- El frontend no guarda tokens en el navegador; envía la cookie con `credentials: "include"`.
- Protección CSRF activa para peticiones que cambian estado (token CSRF para el frontend).
- Frontend y API se sirven bajo el mismo sitio (dominio registrable) para que `SameSite` funcione; CORS restringido al origen del frontend.
- Los endpoints de gestión comprueban la pertenencia a la organización además de la sesión.
- La app móvil futura usará un token opaco propio (revocable, guardado en la tabla de sesiones o similar), definido en un ADR posterior.

## Consecuencias

- Revocación inmediata: cerrar sesión o expulsar a un usuario elimina la sesión.
- Sin gestión de tokens en el navegador, lo que reduce el riesgo de robo por XSS.
- Cada petición consulta la sesión en MySQL; es aceptable al inicio y se puede cachear después.
- Requiere servir web y API en el mismo sitio y configurar CSRF/CORS con cuidado.
- La `SecurityConfig` provisional de la Etapa 0 se sustituye en la Etapa 1.
