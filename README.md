# OverDrive Companion — Android (v1.0.0)

Aplicación Android independiente, en **Kotlin + WebView nativo**, para abrir la **interfaz web original** de OverDrive desde un teléfono. **No instala nada en el BYD, no modifica OverDrive, no replica sus pantallas, no genera túneles y no tiene backend propio.**

Referencia: [OverDrive oficial](https://github.com/yash-srivastava/Overdrive-release) y [sitio oficial](https://www.overdrive.qd.je/).

## Funciones incluidas

- Pantalla de configuración con **Cloudflare**, **Zrok** o **Tailscale**.
- Validación estricta de URL: HTTPS para todos los métodos; HTTP únicamente en Tailscale/red privada hacia una dirección IPv4 privada literal o 100.64.0.0/10.
- **Escaneo de QR real** con cámara (ZXing Android Embedded 4.3.0, orientación vertical); el usuario confirma la URL antes de conectar.
- Dashboard remoto cargado con el WebView del teléfono; JavaScript, DOM Storage, cookies de primera parte, WebSocket a través del motor Chromium/WebView.
- Persistencia privada de la selección y URL, arranque directo en la conexión guardada.
- Navegación interna y Atrás; botón de configuración flotante discreto dentro de la pantalla de la web.
- Fallos de la navegación principal: pantalla Reintentar / Cambiar conexión, con tiempo de espera inicial de 30 s. Los errores de recursos secundarios no cierran el panel.
- Se permite al WebView solicitar permisos de cámara/micrófono de **su mismo origen**, con autorización Android. Admite selección de archivos y descarga de archivos HTTP(S) (no blobs descargables).

## Obtener un APK sin instalar Android Studio (GitHub Actions)

**El ZIP contiene el código fuente, no un APK ya compilado.** Su estructura incluye `.github/workflows/android-apk.yml`, que instala Android SDK 35, usa Gradle 8.9, ejecuta tests y produce un **APK de depuración instalable**.

1. Extrae `OverDriveCompanion.zip` en una carpeta llamada `OverDriveCompanion`.
2. En [github.com](https://github.com/), crea un repositorio **privado** vacío, por ejemplo `overdrive-companion`, sin README inicial.
3. Si tienes [Git para Windows](https://git-scm.com/downloads/win), abre **Git Bash** dentro de la carpeta extraída y ejecuta (sustituye `TU_USUARIO`):

   ```bash
   git init
   git add .
   git commit -m "Primera version OverDrive Companion"
   git branch -M main
   git remote add origin https://github.com/TU_USUARIO/overdrive-companion.git
   git push -u origin main
   ```

   Si Git necesita autenticación, inicia sesión con Git Credential Manager. Alternativa sin línea de comandos: [GitHub Desktop](https://desktop.github.com/), **File > Add local repository > Create a repository here**, luego **Publish repository** y marca **Keep this code private**.
4. Abre en GitHub **Actions > Compilar OverDrive Companion APK** y selecciona **Run workflow > Run workflow**. También se ejecuta automáticamente al subir cambios a `main`.
5. Cuando la ejecución termine correctamente, entra al trabajo y baja el artefacto **`OverDrive-Companion-debug-APK`**. Descomprime el artefacto: dentro encontrarás `app-debug.apk`.
6. Transfiere el APK al Android, permite instalar aplicaciones de esa fuente si Android lo solicita e instala. La aplicación aparecerá como **OverDrive Companion**.

Si la ejecución falla, **no habrá artefacto válido**. Revisa los detalles de los pasos `Ejecutar pruebas unitarias` y `Compilar APK debug` antes de intentar instalar nada. Un APK de debug **no es una publicación firmada para Play Store**.

## Abrir en Android Studio (opcional)

Abre la carpeta raíz del proyecto, deja que sincronice Gradle e instala el SDK 35. `Build > Build App Bundle(s) / APK(s) > Build APK(s)` o en terminal con Gradle 8.9:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

En esta entrega no se incluye un `gradlew`/wrapper JAR: GitHub Actions instala explícitamente Gradle 8.9, y Android Studio puede usar su Gradle configurado. No se simula la compilación local sin SDK.

## Uso con tu BYD

1. Configura en OverDrive (instalado en el vehículo) el túnel remoto que prefieras.
2. Abre OverDrive Companion en tu teléfono.
3. Selecciona Cloudflare, Zrok o Tailscale, y pega o escanea **el enlace real generado por OverDrive**. El botón de QR accede a la cámara bajo permiso de Android.
4. Pulsa Conectar; aparecerá **exactamente la web servida por tu instalación de OverDrive**, incluidos sus controles y login existentes, si el túnel y el servidor están accesibles.
5. En próximos usos se recupera esa dirección. Para editarla, toca `⚙` abajo a la derecha. Si hubo error, usa **Reintentar** o **Cambiar conexión**.

**Tailscale:** el teléfono debe estar en la misma red Tailscale que el vehículo y con la VPN activa. Una URL bien formada **no prueba** que el túnel esté operativo. Usa HTTPS cuando esté disponible. No se configuran ni autentican túneles desde Companion.

## Estructura

```text
OverDriveCompanion/
├── .github/workflows/android-apk.yml   # Compilación y artefacto APK
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/mx/overdrive/companion/
│       │   │   ├── MainActivity.kt             # UI / WebView / QR / errores
│       │   │   ├── PortraitQrCaptureActivity.kt
│       │   │   ├── ConnectionStore.kt         # SharedPreferences privadas
│       │   │   └── ConnectionUrl.kt           # Validación de URL/origen
│       │   └── res/                          # Pantallas, textos, colores y seguridad
│       └── test/.../ConnectionUrlTest.kt      # Tests de lógica de URL
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Seguridad: decisiones y límites importantes

- **Ningún certificado TLS inválido se acepta**: `onReceivedSslError` cancela. No hay bridge JavaScript y se deshabilita acceso a `file://`/a contenido arbitrario desde la web. Depuración WebView desactivada.
- Aun cuando el túnel se seleccione de forma explícita, **no se verifica criptográficamente que una URL pertenezca a tu vehículo**. El usuario debe revisar la URL, especialmente la de un QR, y no confiar en enlaces de desconocidos.
- Los enlaces externos por HTTPS iniciados por el usuario se abren en el navegador; los enlaces internos del **mismo origen** permanecen en WebView. Las redirecciones a proveedores SSO externos y las ventanas emergentes pueden requerir una adaptación futura de seguridad, según la configuración real del servidor.
- Por defecto no se habilitan cookies de terceros. Si la autenticación del túnel las requiere, esa integración debe evaluarse por separado; no se ignora la autenticación existente.
- Las preferencias se guardan **solo en almacenamiento privado de la app** (`SharedPreferences`, `allowBackup=false`); no se transmiten a infraestructura de Companion. La propia conexión al servidor/túnel y la web pueden, naturalmente, intercambiar datos conforme a OverDrive.
- Se guardará la URL completa proporcionada por el usuario, incluidos parámetros si los tiene. **No pegues contraseñas o tokens en la URL** salvo que el propio servicio lo exija; los parámetros de enlace también podrían ser credenciales.
- **Limitación de HTTP privado en Android:** Network Security Config no permite definir una excepción HTTP por rango IPv4/Tailscale. Para admitir HTTP privado, la configuración de transporte del proceso permite cleartext, **pero la política de navegación/carga de recursos en Companion rechaza HTTP público y solo permite subrecursos HTTP del origen privado configurado**. Esto **no es un firewall de red ni intercepta necesariamente todos los WebSockets/las peticiones originadas por JavaScript**. Por ello, para un entorno de máxima seguridad conviene quitar el soporte HTTP y usar exclusivamente HTTPS mediante un túnel fiable. HTTP no cifra la comunicación entre el móvil y el destino aunque Tailscale pueda cifrar su túnel.
- WebView no puede garantizar compatibilidad al 100% con el comportamiento de Chrome para todas las variantes del dashboard. La reproducción de video, cámaras, funciones que pidan contextos seguros, nuevas ventanas, SSO, `blob:`-downloads y ciertos permisos pueden tener limitaciones dependientes de Android System WebView y de la versión del servidor.
- `RECORD_AUDIO` se solicita solo si la página, desde el origen configurado, solicita el micrófono; `CAMERA` se solicita al escanear un QR o si la web legítima pide usarla. Los controles del vehículo siguen estando dentro de **OverDrive**, no de Companion.

## Pruebas y estado

- `ConnectionUrl.kt`: validación y límites de IP privada comprobables con Kotlin/JVM, sin emulador.
- `ConnectionUrlTest.kt`: pruebas JUnit ejecutadas por el workflow antes de construir el APK.
- Sin SDK Android ni un URL real de OverDrive accesible en este entorno, **no se ha compilado un APK ni se ha hecho una prueba de dispositivo, cámara o dashboard en vivo**.
- Antes de confiar en la aplicación para operación remota, prueba en un teléfono real: Cloudflare, Zrok, Tailscale (VPN conectada/desconectada), QR, acceso autenticado, video y WebSocket, navegación y Atrás, pantalla de error al apagar el túnel, cambio de URL, reinicio y permisos. Si una función de OverDrive falla también en Chrome para la misma URL, el problema probablemente no está en esta envoltura.

## Licencia / terceros

Este proyecto **no incluye, modifica ni redistribuye el código ni recursos de OverDrive**; la web la sirve tu propio vehículo. No es una aplicación oficial del proyecto OverDrive. ZXing Android Embedded es una biblioteca de terceros bajo Apache 2.0. Todos los accesos al vehículo están sujetos a la seguridad y responsabilidad de la instalación original.
