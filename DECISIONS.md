# Decisiones

Supuestos, alcance y pendientes del servicio de reservas. Se actualiza en cada feature.

## Diseño

- **Librería Java pura, sin framework.** El contrato es `Inventory.create(Clock, StockAlertListener)` y los tests lo usan directamente. Una capa REST/Spring no aporta a lo que se pide y agrega superficie de revisión.
- **Paquetes por feature:** `catalog` (reglas por categoría), `stock` (stock y reservas de un producto, almacenamiento), `alert` (cuándo avisar de stock bajo). `Inventory` solo conecta las piezas; `DefaultInventoryService` orquesta.
- **Reglas por categoría en una sola tabla** (`CategoryPolicies.defaults()`). Agregar una categoría = una línea. Si falta la política de alguna categoría del enum, falla al crear el servicio y falla `CategoryPoliciesTest`.
- **Almacenamiento detrás de una interfaz** (`InventoryStore`), implementada en memoria. Es la única interfaz con una sola implementación del proyecto: separa el "dónde vive el dato" del servicio. No es todavía un puerto listo para BD: devuelve objetos `ProductStock` vivos cuyo `synchronized` hace la concurrencia, y una BD no puede cumplir eso. El cambio real está en "Antes de llevarlo a producción".

## Supuestos

### Catálogo y stock
- Registrar un SKU ya registrado con la misma categoría no hace nada (reintentos seguros). Con otra categoría lanza `IllegalArgumentException`: cambiar la categoría de un producto con reservas vivas cambiaría sus reglas a mitad del pago.
- SKU vacío o categoría nula en `registerProduct` lanzan `IllegalArgumentException`.
- Un producto registrado empieza con 0 unidades. Un producto desconocido tiene 0 disponibles (contrato).

### Reservas
- **Orden de validación en `reserve`:** datos inválidos (`IllegalArgumentException`) → límite de la categoría (`OrderLimitExceededException`) → stock (`InsufficientStockException`). El límite va antes que el stock porque es una regla del pedido, no del momento.
- **Reintentos idempotentes por `orderId`:** la app reenvía el pedido si la conexión es lenta. Un reenvío con el mismo SKU y cantidad devuelve la reserva existente, aunque ya no quede stock.
- **Un `orderId` reutilizado para otro SKU u otra cantidad** lanza `IllegalArgumentException`: no es un reintento, es otro pedido con el mismo id.
- **El vínculo pedido → SKU se guarda antes de validar el stock** y no se borra si la reserva falla. Así un reintento concurrente nunca pierde el índice que usa `confirm`. Costo: un `orderId` que falló por stock queda atado a ese SKU (la app genera un id por producto, así que no debería importar).

### Expiración
- **Sin schedulers:** las reservas vencidas se liberan de forma perezosa al inicio de cada operación del producto, con la hora del `Clock` inyectado. El resultado observable es el mismo que con un job, sin hilos extra ni carreras con él, y los tests controlan el tiempo con `MutableClock`.
- **Límite exacto:** una reserva está activa mientras `now < expiresAt`. En `expiresAt` ya está liberada.
- **Cola por vencimiento** (`PriorityQueue`): liberar cuesta O(log n) por reserva vencida, no recorrer todas las reservas en cada llamada.
- **Reintento después de vencer:** crea una reserva nueva si hay stock. La anterior ya no existe; tratar el reintento como pedido nuevo es lo menos sorprendente.

### Confirmación
- **`confirm` sigue el contrato al pie de la letra:** sin reserva activa (pedido desconocido, vencido, fallido o ya confirmado) lanza `IllegalStateException`. Un segundo `confirm` del mismo pedido también falla: la reserva ya no está activa. Si el sistema de pagos reintenta, debe tratar esa excepción como "ya confirmado"; alternativa discutible con el equipo.
- **Confirmar descuenta las unidades del stock** (`onHand`) y saca la reserva de las activas. Las unidades vendidas nunca vuelven, aunque pase la ventana de pago.
- **Un `reserve` tardío de un pedido ya pagado** devuelve la reserva confirmada sin reservar de nuevo. Para eso los pedidos confirmados se guardan en memoria sin límite; en BD se resolvería con una tabla de pedidos y su estado.

### Avisos de stock bajo
- **Umbral:** se avisa cuando las unidades disponibles quedan en 5 o menos (`LowStockTracker.THRESHOLD`), con la cifra actual.
- **Un solo aviso hasta reabastecer:** solo `addStock` cuenta como reabastecimiento y rearma el aviso. Que vuelvan unidades por reservas vencidas no es reabastecer, así que no se repite el aviso.
- **Reabastecer y seguir en 5 o menos** vuelve a avisar con la nueva cifra: hubo reabastecimiento y compras sigue necesitando saberlo. Lo mismo aplica al primer `addStock` de un producto nuevo con poco stock.
- **El aviso se envía fuera del lock del producto:** el producto decide bajo su lock quién "reclama" el aviso (exactamente uno) y el envío ocurre después. Un canal lento, como el correo, no frena las reservas.
- **Un canal que falla no rompe la reserva:** se registra el error y la reserva sigue. Costo: ese aviso se pierde. En producción se resolvería con un outbox y reintentos.
- **Multicanal:** `StockAlertListener` ya es el punto de extensión. Para correo + Slack basta un listener que reparta a varios; no lo agregué porque hoy hay un solo canal.

