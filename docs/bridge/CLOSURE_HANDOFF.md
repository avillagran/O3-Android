# Bridge closure handoff — 2026-10-26

Este incremento cierra una prueba única Android→JNI→QUIC→autoridad→shells de dos
servidores T3 **reales aislados**. No acredita NODE vivo, VQ físico ni Xiaomi.
El trabajo anterior de dos mitades queda como evidencia histórica, no como límite
actual de la prueba de laboratorio. Root conserva la responsabilidad del Dev Vault.

Android companion: https://github.com/avillagran/ot3-android/pull/26 (draft),
implementación `387481e`. Android usa la rama propia `bridge-catalog-e2e-frontend`, sobre
`e7e33db76b77b798e8a17b8e2bbc9aaf28a267e6` de PR25. No se modificaron la rama de
PR23, sus tests ni UI1g. No hubo release, tag, deployment, merge ni watch.

## Implementación

- Entrada compartida por enlace/QR `omabridge://pair/...`, con pending y reintento
  tras aprobación, selección persistida y carga del catálogo. Kotlin solo decodifica
  el framing de la URI; pairing, identidad, protocolo y reconciliación siguen en Rust.
  Las filas muestran autorizado/revocado/expirado/autoridad requerida; la vista de
  entornos muestra puente offline y permite refrescar. Sin SDK, la pantalla explica
  su ausencia y mantiene el pairing T3 manual.
- OmaBridge PR3, source SDK `f7d93a9ea8600991a229cadb9d189306965d4195`:
  autoridad local **opt-in**, sin bearer desktop copiado. Usa la CLI existente para
  emitir una sesión nueva con `orchestration:read`, subject por peer y TTL de 1–240 s
  (120 s por defecto). Comprueba UUID del emisor local; Android verifica el descriptor
  HTTPS seleccionado, scopes reales y expiración devuelta por `/api/auth/session`.
  Sin configuración explícita continúa DENY.
- La prueba integrada usa dos T3 `v0.1.3-nightly.20261026.3710`, cada uno con base-dir,
  runtime home, claves, proyecto e hilo propios; ningún provider run. Comprueba shells
  distintos, cruce de host y cliente denegado, escritura real por RPC denegada,
  vencimiento natural y revocación T3, y retirada del puente. Una credencial NUEVA que
  T3 acepta puede tomar propiedad local y conservarla tras retirar el puente.
- Room y Keystore no pueden hacer un commit conjunto. Repository/controller comparten
  un mutex de mutación; se persiste un gate de denegación antes de reemplazar o borrar
  credenciales. El reingreso del SDK no puede convertir ese estado parcial en autorizado
  usando solo un expiresAt anterior. Tras suspender por cierre de streams se vuelve a
  comprobar propiedad y ruta; una edición local gana y el timestamp de conexión no
  suprime una retirada.
- Tres pruebas Room instrumentadas verifican plan/stop con timestamp nuevo, adopción
  local durante aplicación/enrollment y fallos parciales de almacenamiento tras reabrir
  Room. Se preservan drafts, cursors, IDs y orden en el test de catálogo separado.

## Build, tests y evidencia

- Rust workspace/all-targets: 75 passed, 0 failed; Clippy warnings denied, también
  con el feature de laboratorio.
- Core JVM: 241 total, 237 passed, 4 skips preexistentes, 0 failed. Core lint y app
  compile pasan. Room/JNI/QUIC/T3 reales: **10/10**, sin skips. App completa: **26/26**.
- `scripts/verify-bridge-lab.py --t3-binary /absolute/t3 --full-ui` crea labs nuevos,
  exige el source fijado, verifica el build y ejecuta core y app secuencialmente.
  Falla si la prueba única no corre o se omite. Cierra/recolecta procesos y elimina
  logs/estado privados incluso al fallar. Manual completo en `REAL_T3_LAB.md`.
