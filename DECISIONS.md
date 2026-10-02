# Decisiones

Este documento reúne los supuestos que tomé, lo que dejé fuera del alcance y lo que faltaría para llevar el servicio a producción. Lo fui actualizando con cada feature.

## Diseño

- **Una librería Java, sin framework.** El contrato que pide el ejercicio es `Inventory.create(Clock, StockAlertListener)`, y los tests lo llaman directamente. Agregar una capa REST o Spring no aportaba nada a lo que se pedía y solo aumentaba el código a revisar.
- **Paquetes organizados por funcionalidad.** El código se divide en tres paquetes: `catalog` contiene las reglas de cada categoría, `stock` maneja el stock y las reservas de cada producto, y `alert` decide cuándo avisar que queda poco stock. La clase `Inventory` solo conecta estas piezas, y `DefaultInventoryService` coordina las operaciones.
- **Las reglas por categoría están en una sola tabla** (`CategoryPolicies.defaults()`). Para agregar una categoría nueva basta con añadir una línea. Si alguna categoría del enum no tiene reglas definidas, el servicio falla al crearse, y el test `CategoryPoliciesTest` también lo detecta.
- **El almacenamiento está detrás de una interfaz** (`InventoryStore`), que hoy se implementa en memoria. Es la única interfaz del proyecto con una sola implementación, y existe para separar dónde se guardan los datos de la lógica del servicio. Sin embargo, todavía no está lista para conectarse a una base de datos: devuelve directamente los objetos `ProductStock`, y la concurrencia depende de que esos objetos se bloqueen con `synchronized`, algo que una base de datos no puede ofrecer. Explico cómo cambiaría en la sección "Antes de llevarlo a producción".

## Supuestos

### Catálogo y stock

- Si se registra un SKU que ya existe con la misma categoría, no pasa nada; así los reintentos son seguros. Si se registra con una categoría distinta, se lanza `IllegalArgumentException`, porque cambiar la categoría de un producto con reservas activas cambiaría sus reglas en medio de un pago.
- Un SKU vacío o una categoría nula en `registerProduct` también lanzan `IllegalArgumentException`.
- Un producto recién registrado empieza con 0 unidades, y un producto que no existe tiene 0 unidades disponibles, como indica el contrato.

### Reservas

- **Orden de las validaciones.** `reserve` revisa primero que los datos sean válidos (`IllegalArgumentException`), luego el límite de unidades de la categoría (`OrderLimitExceededException`) y por último que haya stock (`InsufficientStockException`). Puse el límite antes que el stock porque es una regla del pedido en sí, mientras que el stock depende del momento en que se hace.
- **Los reintentos con el mismo `orderId` son seguros.** Si la conexión es lenta, la app puede reenviar el mismo pedido. Cuando llega un reenvío con el mismo SKU y la misma cantidad, se devuelve la reserva que ya existe, aunque ya no quede stock.
- **Un `orderId` repetido con otro SKU u otra cantidad** lanza `IllegalArgumentException`, porque ya no es un reintento, sino un pedido diferente con el mismo identificador.
- **La relación entre pedido y SKU se guarda antes de validar el stock**, y no se borra si la reserva falla. De esta forma, aunque lleguen varios reintentos al mismo tiempo, `confirm` siempre encuentra el producto del pedido. La desventaja es que un `orderId` que falló por falta de stock queda ligado a ese SKU. Como la app genera un id distinto por producto, no debería ser un problema.

### Expiración

- **No hay tareas programadas.** Las reservas vencidas se liberan cuando llega la siguiente operación sobre ese producto, usando la hora del `Clock` inyectado. Desde fuera, el resultado es el mismo que con un proceso periódico, pero sin hilos adicionales ni conflictos con ellos, y los tests pueden controlar el tiempo con `MutableClock`.
- **El momento exacto del vencimiento.** Una reserva sigue activa mientras la hora actual sea anterior a `expiresAt`. En el instante `expiresAt` ya se considera liberada.
- **Las reservas se ordenan por fecha de vencimiento** en una `PriorityQueue`. Así, en cada operación solo se tocan las que ya vencieron, en lugar de recorrer todas las reservas.
- **Un reintento después del vencimiento** crea una reserva nueva si hay stock. La reserva anterior ya no existe, y tratar el reintento como un pedido nuevo es lo que menos sorprende.

### Confirmación

