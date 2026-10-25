# 0010. Validación en puerta y control de acceso

- Estado: aceptado
- Fecha: 2026-10-25

## Contexto

Varias puertas validan boletos a la vez, con celulares, y un mismo QR puede llegar a dos puertas casi al mismo tiempo (una captura compartida, dos personas escaneando). Un boleto no puede ingresar más veces de las que permite su regla (ADR 0004), la respuesta debe ser inmediata y todo ingreso debe quedar auditado. Las redes de los recintos fallan: reintentar una lectura no debe convertir un ingreso legítimo en un rechazo.

## Decisión

- **Decide `AccessPolicy`, siempre con el historial confirmado.** `CheckInService` evalúa cada lectura con la regla del tipo de entrada, la sesión, los ingresos aceptados no revertidos y la hora del servidor (ADR 0004).
- **Atomicidad entre puertas.** La decisión y el registro ocurren en una transacción que primero **bloquea la fila del boleto** (`SELECT … FOR UPDATE`) y lee el historial con aislamiento `READ COMMITTED`. La puerta que llega segunda espera y ve el ingreso de la primera, así que el límite nunca se supera. Probado con 12 y 15 lecturas simultáneas de un mismo boleto desde varias puertas (entra exactamente 1 y exactamente 3 respectivamente); las pruebas fallan sin el bloqueo.
- **Idempotencia por lectura.** Cada lectura lleva un `scanId` único generado por la app (restricción única en `check_ins`). Reenviar la misma lectura devuelve el mismo resultado (`replayed = true`) y no cuenta dos veces.
- **Rechazar no es un error de la API.** Un boleto rechazado responde 200 con `result = REJECTED` y un motivo (los de `AccessPolicy` más `INVALID_TOKEN`, `INVALID_CODE`, `WRONG_EVENT`, `TICKET_NOT_FOUND`, `TICKET_VOID`, `EVENT_NOT_ACTIVE`). Solo los problemas de la petición o de permisos son 4xx. Un rechazo por ingreso repetido informa cuándo y por qué puerta entró antes.
- **Auditoría total.** `check_ins` guarda **todos** los intentos, aceptados y rechazados, con el dispositivo, quién validó, el método (QR, código corto o manual) y la hora. Los aceptados no revertidos son además el historial de `AccessPolicy`. Los datos no se borran.
- **Dispositivos.** Cada puerta registra el suyo (nombre libre, por organización) y toda lectura se atribuye a él. Un dispositivo no registrado, de otra organización o revocado recibe 403. Cualquier miembro registra el suyo; solo OWNER y ADMIN ven la lista y revocan.
- **Quién valida.** Cualquier miembro de la organización puede validar los eventos de esa organización (el personal de puerta suele ser MEMBER). Solo OWNER y ADMIN ven la auditoría completa y **revierten** un ingreso aceptado por error (con motivo obligatorio): la reversión no borra nada, lo marca, y el boleto puede volver a entrar.
- **Sesión.** La puerta puede indicar la sesión que valida; si no, el servidor elige entre las que cubre el boleto la que está en curso (con la anticipación permitida) y, si ninguna lo está, la próxima o la última, para dar un motivo claro.
- **Búsqueda manual.** Por código corto (se normaliza: minúsculas, espacios, guion), o por nombre del asistente, nombre o correo del comprador (mínimo 3 caracteres, `%` y `_` no son comodines, 20 resultados). El correo del comprador se muestra enmascarado (`a***@dominio.com`). El ingreso manual usa la misma lógica y atomicidad que el QR.
- **Nombre fijo tras el primer ingreso.** El primer ingreso aceptado marca el boleto; desde ahí el nombre del asistente (boletos nominativos) ya no se puede cambiar.
- **La marca de actividad del dispositivo va fuera de la transacción** y solo se escribe si pasaron más de 30 s: actualizar esa fila dentro de cada lectura provocaba interbloqueos entre puertas con el mismo dispositivo.
- **PWA.** El modo validador es una aplicación web instalable (manifiesto y service worker); la cámara usa `BarcodeDetector` si existe y `jsQR` si no (iOS), y un campo de texto recibe también lectores USB o Bluetooth. Sin conexión solo carga la pantalla de aviso: **la validación requiere red** (la validación offline se deja para la app móvil, según el roadmap).

## Consecuencias

- Con red caída en una puerta no se puede validar; el personal debe poder mover la lectura a otra puerta o esperar. Conviene probar la cobertura del recinto antes del evento.
- Un QR compartido por captura entra una sola vez (el segundo intento se rechaza), no se puede "adivinar" otro boleto y una lectura alterada se rechaza por su firma.
- La auditoría crece con cada lectura; para eventos muy grandes habrá que archivar por evento.
- Los reembolsos totales ya anulan los boletos (ADR 0009), así que un boleto reembolsado se rechaza como `TICKET_VOID`.
