# Bridge catalog integration handoff — 2026-10-26

Estado: incremento funcional aislado, NO aceptación de NODE productivo ni físico.
No se inspeccionó/operó Xiaomi. No se modificaron configuraciones productivas,
grants, servicios ni listeners existentes. Sin release/tag, merge ni watch.
UI2/PR23 y UI1g no se modificaron. El Dev Vault queda exclusivamente a cargo de root.

## Repositorios / commits

- Android: rama propia `bridge-catalog-integrated-sync`, sobre PR24 ya integrado a
  `feat/ui-1`; el PR companion compara solo este incremento.
- OmaBridge: https://github.com/avillagran/omabridge/pull/3, base `feat/client-catalog`
  (PR2), source candidato `04729573e2f77da651e24e12e3f40a625e34dd51`.
- lib-t3-rust: no cambios de fuente nuevos. SDK bridge consume pin `027a025` de PR11.
  El AAR y JNI T3 de Android siguen ambos en release 0.3.4, source `e2ec546`.
  Se compiló y descartó como integración el AAR candidato 027a025; no se mezcla con
  el JNI de release. SDK OmaBridge tiene biblioteca/ABI/lock propios.

## Implementado

1. Publicador desktop Rust `catalog-publish`: ejecutable absoluto local + único
   argumento fijo `connections-view`, sin shell ni comandos recibidos de red.
   Config privada con aliases explícitos a UUID, rutas HTTPS, allowlist por cliente.
   Verifica descriptor por TLS sin proxy/redirect; omite rutas nuevas no verificadas.
   Retiene ruta previamente verificada durante caída de sonda, pero aplica bajas
   explícitas. Poll 30s configurable; cambios + revisión bajo un mismo file lock.
2. SDK nativo separado (`mobile-sdk`, C ABI/JNI): identidad propia persistente,
   pairing auténtico iroh, selección/restauración de puente, lectura catalog,
   reconciliación/cache/TTL/proyección Rust y enrollment. Sin ciclo de dependencias.
   `scripts/build-bridge-sdk.sh` exige el source exacto de `libs/omabridge-native.lock`;
   genera host, Android x86_64 y arm64 con metadata/hash. No versiona binarios.
3. Autorización separada: `EnrollmentAuthority` explícita; Node/CLI por defecto
   DENIEGAN. Comprueba peer autenticado + environment autorizado antes/después de
   issuance; envelope por cliente/environment, solo lectura, máximo 5min.
   Canje Rust con `/oauth/token` existente comprueba descriptor, scopes y TTL
   realmente devueltos. No inventa bearer que T3 acepte ni copia tokens desktop.
4. Android `BridgeCatalogController`: marshaling/lifecycle/Room/Keystore; protocolo
   y decisiones de reconciliación/proyección/enrollment en Rust. Migración 2→3 añade
   tabla separada de provenance/status/expiración. Preserva IDs, orden, drafts,
   selection y cursors; los cambios de ruta no llaman al save que borra cursor.
   Detiene streams antes de aplicar retirada/cambio/expiry, fuera de transacción.
   Credenciales de filas gestionadas solo disponibles si autorizadas y vigentes.
   AppGraph expone `pairBridge(invitation)` y restaura selección; lifecycle y cambios
   de red refrescan en foreground. No se añadió pantalla/QR de invitaciones bridge.
5. Unavailable de fuente/IO distinto de Denied autenticado. La caída conserva cache;
   retirada auténtica persiste bloqueo incluso tras reinicio. Frescura se valida al
   recibir la respuesta, evitando rechazos al cruzar un segundo durante I/O.

## Evidencia y límites

- Suite Rust workspace/all-targets, fmt y Clippy con warnings denied.
- Publisher TLS real -> bridge QUIC real -> manager/proyección Rust: VQ+NODE,
  alias/UUID, rename, baja/recover, descriptor outage, fuente desaparecida,
  idempotencia/revisiones y revocación persistida.
