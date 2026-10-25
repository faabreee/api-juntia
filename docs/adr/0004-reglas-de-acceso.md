# 0004. Reglas de acceso de las entradas

- Estado: aceptado
- Fecha: 2026-10-25

## Contexto

Un mismo evento puede tener varias sesiones (p. ej. un festival de 3 días). Las entradas deben poder expresar casos distintos: entrada para una sola función, pase por días, pase de varias entradas, reingreso libre, etc. La validación en puerta (Etapa 7) y la emisión de boletos (Etapa 6) necesitan una regla única, sin casos especiales por tipo de entrada.

## Decisión

Cada **tipo de entrada** lleva una **regla de acceso** compuesta por cuatro parámetros. No hay "tipos" de regla: todo se expresa con estos parámetros.

| Parámetro | Significado |
|---|---|
| `scope` | `ALL_SESSIONS` (todas las sesiones del evento) o `SELECTED_SESSIONS` (solo las listadas). |
| `maxTotalUses` | Máximo de ingresos en total con un boleto. `null` = sin límite. |
| `maxUsesPerSession` | Máximo de ingresos por sesión. `1` = un ingreso por sesión; `null` = reingreso libre. |
| `earlyEntryMinutes` | Minutos antes del inicio de la sesión desde los que se permite el ingreso (por defecto 60). Se permite hasta el fin de la sesión. |

### Evaluación (`AccessPolicy`, clase pura y sin estado)

`evaluate(regla, sesión, historial de ingresos, ahora)` devuelve permitido o un motivo. Las comprobaciones se aplican **en este orden**, y la primera que falla decide:

1. `SESSION_NOT_INCLUDED` — la sesión no está cubierta por el alcance.
2. `TOO_EARLY` — falta más de `earlyEntryMinutes` para el inicio.
3. `SESSION_ENDED` — la sesión ya terminó.
4. `TOTAL_USES_EXHAUSTED` — los ingresos previos alcanzan `maxTotalUses`.
5. `ALREADY_USED_IN_SESSION` — los ingresos previos en esa sesión alcanzan `maxUsesPerSession`.

El historial contiene solo ingresos ya aceptados. La política **no escribe nada**: registrar el ingreso (con la atomicidad necesaria para varias puertas) es responsabilidad de la Etapa 7.

### Validación de una regla al configurarla

- `SELECTED_SESSIONS` exige al menos una sesión, y todas deben pertenecer al evento.
- `maxTotalUses` y `maxUsesPerSession`, si existen, son >= 1.
- Si ambos existen, `maxUsesPerSession <= maxTotalUses`.
- `earlyEntryMinutes` entre 0 y 1440.

### Plantillas de la interfaz

La interfaz ofrece plantillas amigables que **solo rellenan estos parámetros**; el backend no las interpreta (se guarda su nombre únicamente para reabrir el editor igual).

| Plantilla | `scope` | `maxTotalUses` | `maxUsesPerSession` |
|---|---|---|---|
| Entrada única | una sesión elegida | 1 | 1 |
| Pase por días | los días elegidos (por defecto todos) | sin límite | 1 |
| Pase de N usos | todas las sesiones | N | 1 (o libre, opcional) |
| Personalizada | cualquiera | cualquiera | cualquiera |

### Ejemplos (todos con prueba automatizada)

1. **Entrada única** a la sesión S1: entra en S1 una vez; segundo intento → `TOTAL_USES_EXHAUSTED`; en S2 → `SESSION_NOT_INCLUDED`.
2. **Pase de 3 días** (S1, S2, S3): entra una vez cada día; repetir el mismo día → `ALREADY_USED_IN_SESSION`; un cuarto día no incluido → `SESSION_NOT_INCLUDED`.
3. **Pase de 3 usos** en un evento de 5 sesiones: entra en 3 sesiones distintas; el cuarto ingreso → `TOTAL_USES_EXHAUSTED`; repetir una sesión → `ALREADY_USED_IN_SESSION`.
4. **Reingreso libre** (`maxUsesPerSession = null`): puede salir y volver a entrar en la misma sesión.
5. **Ventana horaria**: antes de `inicio - earlyEntryMinutes` → `TOO_EARLY`; después del fin → `SESSION_ENDED`.

### Dinero

Los precios se guardan como entero en la unidad menor de la moneda (`priceMinor`, p. ej. céntimos) y con la moneda de la organización. Precio 0 = entrada gratuita.

## Consecuencias

- Una sola función decide el acceso para cualquier entrada; la validación en puerta no necesita conocer plantillas.
- Añadir una plantilla nueva es un cambio de interfaz, no de backend ni de datos.
- Casos fuera de este modelo (p. ej. acceso por zonas dentro del recinto o franjas horarias distintas por sesión) requerirían ampliar la regla y este ADR.
- Cambiar la regla de un tipo de entrada con boletos ya vendidos afecta a esos boletos; las etapas de venta deberán bloquear o versionar ese cambio.
