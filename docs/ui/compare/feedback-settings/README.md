# Feedback 2 — Ajustes sin navegación inferior

Implementación: `f87df20`, sobre `main` `820a108`, lib fijada v0.3.5.

El shell oculta la barra al entrar en cualquier destino de SettingsGraph,
incluidos emparejamiento y escáner. No compone la barra ni reserva su altura.
Ajustes y Entornos incorporan título y Volver; las páginas existentes conservan
sus cabeceras. Archivo y Licencias siguen pendientes funcionalmente, pero tienen
cabecera y Volver. La raíz es desplazable con fuentes grandes o poca altura.
Volver desde Ajustes abre Home incluso si Chats retenía un hilo.

Validación: 16 pruebas JVM de navegación, lint y compilación; prueba instrumentada
SettingsNavigationDeviceTest 1/1 en API36.1, AVD propio ot3-feedback361,
serial emulator-5584. Recorre Apariencia, Notificaciones, Entornos, Uso,
Archivo y Licencias; comprueba ausencia de barra y retorno en horizontal.
El primer intento detectó que el fixture delegaba Archivo al placeholder sin
cabecera: se corrigió su enlace y la repetición pasó. El shell sigue ocultando
la barra con IME visible; las pantallas conservan sus insets propios.

Capturas y vídeo de la aplicación real instalada en ese emulador, sin entornos
configurados ni servidor: settings.png, environments.png, home-restored.png,
navigation.mp4 (28.4 s). Vídeo decodificado e inspeccionado. La instrumentación
usa servidor sintético WorkspaceFixture, navegación y pantallas reales.
No es evidencia de servidor productivo ni de aceptación física. Xiaomi intacto.

Pendiente: aceptación en Xiaomi por el usuario; funcionalidades de Archivo y
Licencias fuera de este incremento; auditoría integral de formularios con IME.

Seguimiento CI: la suite completa detectó que QuotaStripDeviceTest aún intentaba
pulsar Chats desde Ajustes. Ahora usa Volver de Ajustes, conforme a la nueva UI.
Las cuatro pruebas combinadas de navegación de Ajustes y QuotaStrip pasaron en
API36.1 tras integrar main152b851 (tema/PR54). No se eliminó ninguna aserción de
suscripción, persistencia, desconexión ni reconexión.