### Concurrencia
- **Lock por SKU:** `ProductStock` es el lock (`synchronized`). Pedidos de productos distintos no se bloquean entre sí; los del mismo producto se serializan. Basta para una instancia; con varias, el lock tiene que pasar a la BD (ver "Antes de llevarlo a producción").
- **Probado con hilos, no solo argumentado:** `ConcurrencyTest` lanza 200 pedidos a la vez sobre 50 unidades (exactamente 50 reservas, 0 disponibles, un solo aviso) y 200 reintentos simultáneos del mismo `orderId` (una sola reserva). Cada test se repite 20 veces porque una carrera no siempre aparece en la primera corrida. Verifiqué que sin `synchronized` en `reserve` ambos fallan (se venden 57–62 de 50).
- **Hilos virtuales + un latch de salida:** todos los pedidos esperan la misma señal y pegan al servicio al mismo tiempo; con hilos virtuales 200 tareas no cuestan nada.

## Fuera de alcance

- **API REST, Spring, persistencia real.** El contrato es una librería; el README pide datos en memoria por ahora.
- **Cancelar una reserva** antes de que venza (p. ej. el cliente abandona el carrito). El contrato no lo expone; hoy la unidad vuelve al vencer.
- **Devoluciones o ajustes de inventario** (restar stock, mermas). El contrato solo tiene `addStock`.
- **Varios canales de aviso.** `StockAlertListener` ya lo permite con un listener que reparta; no hay un segundo canal hoy.
- **Umbral de stock bajo por producto o categoría.** Es fijo en 5 (`LowStockTracker.THRESHOLD`), como dice el README.
- **Métricas y trazas** (reservas por segundo, vencimientos, avisos fallidos). Solo se registra en log el aviso que falla.

## Antes de llevarlo a producción

El README anuncia BD y varias instancias. Con eso el `synchronized` deja de proteger nada: dos instancias tienen dos copias del lock. Lo que cambiaría:

1. **El lock pasa a la BD.** Reservar sería una sola sentencia atómica:
   `UPDATE stock SET reserved = reserved + :q WHERE sku = :sku AND on_hand - reserved >= :q`
   (0 filas = sin stock), o `SELECT ... FOR UPDATE` dentro de la transacción, u optimistic locking con columna `version` y reintento. Cualquiera de las tres reemplaza a `ProductStock` como lock; `InventoryStore` dejaría de devolver objetos vivos y pasaría a operaciones (`reserve`, `confirm`, `release`).
2. **Tabla de reservas** con `order_id` como clave primaria (la idempotencia la da la restricción única, no un mapa), `status` (`ACTIVE`/`CONFIRMED`/`EXPIRED`) e índice por `expires_at`.
3. **Expiración:** se puede mantener perezosa (filtrar `expires_at > now` en cada consulta) y sumar un job que libere en lote para que `reserved` no quede inflado en productos que nadie consulta.
4. **Memoria sin límite que hoy existe:** el índice `orderId → sku` (`InMemoryInventoryStore.orderSkus`) y los pedidos confirmados (`ProductStock.confirmed`) nunca se borran. En BD pasan a la tabla de reservas con un TTL o archivado (p. ej. 30 días).
5. **Avisos con outbox:** escribir el aviso en una tabla en la misma transacción que la reserva y que un proceso aparte lo envíe con reintentos. Hoy un canal que falla pierde el aviso. El estado "ya avisado" también pasa a la BD, si no cada instancia avisaría una vez.
6. **Relojes:** con varias instancias, la hora de vencimiento debería venir de la BD (`now()`) o de relojes sincronizados, para que dos instancias no discrepen sobre si una reserva venció.
7. **`confirm` repetido:** acordar con pagos si un segundo `confirm` debe ser idempotente en vez de lanzar `IllegalStateException`.
8. **Acoplamiento `stock` → `alert`:** `ProductStock` guarda su `LowStockTracker` para decidir el aviso bajo el mismo lock. Con BD esa decisión se mueve a la transacción y el acoplamiento desaparece.

## Uso de IA

Usé Claude Code como par de programación, con reglas explícitas y revisión mía en cada paso:

- **Guardrails en el repo:** `CLAUDE.md` del proyecto con las reglas del contrato y las convenciones, y un hook `PreToolUse` (`.claude/hooks/protect-api.sh`) que bloquea cualquier edición en `com.store.inventory.api`. La regla "no tocar el contrato" no depende de que la IA se acuerde.
- **Plan antes de código:** primero un plan con los requisitos implícitos del README (idempotencia, expiración por `Clock`, orden de validaciones, concurrencia) y las ramas; lo aprobé y ajusté antes de escribir nada.
- **Una rama por feature:** cada una con commits `feat` → `test` → `docs(decisions)`, `mvn test` en verde y un PR que yo revisé y mergeé en GitHub.
- **Lo que decidí o descarté:** Spring/REST (scope creep), un listener compuesto para multicanal (YAGNI), y el commit de refactor de avisos del plan (los avisos ya salían fuera del lock).
- **Revisión antes de la release:** una revisión de todo el código detectó que faltaba un test de concurrencia, que `InventoryStore` no es un puerto real de BD y que había memoria sin límite. Lo primero se resolvió en `feature/concurrency-safety`; lo demás está documentado arriba. Antes del merge a `main` corrí `/code-review` sobre `main...release/1.0.0`.
- **Tests que pueden fallar:** el test de concurrencia se verificó quitando el `synchronized` y viéndolo fallar.