- **`confirm` sigue el contrato al pie de la letra.** Si no hay una reserva activa (porque el pedido no existe, venció, falló o ya se confirmó), lanza `IllegalStateException`. Esto significa que confirmar dos veces el mismo pedido también falla. Si el sistema de pagos reintenta, tendría que interpretar esa excepción como "ya confirmado". Es una decisión que convendría revisar con el equipo.
- **Al confirmar, las unidades se descuentan del stock** y la reserva deja de estar activa. Las unidades vendidas no vuelven al inventario, aunque pase la ventana de pago.
- **Si llega un `reserve` tarde para un pedido ya pagado**, se devuelve la reserva confirmada sin reservar otra vez. Para lograrlo, los pedidos confirmados se guardan en memoria sin límite. Con una base de datos, esto se resolvería con una tabla de pedidos y su estado.

### Avisos de stock bajo

- **Umbral.** Se envía un aviso cuando quedan 5 unidades disponibles o menos (`LowStockTracker.THRESHOLD`), indicando la cantidad actual.
- **Un solo aviso hasta que se reabastezca.** Solo `addStock` cuenta como reabastecimiento y vuelve a activar el aviso. Si vuelven unidades porque una reserva venció, eso no es reabastecer, así que el aviso no se repite.
- **Si después de reabastecer siguen quedando 5 o menos**, se avisa de nuevo con la cantidad nueva, porque hubo reabastecimiento y el equipo de compras necesita saberlo. Lo mismo pasa con el primer `addStock` de un producto nuevo que entra con poco stock.
- **El aviso se envía fuera del bloqueo del producto.** Mientras el producto está bloqueado, se decide qué operación se encarga de enviar el aviso (solo una), y el envío ocurre después de liberar el bloqueo. Así, un canal lento como el correo no retrasa las reservas.
- **Si el canal falla, la reserva no se pierde.** El error se registra en el log, la reserva se completa y el aviso queda pendiente para que la siguiente operación sobre el producto (`reserve` o `addStock`) lo vuelva a intentar. La desventaja es que, si no hay más operaciones, el aviso nunca llega. En producción esto se resolvería con un outbox y reintentos.
- **Varios canales.** `StockAlertListener` ya sirve como punto de extensión: para avisar por correo y por Slack basta con un listener que reenvíe el aviso a los demás. No lo implementé porque hoy solo hay un canal.

### Concurrencia

- **Un bloqueo por SKU.** Cada `ProductStock` funciona como su propio bloqueo (`synchronized`). Los pedidos de productos distintos no se esperan entre sí, y los del mismo producto se procesan de uno en uno. Esto es suficiente con una sola instancia; con varias, el bloqueo tiene que pasar a la base de datos (ver "Antes de llevarlo a producción").
- **La concurrencia está probada con hilos reales.** `ConcurrencyTest` lanza 200 pedidos al mismo tiempo sobre 50 unidades y comprueba que se crean exactamente 50 reservas, que quedan 0 disponibles y que se envía un solo aviso. También lanza 200 reintentos simultáneos del mismo `orderId` y comprueba que solo se crea una reserva. Cada test se repite 20 veces, porque un problema de concurrencia no siempre aparece en la primera ejecución. Comprobé que, si se quita el `synchronized` de `reserve`, ambos tests fallan (se venden entre 57 y 62 unidades de 50).
- **Cómo se generan los pedidos simultáneos.** Todos los hilos esperan la misma señal (un `CountDownLatch`) y llaman al servicio a la vez. Uso hilos virtuales porque crear 200 casi no tiene costo.

## Fuera de alcance

- **API REST, Spring y persistencia real.** El contrato es una librería, y el README pide por ahora guardar los datos en memoria.
- **Cancelar una reserva** antes de que venza, por ejemplo cuando el cliente abandona el carrito. El contrato no ofrece esta operación; hoy la unidad vuelve al inventario cuando la reserva vence.
- **Devoluciones o ajustes de inventario**, como restar stock o registrar mermas. El contrato solo incluye `addStock`.
- **Varios canales de aviso.** `StockAlertListener` ya lo permite con un listener que reenvíe a varios canales, pero hoy no hay un segundo canal.
- **Un umbral de stock bajo por producto o por categoría.** Está fijo en 5, como indica el README.
- **Métricas y trazas** (reservas por segundo, vencimientos, avisos fallidos). Hoy solo se registra en el log cuando falla un aviso.

## Antes de llevarlo a producción

