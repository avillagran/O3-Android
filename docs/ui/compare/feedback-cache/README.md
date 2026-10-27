# Feedback 4 — Caché local acotada de hilos

Implementación `1d0e601`, contra main `820a108`, AAR fijado v0.3.5.

## Semántica

Antes cada ThreadViewModel creaba ThreadSession con proyección/cursor solo en
memoria y needSnapshot=true: volver a abrir obligaba a boundedThread. La posición
se conservaba al recrear el destino, pero no al crear una navegación nueva.

Ahora se guarda la proyección opaca de Rust, su snapshotSequence y los campos de
historial como una unidad atómica. Se separa por id local de entorno+hilo, y se
valida además UUID del servidor+origen guardados: cambiar de host no reutiliza
mensajes/cursor de otro servidor. Los nombres de archivo son hashes sin colisiones
por separadores. No hay mezcla, ordenación ni deduplicación de mensajes en Kotlin:
projection.apply y timeline.view siguen siendo Rust.

Al abrir, el último estado guardado se dibuja sin esperar al servidor y se marca
pendiente de validar; offline dice “Mensajes guardados · sin conexión”. La sesión
pide el stream desde ese cursor, con boundedSnapshot=true. Rust aplica eventos y
reemplaza la proyección si llega snapshot. Un fallo al reanudar vuelve al camino de
snapshot existente. Si un servidor quieto no entrega ningún primer ítem, se espera
hasta3s y se valida con boundedThread; no se dejan acciones bloqueadas para siempre.
Enviar/interrumpir y controles que requieren estado actual esperan revalidación.
Se puede editar el borrador offline. Reintentos de comandos pendientes conservan
la semántica durable existente, sin repetirlos por restaurar una proyección.

La caché guarda la última proyección **acotada** que la lib expone, no todo el
historial del servidor. Conserva historyCursor/hasMoreHistory/payloadBudgetExceeded;
la UI mantiene el límite de historial explícito. No concatena ventanas distintas
ni inventa mensajes para llenar huecos. El paginado/merge histórico común pendiente
U18 sigue fuera de este cambio. Un snapshot sustituye la ventana de forma atómica.

## Límites y limpieza

- Máximo32MiB total,128 entradas,8MiB por entrada y7d desde la última escritura.
- LRU por acceso; leer no rejuvenece la edad del contenido. Entradas demasiado
  grandes no se guardan. Podas al inicio, abrir, escribir y reconciliar shells.
- Archivos en noBackupFilesDir/thread-cache-v1; escritura temporal+rename atómico.
  Como máximo una escritura por segundo durante streaming y flush final al cerrar
  la sesión. Una muerte abrupta puede perder hasta el último segundo de avances
  solo de esta caché regenerable; no altera outbox ni borradores.
- Salir del chat conserva la caché. Cerrar/asentar o archivar elimina su proyección
  cuando el shell existente lo confirma mediante sidebar.view de Rust. Si el
  teléfono está offline, esa confirmación llega al reconectar.
- Una lease local evita que un lector antiguo o una escritura tardía recree el
  contenido eliminado/sobrescriba al lector nuevo. Un fallo de caché cuesta un
  snapshot; nunca una mutación de servidor.
- No se añaden suscripciones por entradas guardadas. El lector usa su ThreadSession
  existente; al salir cancela su stream. El observador de limpieza solo consume
  los shells que ConnectionManager ya posee.

Borradores y outbox siguen en sus tablas Room independientes y no se eliminan.
La posición es metadato pequeño separado en DataStore, por entorno+hilo: id de fila,
índice y desplazamiento. Se escribe serialmente, agrupando cambios de scroll y
conservando el último al salir; ni LRU ni cierre/archivo la borran. Al restaurar se
busca el id; si ya no está en la ventana acotada, se limita el índice a esa ventana.

## Verificación y evidencia

- Suite JVM completa: app268/268; core303 pasadas,5 optativas omitidas. Lint de ambos
  módulos y todas las capturas Roborazzi pasan. Después se verificaron la limpieza
  contra sidebar.view nativo y el bloqueo de acciones cacheadas con pruebas focales.
- Cache tests: reinicio de instancia/disco, aislamiento de entorno/host, bytes,
  tamaño individual, cantidad/LRU, edad que no se extiende al leer, escritor viejo,
  cierre y archivo nativos. El control de elegibilidad lo decide Rust.
- ThreadCachedSessionTest usa JNI real: reapertura offline sin snapshot, cursor10,
  actualización streaming del mismo mensaje sin duplicarlo, sustitución por snapshot,
  cursor rechazado→snapshot y servidor quieto→snapshot tras3s. Cancelación deja0streams.
- ThreadCacheDeviceTest1/1 API36.1: Back→offline con mensajes y borrador; restaura id
  de fila y desplazamiento relativo con tolerancia1px,0streams offline, reconexión
  sin segundo snapshot, recreación y una instancia nueva de cache/Room/VM offline
  con0lecturas boundedThread. También conserva el borrador de Room en disco.
- Un intento no encontró el AVD detenido; se repitió con AVDpropio5584 vivo bajo su
  unidad dedicada. Fallos de aserción semántica/registro JUnit del test se corrigieron
  antes del pase final; no son evidencia de aceptación.

online.png y restarted-offline.png: fixture sintético, pantallas/VM/Room/DataStore y
Rust reales, AVDot3-feedback361/emulator-5584. restart-offline.mp4 (15.54s) inspeccionado:
se reinicia realmente el proceso con --relaunch y las lecturas de red rechazadas desde
el inicio; reaparecen mensajes y borrador. El vídeo muestra el último mensaje;
la posición de lectura profunda y precisión de píxel se comprueban en instrumentación.
No son datos productivos ni prueba física. No hubo envío, borrado de servidor o Xiaomi.

Pendiente: aceptación productiva/física; historial anterior fuera de la ventana U18;
no se promete que una caché expulsada permita leer offline. Una expulsión recupera
mensajes desde el servidor cuando vuelve la red, conservando borrador/outbox/posición.

Integración posterior: origin/main152b851 (incluye tema/PR54) fusionado en939ad35.
Pruebas focales nativas de caché y tema, aplicación visual de tokens, guardas de
acciones y assembleDebug pasaron juntas tras esa integración.
