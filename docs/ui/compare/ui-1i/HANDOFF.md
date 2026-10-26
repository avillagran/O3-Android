# UI1i handoff to root

- Máquina: node; checkout propio `t3-20124707`, rama `feat/ui-1i-density-motion`,
  base `origin/feat/ui-1` `9df6d39`. Sin subdelegación ni worktrees nuevos.
- Decisión 2026-10-26: integrar PR21 (`0947436`, `a59c9a0`) con cherry-pick y conservar
  anexos UI1f/UI1h al resolver conflictos de UI_SPEC, LIB_REQUESTS y strings.
  UI1i sustituye la exclusión v1 de auras en §3.7/3.4.3 por petición actual de Andrés.
- Implementado: Home real con cielo/marca/logo/pills/búsqueda sin FAB; recupera 112 px
  (42,7 dp). Chat chrome 56 dp, roles y densidad ot3, composer mínimo enfocado, IME
  sin gaps. Catálogo sin launcher. Recorte horizontal consumido una vez, sidebar con
  280 dp de contenido. Metadata/navegación UI1h conservadas.
- Movimiento: aura real `threadaura.frag`; default nebula `reveal.frag`, spring solo
  fallback; stack Navigation Compose/predictive back; ticker compartido; llegada
  83+333 ms, salida opacity312, shelf placement520 InOutQuart. Off/sistema0 verificados.
- Verificado: JVM app114, core230 pasadas+4 omitidas; instrumentadas app24/core4;
  lint y golden verify; shader compile/reveal/aura, píxeles AGSL/Canvas, IME,
  launcher, rotación/borrador/scroll/un stream. Ver README para intentos descartados.
- Evidencia: capturas ANTES originales; después mismo theme/contenido; baseline rico
  reconstruido claramente rotulado, comparativa con fuente ot3, medidas JSON/tabla,
  vídeos 8,0/9,8/3,1 s y contacto nebula. Fixture real graph/VM/Rust no acredita por sí
  sola aceptación estética ni transporte vivo.
- APK: `artifacts/ot3-ui1i-debug.apk`, SHA256
  `fb26547c96d39e3676a51ba7acdf1701c5fcc2a3188c2cea122935870b1aa2a3`.
- Pendientes exactos: UI2 full `ThreadView.timeline` (roles, Markdown, actividad,
  sender/time/acciones/history); UI3 contrato composer/modelo/adjuntos/queue/steer.
  `CONTINUITY.md` contiene orden, fuentes y aceptación. Arc flight/departure drift y
  controles de estilos/pace/efectos pendientes; no declarar chat visual terminado.
- No probado: Xiaomi, live server, rendimiento físico, aprobación estética de Andrés,
  release firmada/build de distribución. No son bloqueos para este PR debug.
- Emulador detenido y sesión agent-device cerrada; root no necesita liberarlo.
  No se tocó UI1g ni se escribieron notas/vault concurrentes. Root debe actualizar
  Dev Vault con este resumen y cerrar UI1g por separado.
- Fuentes: clon ot3 `7b5b1de` solo lectura; PR21 importado; UI_SPEC addendum;
  README/CONTINUITY/measurements y pruebas instrumentadas en esta rama.
