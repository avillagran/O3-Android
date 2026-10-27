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
