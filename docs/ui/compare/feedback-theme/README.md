# Feedback 1 — Tema del escritorio y respaldo local

Implementación `3a5b9d9`, base main `820a108`, lib v0.3.5.

Apariencia ofrece una elección principal: seguir el tema del escritorio conectado
o elegir el de este teléfono. “Claro u oscuro según Android” queda bajo el tema
local o el respaldo. Editar modo/paleta de respaldo mantiene el seguimiento;
la elección explícita de teléfono lo desactiva. Se muestra el entorno y tema real,
o el motivo de respaldo (sin entorno, conectando, sin Omarchy, sin permiso o error).
Los textos nuevos tienen versión española e inglesa.

LiveThemeSource conserva la caché por entorno, lee al cambiar de entorno,
reconectar, volver al primer plano o actualizar. Comprueba cada15s visible
(antes180s), sin sondeos en segundo plano. `theme.tokens` y la API tipada existente
siguen en Rust. No existe evento de cambio Omarchy expuesto: es sondeo acotado,
no notificación instantánea. Se vuelve a localizar HOME al cambiar de conexión,
para evitar reutilizar rutas de un host anterior. Ante desconexión/error se
conservan colores y ahora se indica guardado hasta una lectura correcta.
Sin Omarchy/scope se elimina esa caché y se muestra respaldo explícito.

## Contraste con la build instalada

El registro local artifacts/editor-820a108/verification.json del checkout principal
acredita instalación Success del APK820a108 en23090RA98G, sin operar su UI.
Recalculado el SHA del APK local: coincide con b5b02efebee94d4d54ec01e32021b4db72f4f55f7da6f6d4ebc127ea098850f6.
`aapt2 dump resources` confirma “Seguir el sistema” y el interruptor separado
“Seguir el tema Omarchy del entorno”, iguales al código820a108.
En esa build `AppearanceSettingsViewModel.setMode` desactiva followEnvironment:
elegir “Seguir el sistema” podía apagar el seguimiento del escritorio. Elegir
una paleta también lo apagaba. Es una causa reproducible de la confusión descrita;
Android claro/oscuro nunca significó sincronizar la paleta del escritorio.

No se inspeccionaron preferencias, permisos ni host del Xiaomi: no se puede
atribuir con certeza su caso individual a esa acción. Un host sin Omarchy o sin
filesystem:read tampoco puede sincronizar; la brecha de hosts del puente sigue
abierta y pertenece al integrador (#39 integrado/#40 draft093be6d). No se tocó el
teléfono ni se instaló nada en él. Esta revisión del APK usa la evidencia previa,
no una nueva comprobación física.

## Verificación

-9 pruebas LiveThemeSource: selección/cache/reinicio, cambio de entorno, reconexión,
  primer plano/pausa, edición sin cambiar nombre, NoScope/NotOmarchy/error.
  Añadida etiqueta guardado tras desconectar y retirada tras reconectar.
- LiveThemeApplicationTest confirma tokens reales prevalecen sobre modo Android,
  sin perder composición/borrador al cambiar a respaldo. Build y lint pasan.
- OmarchyThemeSelectionTest1/1 API36.1: editar respaldo mantiene seguimiento,
  elección local aplica y persiste tras recreación, volver a seguimiento.
- LiveOmarchyThemeDeviceTest1/1: lectura real del escritorio Omarchy, Matte Black,
  AppGraph/ConnectionManager/transporte nativo/theme.tokens, fromCache=false.
  Credencial temporal10min de solo lectura, revocada; archivo privado eliminado,
  túnel y reverse retirados, harness inactivo. Tema del escritorio sin modificar.

Evidencia en AVD propio ot3-feedback361/emulator-5584:
live-desktop.png y live-verification.json son servidor real autorizado;
local-choice.png muestra elección local sobre ese contexto;
fallback-no-environment.png muestra respaldo sin servidor configurado.
Vídeo desktop-follow.mp4 (19.63s) inspeccionado: cambiar respaldo no apaga
seguimiento; elegir teléfono cambia paleta local y volver recupera escritorio.
Las capturas aún tienen bottom porque este PR es independiente del punto2/PR52.

Pendiente: aceptación física por el usuario; no se cambió el tema productivo para
simular una actualización en vivo. Los cambios/reinicio/reconexión/errores están
cubiertos con transporte determinista; lectura actual real sí acreditada.
