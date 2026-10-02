# Decisiones

Supuestos, alcance y pendientes del servicio de reservas. Se actualiza en cada feature.

## Diseño

- **Librería Java pura, sin framework.** El contrato es `Inventory.create(Clock, StockAlertListener)` y los tests lo usan directamente. Una capa REST/Spring no aporta a lo que se pide y agrega superficie de revisión.
- **Paquetes por feature:** `catalog` (reglas por categoría), `stock` (stock y reservas de un producto, almacenamiento). `Inventory` solo conecta las piezas; `DefaultInventoryService` orquesta.
- **Reglas por categoría en una sola tabla** (`CategoryPolicies.defaults()`). Agregar una categoría = una línea. Si falta la política de alguna categoría del enum, falla al crear el servicio y falla `CategoryPoliciesTest`.
- **Almacenamiento detrás de un puerto** (`InventoryStore`), implementado en memoria. Es la única interfaz con una sola implementación del proyecto; se justifica porque el README anuncia la migración a base de datos.

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

## Fuera de alcance

_Se completa en la release._

## Antes de llevarlo a producción

_Se completa en la release._

## Uso de IA

_Se completa en la release._
