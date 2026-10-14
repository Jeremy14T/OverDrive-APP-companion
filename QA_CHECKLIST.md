# Pruebas de aceptación — OverDrive Companion v1

**Estado en esta entrega:** la verificación de formatos de archivos y política de URL puede realizarse sin Android. Todas las pruebas UI/red necesitan **APK compilado + teléfono + URL real de OverDrive**. No se marcan como realizadas por anticipado.

| ID | Prueba en teléfono Android | Resultado esperado |
| --- | --- | --- |
| 01 | Instalar APK nuevo, abrir app | Configuración inicial, sin URL fija precargada |
| 02 | Elegir Cloudflare, pegar URL HTTPS real, Conectar | Dashboard de la instalación original; controles/cookies se manejan en OverDrive |
| 03 | Elegir Zrok, URL HTTPS real | Dashboard original, sin abrir Chrome para navegación interna |
| 04 | Tailscale VPN encendida, URL HTTPS / IP HTTP 100.64/10 | Carga si el servicio está conectado y responde |
| 05 | Tailscale VPN apagada | Pantalla de error; no dice que el túnel haya cambiado |
| 06 | Introducir `javascript:...`, `file:...`, `http://dominio-publico` | URL rechazada sin abrir contenido |
| 07 | Seleccionar Cloudflare e introducir `http://100.70.1.2:8080` | URL rechazada; HTTP solo bajo método Tailscale |
| 08 | Pulsar Escanear QR, conceder cámara, mostrar QR con URL | Lector de cámara funcional; dirección insertada en campo y visible antes de conectar |
| 09 | Escanear QR sin dirección válida | Mensaje de error, sin navegación |
| 10 | Abrir dashboard, cerrar proceso y volver a abrir | Recupera proveedor y URL y abre directamente panel |
| 11 | Abrir dashboard y pulsar ⚙ | Vuelve a configuración con el valor anterior intacto |
| 12 | Cortar túnel o detener servidor, reintentar | Pantalla de error en la carga principal, Reintentar / Cambiar conexión útiles |
| 13 | Forzar falla de una imagen/petición secundaria | Dashboard principal no se cierra por esa falla |
| 14 | Navegar entre varias páginas internas y usar Atrás | Historial WebView sin saltar a Chrome |
| 15 | Abrir cámara/Live y grabaciones disponibles | Se comporta según las capacidades de la web y del Android System WebView |
| 16 | Reproducir video / probar WebSocket | Funciona si el túnel, la URL y el WebView lo soportan |
| 17 | Acceder a panel con login/contraseña original | Se respeta el login original; no solicita credenciales nuevas de Companion |
| 18 | Simular error de TLS / certificado no confiable | No se ignora certificado; no se establece sesión insegura |
| 19 | Web con enlace externo HTTPS tocado por el usuario | Abre navegador solo para enlace externo, no para rutas originales |
| 20 | Reabrir con túnel que cambió de URL | Error recuperable, edición conserva URL anterior |
| 21 | Solicitud de permiso de cámara/micrófono en web | Permiso solamente a origen principal y tras consentimiento del sistema |
| 22 | Descarga normal HTTP(S) desde web | Se inicia la descarga; enlaces `blob:` pueden requerir trabajo adicional |
| 23 | Girar el teléfono, usar teclado, cambiar a modo oscuro Android | WebView y formulario aprovechan dimensiones y zonas seguras sin perder la conexión |

**Nota:** ni el estado de OverDrive en un BYD King México, ni las cámaras, transmisiones, telemetría, permisos reales, VPN, credenciales o WebSocket pueden certificarse con análisis estático. Se deben probar contra el vehículo y una URL auténtica.


## Comprobaciones adicionales v1.3 (ícono)

- [ ] El launcher muestra OD + señal con vehículo minimalista y fondo oscuro, sin un borde decorativo superpuesto.
- [ ] El ícono se ve centrado en lanzadores redondos y cuadrados (adaptive icon, Android 8+).
- [ ] El ícono de la pantalla de configuración y de la pantalla de error también muestra la nueva identidad.
- [ ] Actualizar la app (misma firma) conserva URL y la sesión si no ha expirado.
- [ ] Regresar de configuración a un WebView ya cargado no fuerza un nuevo login.
- [ ] El acceso `⚙ Conexión` aparece solo durante la primera entrada y desaparece a los 5 segundos o al tocar la página.