- QUIC + autoridad/backend T3 fake aislado -> canje y HTTP reales lib-t3-rust:
  shells distintos por VQ/NODE, denegación token cross-environment, escritura
  denegada, cross-client denial y revocación backend/bridge. El backend de esta
  prueba es HTTP loopback; NO acredita rutas Android productivas ni un servidor
  T3 productivo. No hubo SEND vivo.
- JNI Android + QUIC real + Room en emulator-5580: catálogo VQ+NODE, pairing al
  puente sin pairing manual por host, IDs estables, rename/ruta, baja/recover,
  preservación de draft/cursor/token local independiente, revocación.
  La versión final añade cierre/reapertura de SDK sin re-pair/selectBridge.
- Las dos rutas anteriores prueban metadata/Room y autorización/shells por separado.
  NO se afirma una única prueba Android→T3-shells con autoridad productiva.
- Repeticiones con invitación ya reclamada se descartaron; el lab debe usar un
  directorio NUEVO para cada corrida. Las evidencias válidas usan lab nuevo.

## Reproducción local del emulador

1. Mantener AAR/JNI T3 0.3.4 coherentes; `scripts/build-bridge-sdk.sh` con clone limpio
   en source fijado. El script no crea clones/checkouts ni cambia ramas.
2. Iniciar `cargo run --locked --example catalog_mobile_lab -- /tmp/<new-lab-dir>`
   en el clone bridge. Solo imprime `port` + bridgeId públicos. Invitación se obtiene
   en memoria por HTTP loopback del lab; no se imprime ni guarda en evidencias.
3. `scripts/emulator.sh start --headless`, device_list/device_open; fijar siempre
   `ANDROID_SERIAL=emulator-5580`. Ejecutar `:core:connectedDebugAndroidTest` con
   `-Pandroid.testInstrumentationRunnerArguments.catalogLabPort=<port>`.
4. Parar lab vía `/stop` y `scripts/emulator.sh stop`. Nunca usar el Xiaomi.
   Sin catalogLabPort, la prueba de lab se omite explícitamente en CI normal.

## Pendientes concretos / siguiente incremento

- Implementar autoridad T3 productiva usando la autoridad existente, después de
  comprobar soporte real de scopes, TTL y revocación por cliente/environment.
  Fuente revisada: ot3 `connection_access.rs` (/api/auth/pairing-token) y lib
  `pairing.rs` (environment-bootstrap exchange). Esto NO prueba el contrato
  productivo acotado completo; por eso no se habilita autoridad por defecto.
- Incorporar frontend/QR de invitación bridge a `AppGraph.pairBridge`, con flujo
  pending/approved y señales de filas offline/revoked/authority-required visibles.
- Ejecutar una prueba única desde Android hasta los shells de ambos hosts usando
  esa autoridad aislada; hoy las dos mitades están verificadas por separado.
- Automatizar build SDK y lab en CI antes de habilitar la función en distribución.
  El candidato JNI no entra en un APK de CI normal sin ejecutar su build explícito.
- Luego validación viva read-only autorizada, y física solo si Andrés la autoriza.
  NODE productivo permanece abierto. Root integra PR23 y este companion por separado.

## Resultado final verificado (source SDK 0472957)

- Android core JVM: 241 tests, 0 fallos, 4 omitidos de integración preexistentes.
  Lint core y compilación Kotlin app pasan.
- Emulador Android 14, serial fijo emulator-5580: 6/6 instrumentadas pasan con
  candidato source 0472957, incluyendo close/open del SDK con selección persistida.
  Lab nuevo para la corrida final; lab y emulador detenidos después.
- JNI host + x86_64 + arm64: los tres generados desde el mismo source 0472957,
  ABI omabridge-mobile-1. Hashes/source y conteos en
  `docs/bridge/evidence/2026-10-26/verification.json`.
- lib-t3-rust queda limpio en su rama original feat/bridge-catalog-sync, sin fuente
  nueva ni PR adicional. Solo se usó una rama local de build para reconstruir JNI
  baseline 0.3.4 coherente con su AAR; no se cambió su PR11.
- Dev Vault / memoria compartida no se escriben desde el hijo; root incorpora este
  HANDOFF y sus PRs. No se hace watch desde este hilo hijo.
