# Feedback 5 — Iconos de proveedores

Implementación `2cbd832`, base `820a108`, AAR v0.3.5.

El selector real muestra la marca en el carril de proveedores, la fila de cada
modelo y la selección compacta. Se usa exclusivamente driverKind de models.catalog,
con coincidencia exacta, sin analizar nombre/slug de modelo ni etiqueta/instanceId.
Las cuatro marcas conocidas y el fallback ACP son los assets del escritorio
`7b5b1de`, ui/assets/icons y ui/js/IconAssets.js. Procedencia en
app/src/main/svg/lucide/NOTICE.md. El conversor existente preserva ahora la opacidad
interior de la marca que la usa. Los textos de proveedor conservan accesibilidad;
las imágenes son decorativas para evitar anuncios duplicados.

ProviderIconsDeviceTest1/1 API36.1 recorre cinco identidades en claro y oscuro,
todas con el mismo nombre y slug de modelo. Catálogo producido por Rust desde
server.getConfig sintético; navegación/lector/composer/selector reales.
ComposerModelsTest y pruebas del conversor pasan; lint/build pasan.
El primer intento señaló el icono compacto tapado por la hoja; se corrigió el
selector de la aserción para comprobar el icono del proveedor visible.
Un intento sin emulador se repitió después de reiniciar solo AVDpropio5584.

light.png, dark.png y custom-fallback.png: capturas del emulador propio
ot3-feedback361/API36.1. providers.mp4 (16.85s) muestra el selector claro con una
marca seleccionada; vídeo decodificado e inspeccionado. La cobertura de los cinco
proveedores y ambos temas está en la prueba, no se infiere del vídeo.
No son datos productivos ni aceptación Xiaomi. No se envió ningún mensaje.

Pendiente U34 en docs/LIB_REQUESTS.md: la lib valida registryIcon pero la ABI
binaria actual no obtiene sus bytes. Los proveedores de registro/desconocidos
muestran ACP hasta tener una lectura nativa acotada; no hay HTTP en Kotlin ni
heurísticas por nombre para simular la marca del registro. Aceptación física
pendiente del usuario. No se modificaron repositorios de referencia ni la lib.
