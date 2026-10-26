# UI-N handoff to root

- Máquina: node; checkout T3 propio `t3-be03606f`, rama `ui-n-notifications-live-updates`,
  base `origin/feat/ui-1` `726ff02` (PR24 integrado; PR23 no tocado). Sin subdelegación,
  sin worktrees/top-levels nuevos, sin tocar UI1g ni el agente bridge, sin emulador.
- Alcance: **preparación**. UI_SPEC §4.14 «Notifications and Live Updates», fila UI-N en
  §5 (después de UI-2a y catálogo puente; fases previas sin renombrar ni cerrar), U28 en
  §6 y LIB_REQUESTS, DECISIONS 2026-10-26, aceptación/matriz API en `README.md`,
  licencias/NOTICE. Código solo de presentación: canales, tarjeta, alertas, chip, lock
  screen, Live Update settings, parser del contrato propuesto e icono `ic_stat_ot3`.
  Sin servicio, sin manifest, sin ajustes UI, sin lógica de protocolo/contadores Kotlin.
- Verificado (2026-10-26): `:app:testDebugUnitTest` 159/159 (incluye 44 de
  `NotificationPresentationTest` en API 24/26/33/36 + `NotificationIconTest`);
  `:app:lintDebug` sin errores ni avisos nuevos; `tools/import-ot3-strings --check` OK.
  Robolectric prueba solicitudes, no promoción real.
- Hallazgos clave: `sidebar.view` ya da `counts` y `pending/reason/revision`; no existe
  transición/baseline/alerta/expiración en la lib → **U28 `awareness.view` bloquea el
  runtime**. Promoción = API 36.1 (QPR2); `canPostPromotedNotifications` en SDK 36;
  `POST_PROMOTED_NOTIFICATIONS` constante desde SDK 37.0. FGS elegido `specialUse`
  (dataSync: tope 6 h/24 h targetSdk 35+). Idle stop 15 min por defecto.
- Upstream (política vigente de Andrés: preparar, **preguntar antes**, no enviar ni
  abrir/reabrir PR sin aprobación explícita): **ningún bug confirmado** en
  `AgentNotifications.kt`/`AgentActivityPresentation.kt`/doc T3 @`680152e27b`; no se
  preparó parche ni se envió nada. Observaciones menores para revisión: ids de alerta por
  `hashCode()` (colisión teórica); `NotificationCompat.setSilent(true)` mete la tarjeta en
  grupo sintético `"silent"` en API 26+ (comprobado en Robolectric; inocuo según la guía).
  Discrepancia de documentación **de Android** (no T3): la guía cita
  `ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`, inexistente en SDK 36/37.
- Emulador: solo inventario. Instalada únicamente `android-34 google_apis`; disponible sin
  instalar `system-images;android-36.1;google_apis;x86_64` rev 4. No arrancado.
- Siguiente (tras UI-2a y catálogo puente): U28 en lib → leases en `ConnectionManager` →
  `FollowService` specialUse + permisos + Ajustes → imagen 36.1 por root → capturas chip,
  lock screen, shade, alertas → APK pre-release. Approve depende de la tarjeta visible UI-4.
- No probado/no afirmado: chip real, lock screen físico, OEM, Xiaomi, servidor vivo,
  release. No hay capturas ni vídeos de UI-N.
- Fuentes: ot3 `7b5b1de` (`BarWidget.qml:20-22`, `T3BarIndicator.qml:117-120`,
  `src/main.rs:54-144`); lib v0.3.4 `e2ec546` (`src/sidebar/view.rs`); T3 `680152e27b`
  (módulo Android, `packages/shared/src/agentAwareness.ts`, relay `agentActivity/*`,
  `docs/operations/android-notifications.md`); URLs Android oficiales en `README.md`.
- Root: actualizar Dev Vault con este resumen (no se escribió el vault).

## Root acceptance corrections (2026-10-26)

- Full lock-screen card is the default explicitly requested by Andrés; status-only and
  hidden remain options. System privacy settings still apply.
- Application label is OT3. Short critical text stays within the platform budget; the
  combined rendered brand/state chip still needs API 36.1 evidence.
- No upstream submission is authorized without asking Andrés first.
- Root verification after corrections: 44 presentation cases (API 24/26/33/36) and
  app lint passed; no device was started.