- Host, x86_64 y arm64 se reconstruyeron desde el MISMO source f7d93a9. Se verificaron
  variantes lab y production por separado. Gradle comprueba pin/ABI/target/hash;
  release rechaza lab-tls. Al entregar quedan candidatos production, con las operaciones
  y trust injection de laboratorio ausentes del ELF. T3 AAR/JNI siguen ambos en 0.3.4,
  source `e2ec546`; lib-t3-rust PR11 permanece sin cambios.
- `evidence/2026-10-26/closure/verification.json` separa fixture de catálogo, servidores
  reales aislados, UI fixture, NODE vivo y Xiaomi. `bridge-approved-ui.txt` captura
  semántica aprobada con campo vacío; SecureWindow bloquea deliberadamente screenshots
  de esa pantalla. No se desactivó para obtener una imagen.
- Observaciones preservadas: una corrida app anterior falló en el draft del test
  heredado ThreadWorkspaceDeviceTest:44; posteriores completas pasaron. Hubo errores
  nativos genéricos previos no reproducidos en la última fuente; se añadieron códigos
  fijos sin datos privados. No se afirma que el foco/lector CI esté resuelto ni se
  ocultan esas corridas. El listener opt-in captura screenshot/foco en un fallo futuro
  sin modificar asserts ni plazos de los tests heredados.

## UPSTREAM — instrucción vigente de Andrés

Los arreglos/mejoras que pertenecen a T3 deben prepararse y enviarse upstream,
continuando nuestros companions sin esperar merge. La conversación de licencias
no congela upstream. Esta regla sustituye la interpretación anterior.

El probe real mostró que `auth pairing create --ttl 2m` limita el enlace sin usar,
pero OAuth devuelve unos 30 días. Se preparó en fuente upstream 4e493b1492c295475e5c4d1b971677b1bd305960
un cap explícito `T3CODE_BOOTSTRAP_SESSION_TTL_SECONDS` para sesiones nuevas por
cookie/bearer/DPoP, sin cambiar defaults, scopes ni sesiones existentes. El cap no
extiende la hora actual de DPoP. Regresión primero roja (2592000 frente a 120), luego
39 tests focalizados verdes, formato/lint/typecheck del servidor verdes. El HTTP test
comprueba respuesta acotada y denegación de escritura; TestClock comprueba expiración
real del SessionStore y revocación individual. `check:changed` no pudo calcular merge
base en el archivo de fuentes sin checkout; se ejecutaron los checks focalizados.

El trabajo se verifica en un archivo de fuentes temporal, sin nuevo checkout o
worktree Git. La publicación usa una base temporal de objetos Git sin working tree.
PR upstream enviado: https://github.com/pingdotgg/t3code/pull/25361,
head `630314b400cb5cd2f90be60274d443240a409524`. La rama usa el main existente
171f8d8 del fork para no importar workflows ajenos con un PAT sin scope workflow.
Los siete archivos se fusionan limpiamente con 4e493b1 y el resultado es idéntico
byte por byte a la fuente de los 39 tests. Los intentos de publicar el main reciente
y sincronizar el fork fueron rechazados por workflow scope; no se amplió el permiso.
El PR final solo introduce código/tests/docs, sin workflows. No se espera su merge.

## Pendientes reales

- Distribución SDK/CI: no hay workflow nuevo; el PAT actual carece de scope workflow.
  Los scripts/Gradle verifican localmente, pero CI normal que omite lab/SDK no es
  prueba puente. No se amplió permiso ni se publicó artefacto/release.
- Aceptación desktop viva: NODE y VQ requieren validación separada de rutas/identidad
  y autoridad consentida en sus hosts. Este incremento no operó servicios productivos.
  Xiaomi sigue fuera del alcance.
- Root integra el companion sobre PR25 y sigue sus checks/reviews; PR23 permanece
  congelado sin merge. No esperar el merge upstream para continuar Android/OmaBridge.

Al terminar, el inventario de dispositivos muestra ot3a detenido. Los labs de la
verificación fueron cerrados y su estado privado eliminado. Root debe incorporar este
handoff y las URLs finales a Vault; el hijo no escribió el vault concurrentemente.