El README menciona que más adelante habrá una base de datos y varias instancias del servicio. En ese escenario, `synchronized` deja de proteger el stock, porque cada instancia tendría su propio bloqueo. Estos son los cambios necesarios:

1. **El bloqueo pasa a la base de datos.** Reservar sería una sola sentencia atómica:

   ```sql
   UPDATE stock SET reserved = reserved + :q
   WHERE sku = :sku AND on_hand - reserved >= :q
   ```

   Si no se actualiza ninguna fila, es que no hay stock suficiente. Otras opciones son `SELECT ... FOR UPDATE` dentro de una transacción, o un bloqueo optimista con una columna `version` y reintentos. Cualquiera de las tres reemplaza a `ProductStock` como bloqueo. Además, `InventoryStore` dejaría de devolver objetos y pasaría a ofrecer operaciones (`reserve`, `confirm`, `release`).
2. **Una tabla de reservas** con `order_id` como clave primaria (así la propia base de datos impide pedidos duplicados), una columna `status` (`ACTIVE`, `CONFIRMED`, `EXPIRED`) y un índice por `expires_at`.
3. **Expiración.** Se puede seguir liberando las reservas al consultar (filtrando por `expires_at > now`) y añadir un proceso periódico que libere las vencidas en lote, para que los productos que nadie consulta no acumulen unidades reservadas de más.
4. **Datos en memoria que hoy crecen sin límite.** La relación entre pedido y SKU (`InMemoryInventoryStore.orderSkus`) y los pedidos confirmados (`ProductStock.confirmed`) nunca se borran. Con una base de datos, pasarían a la tabla de reservas con una política de borrado o archivado, por ejemplo a los 30 días.
5. **Avisos con outbox.** El aviso se guardaría en una tabla dentro de la misma transacción que la reserva, y un proceso aparte lo enviaría con reintentos. Hoy, si el canal falla, el aviso puede perderse. Además, el estado "ya avisado" tendría que guardarse en la base de datos; si no, cada instancia enviaría su propio aviso.
6. **Relojes.** Con varias instancias, la hora para calcular vencimientos debería venir de la base de datos (`now()`) o de relojes sincronizados, para que dos instancias no lleguen a conclusiones distintas sobre si una reserva venció.
7. **`confirm` repetido.** Hay que acordar con el equipo de pagos si un segundo `confirm` debería ser idempotente en lugar de lanzar `IllegalStateException`.
8. **Dependencia entre `stock` y `alert`.** Hoy `ProductStock` guarda su `LowStockTracker` para decidir el aviso dentro del mismo bloqueo. Con una base de datos, esa decisión se tomaría dentro de la transacción y esta dependencia desaparecería.

## Uso de IA

Usé Claude Code como compañero de programación, con reglas claras y revisando yo cada paso:

- **Reglas dentro del repositorio.** El `CLAUDE.md` del proyecto define las reglas del contrato y las convenciones, y un hook `PreToolUse` (`.claude/hooks/protect-api.sh`) bloquea cualquier cambio en el paquete `com.store.inventory.api`. Así, la regla de no tocar el contrato no depende de que la IA la recuerde.
- **Primero el plan, después el código.** Antes de escribir código, preparamos un plan con los requisitos implícitos del README (idempotencia, expiración con `Clock`, orden de las validaciones y concurrencia) y las ramas necesarias. Lo revisé y ajusté antes de empezar.
- **Una rama por funcionalidad.** Cada rama siguió el mismo orden de commits (`feat`, luego `test`, luego `docs(decisions)`), con `mvn test` en verde y un PR que revisé y mergeé yo en GitHub.
- **Lo que decidí o descarté.** Descarté Spring y REST porque se salían del alcance; un listener compuesto para varios canales, porque todavía no hace falta; y un commit de refactor de avisos que estaba en el plan, porque los avisos ya se enviaban fuera del bloqueo.
- **Revisión antes de la release.** Una revisión completa del código encontró tres cosas: faltaba un test de concurrencia, `InventoryStore` no está realmente preparado para una base de datos y había datos en memoria que crecían sin límite. El test se agregó en `feature/concurrency-safety`, y lo demás está documentado arriba. Antes de mergear a `main`, ejecuté `/code-review` sobre `main...release/1.0.0`.
- **Tests que de verdad pueden fallar.** Comprobé el test de concurrencia quitando el `synchronized` y viendo que fallaba.
