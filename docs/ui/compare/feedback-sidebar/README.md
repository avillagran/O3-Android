# Feedback 3 — Estantes inferiores

Implementación `d6a0a93`, base `main` `820a108`, lib v0.3.5 sin cambios.
Referencia de comportamiento: `ui/qml/T3Panel.qml:1205-1364` del escritorio:
lista principal flexible, cabeceras y cuerpos independientes de estantes.

Pospuestos y Cerrados quedan fuera del scroll principal. Sus cabeceras mantienen
48 dp de área táctil; cada cuerpo expandido tiene una LazyColumn independiente.
Cada cuerpo usa hasta el 30 % del espacio restante después de las cabeceras;
la lista principal ocupa el resto, también sin hilos activos. En ventanas de
menos de 480 dp de alto, marca y búsqueda comparten fila para dejar altura útil.
Las secciones, miembros y orden vienen de sidebar.view; el reparto es de layout.
La búsqueda de mensajes sigue en la lista principal. La paginación de Cerrados
permanece en su estante. Se conservan las animaciones de filas.

Verificación: HomeListSectionsTest, lint y compilación; SidebarShelvesDeviceTest
1/1 en API36.1, AVD propio ot3-feedback361/emulator-5584. Comprueba 0/1/80 activos,
25 por estante, scroll principal sin mover cabeceras, expansión de ambos,
scroll independiente de Pospuestos, horizontal y fuente160 % en la lista corta.
La primera ejecución mostró falta de espacio útil en horizontal; se compactó
la cabecera. Otra ejecución perdió el daemon Gradle; repetir con un trabajador
y heap1536MiB pasó. No se considera el intento interrumpido como validación.

Capturas: long-collapsed.png, long-expanded.png, landscape-font160.png.
Vídeo shelves.mp4, 7.48 s, expansión de ambos estantes, decodificado e inspeccionado.
Todas usan fixture sintético en las pantallas reales, Room, VM y Rust; no servidor
productivo ni Xiaomi. La fuente160 % se aplica solo al fixture, sin alterar ajustes
del dispositivo. En horizontal con fuente grande las listas tienen poca altura:
se desplazan individualmente para leer el contenido completo.

Pendiente: aceptación física por el usuario; resize manual de estantes del
escritorio fuera del pedido móvil. No se modifican estados ni datos del servidor.

## Sincronización de rotación y recreación — 2026-10-27

La prueba anterior pedía horizontal y sólo esperaba Compose idle: podía ejecutar
sus aserciones antes de que Android cambiara la configuración. La captura horizontal
original ya fue inspeccionada; esto corrige la garantía de la prueba, sin afirmar un
fallo del layout de producción ni cambiar sus dimensiones.

SidebarShelvesDeviceTest ahora fuerza retrato al iniciar cada caso (0/1/80 activos)
y espera simultáneamente configuración, medidas reales de decorView y bounds de Home
con el aspecto solicitado. En horizontal y después de recreation comprueba ambas
cabeceras visibles y completas dentro de Home, lista principal antes de Pospuestos y
cabeceras sin solaparse, con comparaciones estrictas. La escala160 % del caso corto se
lee de TextLayoutResult.layoutInput.density; no se infiere sólo del extra del Intent.
No se añadieron pausas ni tolerancias geométricas. No se modifican ajustes globales de
fuente/orientación, ni protocolo, ni código de producción.

Verificación final adicional: HomeListSectionsTest1/1, lintDebug y ambos APK debug
PASS; SidebarShelvesDeviceTest1/1 API34/emulator-5580,11.668 s. Las nueve muestras de
[configuración, bounds y escala real](rotation-api34.json) cubren retrato, horizontal
y horizontal recreado de los tres tamaños. En todos los horizontales decorView mide
2400×1080; el caso corto mantiene fontScale1.6 antes/después de recreation. Son datos
del fixture sintético real, sin aceptación física ni una nueva afirmación de paridad.
Se conservan las capturas/vídeo originales arriba; no se añadieron goldens ni pausas.
