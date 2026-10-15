# OverDrive Companion — actualización v1.9.0 (interfaz)

Esta actualización es **solo de presentación** y parte del proyecto completo **v1.8.0**.

- Identidad de acceso rediseñada: logo centrado, nombre «OverDrive Companion» debajo y subtítulo «Tu acceso rápido a OverDrive». Se usa igual en primera configuración y en el listado de conexiones.
- El logo del encabezado utiliza `drawable-nodpi/app_logo_header.png`, un recorte sin los márgenes transparentes de la imagen anterior. El logo original `app_logo.png` se conserva para las otras pantallas.
- Botón principal con código QR vectorial reconocible, icono y texto **centrados como un grupo**: «Escanear código QR». El escáner ZXing sigue siendo el mismo; solo cambia la vista que invoca `launchQr()`.
- Mensajes de primera conexión, separador, ejemplo URL y campo de nombre abreviados. No se incrusta ninguna dirección real del usuario en el código.
- Se conservan inalterados el almacenamiento de perfiles, favoritos, última conexión, cookies y WebView, comprobación de Tailscale, timeout de 10 s, navegación y GitHub Actions.

**Validación:** análisis estático de XML, recursos e IDs y pruebas Kotlin/JVM disponibles. La compilación APK y la verificación visual en Android se deben realizar con el workflow del repositorio.

---

## Actualización v1.7.0 — Favoritas y cabecera

- En todas las pantallas de configuración (primera vez y conexiones guardadas), el logo aparece a la izquierda y el nombre «OverDrive Companion» a su derecha.
- En cada tarjeta de conexión, la estrella está arriba a la derecha: contorno si no es favorita, rellena si es favorita.
- Solo hay una favorita a la vez; al marcar otra se desmarca automáticamente la anterior.
- Al tocar una estrella rellena se quita esa favorita, y el inicio rápido pasa a utilizar la última conexión usada. La primera conexión guardada se sigue marcando automáticamente como favorita inicial.
- Se mantienen intactos el QR, los túneles, la autenticación del WebView y el temporizador/error de v1.6.

# OverDrive Companion — Android (v1.6.0)

## Actualización v1.6 — inicio visual y recuperación de Tailscale

- **Ingreso más claro:** encabezado compacto, breve tarjeta de bienvenida, escaneo QR como acción destacada, separador visual y pegado de URL en el mismo formulario. Los perfiles previamente guardados permanecen como estaban.
- **Consejo contextual:** al identificar una URL Tailscale por escritura, pegado o QR, o al elegir ese proveedor para una URL ambigua, se indica que Tailscale debe estar instalado y conectado **en este teléfono** a la red del vehículo. Aparece Abrir Tailscale o Instalar Tailscale, según esté instalada la aplicación oficial `com.tailscale.ipn`.
- **Sin pantalla blanca permanente:** mientras inicia la navegación principal aparece una pantalla oscura con indicador de carga. Un tiempo máximo de **10 segundos contados desde `loadUrl`** muestra la pantalla de error si no se confirma que se ha mostrado una página. Los fallos de navegación principal también producen error; los de recursos secundarios no cierran el panel.
- **Errores contextualizados:** Tailscale muestra recomendaciones para comprobar la app en el teléfono, el túnel en el coche y la dirección. Cloudflare y Zrok mantienen el mensaje genérico. **No se afirma ni se puede comprobar desde Companion que Tailscale está realmente apagado; el mensaje es una guía de comprobaciones.**
- **Reintentos limpios:** después de un error se recrea únicamente el WebView para evitar eventos tardíos de cargas fallidas, sin borrar cookies, historial de perfiles, favorita ni última conexión. Al volver desde configuración a un panel que ya funciona, sigue reutilizándose el mismo WebView sin recarga.
- **Sin permisos de VPN ni acceso nuevo a OverDrive:** el botón Tailscale abre la aplicación o su ficha oficial; no enciende una VPN sin consentimiento y no obtiene el código de acceso del vehículo.

**Validación de esta entrega:** pruebas Kotlin/JVM de políticas URL, detección y clasificación de errores ejecutadas localmente; XML/recursos revisados de forma estática. **La compilación Android y pruebas reales con Tailscale apagado o encendido quedan pendientes en GitHub Actions y un teléfono.**

## Actualización v1.5 — ingreso unificado y detección de túneles

- **Primera configuración simplificada:** no hay un botón de «ingreso manual» que abra un segundo formulario. El formulario de conexión se muestra directamente e incluye **Escanear QR** y **pegar URL** en la misma tarjeta.
- **Detección automática real:** al pegar una URL o leer un QR, se utiliza `ConnectionUrl.parseInput` y se identifica Cloudflare (`*.trycloudflare.com`, `*.cfargotunnel.com`), Zrok (`*.share.zrok.io`) y Tailscale (`100.64.0.0/10` y `*.ts.net`). En estos casos el proveedor se selecciona sin intervención manual y se guarda junto a la dirección tras la confirmación.
- **Sin clasificaciones inventadas:** los dominios personalizados y direcciones privadas no reconocibles no permiten saber con certeza qué túnel se usa. Solo entonces aparece un selector Cloudflare / Zrok / Tailscale. En ese selector la etiqueta dice **Tailscale**, sin «red privada».
- **Persistencia y navegación conservadas:** no cambia el formato de perfiles, la preferida, la última usada, la migración desde v1.3, la sesión del WebView, el lector QR ni el workflow de GitHub Actions.
- **Sin acceso nuevo al vehículo:** la lectura QR rellena y valida un formulario; el usuario confirma «Guardar y conectar». No se configuran ni encienden túneles del automóvil.

### Validación

En un entorno con Kotlin/JVM instalado, los tests rápidos pueden ejecutarse desde la raíz del proyecto:

```bash
kotlinc app/src/main/java/mx/overdrive/companion/ConnectionUrl.kt verification/UrlPolicySmoke.kt -include-runtime -d /tmp/url-smoke.jar
java -jar /tmp/url-smoke.jar
kotlinc app/src/main/java/mx/overdrive/companion/ConnectionUrl.kt verification/ProviderDetectionSmoke.kt -include-runtime -d /tmp/provider-smoke.jar
java -jar /tmp/provider-smoke.jar
```

Para compilar Android y ejecutar tests unitarios de Gradle, utiliza el workflow que ya contiene el proyecto. La validación de pantalla real requiere instalar y probar el APK en un teléfono.


Aplicación Android independiente, en **Kotlin + WebView nativo**, para abrir la **interfaz web original** de OverDrive desde un teléfono. **No instala nada en el BYD, no modifica OverDrive, no replica sus pantallas, no genera túneles y no tiene backend propio.**

Referencia: [OverDrive oficial](https://github.com/yash-srivastava/Overdrive-release) y [sitio oficial](https://www.overdrive.qd.je/).

## Funciones incluidas

- **Nuevo ingreso**: la primera vez se ofrece escanear el QR de OverDrive en el vehículo; introducir una URL es la alternativa.
- **Administrador de múltiples túneles**: perfiles independientes Cloudflare, Zrok y Tailscale con nombre y URL, almacenados localmente.
- **Elección de preferencia de inicio**: la primera conexión guardada se etiqueta favorita, pero la apertura automática usa la última seleccionada salvo que marques explícitamente una favorita (★); también puedes volver al modo “última usada”.
- **Prueba de accesibilidad** desde cada perfil, con una solicitud `HEAD` y tiempo de espera de 6.5 s. Indica si el servidor HTTP respondió; **NO** acredita que sea OverDrive ni que el túnel esté activo internamente o que la sesión esté autenticada.
- Detección **sugerida** de túneles a partir de URL/QR reconocibles: `*.trycloudflare.com`, `*.share.zrok.io`, `*.ts.net` y Tailscale `100.64.0.0/10`. Para dominios personalizados se pide confirmación del proveedor.
- Validación estricta de URL: HTTPS para todos los métodos; HTTP únicamente en Tailscale/red privada hacia una dirección IPv4 privada literal o 100.64.0.0/10.
- **Escaneo de QR real** con cámara (ZXing Android Embedded 4.3.0, orientación vertical); el usuario confirma la URL antes de conectar.
- Dashboard remoto cargado con el WebView del teléfono; JavaScript, DOM Storage, cookies de primera parte, WebSocket a través del motor Chromium/WebView.
- Persistencia privada de todas las conexiones, favorita, última usada y preferencia de inicio. **Migración automática** de la URL única guardada en v1.3, sin borrar preferencias ni cookies. Arranque directo en el túnel seleccionado de acuerdo a la preferencia.
- Navegación interna y Atrás; diálogo Cambiar conexión / Salir / Cancelar en la raíz de la web. Botón de conexión superior derecho únicamente durante la primera entrada, que se oculta a los 5 segundos o al tocar la web.
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
3. En la **primera ejecución**, pulsa **Escanear QR del vehículo** o pega la URL directamente en la misma tarjeta. El escáner no entra automáticamente: siempre confirmas la URL antes de guardar. Si detecta Tailscale, verás el consejo para tenerlo instalado y conectado en este teléfono.
4. Al confirmar **Guardar y conectar**, aparece el dashboard original de OverDrive con su autenticación habitual. La primera URL queda **favorita inicial**.
5. Para añadir otra: dentro del panel, usa Atrás de Android hasta la raíz → **Mis conexiones**, o el acceso de una sola vez de arriba a la derecha. Pulsa **Escanear otro QR** o **Agregar URL manualmente**. Cada túnel se guarda por separado.
6. En Mis conexiones, pulsa **Abrir** para cambiar sin volver a escribir la dirección; **Probar** comprueba si responde un servidor HTTP en esa URL; **Editar dirección** modifica una URL guardada. La estrella ★ **fija la favorita para siguientes arranques**, independientemente de la última utilizada; **Abrir la última usada al iniciar** devuelve el comportamiento de ingreso rápido por la última conexión seleccionada.
7. **Volver al panel** conserva el WebView cargado sin iniciar otra navegación. Abrir una URL/proveedor distinto sí inicia una navegación nueva y podría necesitar autenticación del servidor. Si un túnel falla, usa **Reintentar** o **Cambiar conexión**.

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
│       │   │   ├── ConnectionFailurePolicy.kt # Plazo y tipo de error
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
- Se guardarán las URLs completas proporcionadas por el usuario, incluidos parámetros si los tienen. La prueba de conexión usa `HEAD` a una URL que hayas guardado de forma explícita; no envía cookies ni sigue redirecciones. Es una prueba de conectividad a ese servidor, **no** de identidad de OverDrive ni salud de los demonios de túnel.
- El estado “Última usada” significa **última seleccionada**. Si una conexión falló, también puede seguir siendo la última seleccionada. Selecciona manualmente la favorita para tener un arranque estable. **No pegues contraseñas o tokens en la URL** salvo que el propio servicio lo exija; los parámetros de enlace también podrían ser credenciales.
- **Limitación de HTTP privado en Android:** Network Security Config no permite definir una excepción HTTP por rango IPv4/Tailscale. Para admitir HTTP privado, la configuración de transporte del proceso permite cleartext, **pero la política de navegación/carga de recursos en Companion rechaza HTTP público y solo permite subrecursos HTTP del origen privado configurado**. Esto **no es un firewall de red ni intercepta necesariamente todos los WebSockets/las peticiones originadas por JavaScript**. Por ello, para un entorno de máxima seguridad conviene quitar el soporte HTTP y usar exclusivamente HTTPS mediante un túnel fiable. HTTP no cifra la comunicación entre el móvil y el destino aunque Tailscale pueda cifrar su túnel.
- WebView no puede garantizar compatibilidad al 100% con el comportamiento de Chrome para todas las variantes del dashboard. La reproducción de video, cámaras, funciones que pidan contextos seguros, nuevas ventanas, SSO, `blob:`-downloads y ciertos permisos pueden tener limitaciones dependientes de Android System WebView y de la versión del servidor.
- El botón **Abrir Tailscale** usa visibilidad de paquete limitada a `com.tailscale.ipn` y, si no está instalado, ofrece Google Play. **No comprueba el estado interno de Tailscale ni certifica que la VPN esté activa**.
- El watchdog de 10 s mide la carga principal desde la solicitud y puede agotar el plazo en redes lentas aunque el túnel esté encendido. El error sugiere comprobaciones, no una causa definitiva. No se aplica a las peticiones secundarias una vez mostrado el panel.
- `RECORD_AUDIO` se solicita solo si la página, desde el origen configurado, solicita el micrófono; `CAMERA` se solicita al escanear un QR o si la web legítima pide usarla. Los controles del vehículo siguen estando dentro de **OverDrive**, no de Companion.

## Pruebas y estado

- `ConnectionUrl.kt`: validación y límites de IP privada comprobables con Kotlin/JVM, sin emulador.
- `ConnectionUrlTest.kt`: pruebas JUnit ejecutadas por el workflow antes de construir el APK.
- Sin SDK Android ni un URL real de OverDrive accesible en este entorno, **no se ha compilado un APK ni se ha hecho una prueba de dispositivo, cámara o dashboard en vivo**.
- Antes de confiar en la aplicación para operación remota, prueba en un teléfono real: Cloudflare, Zrok, Tailscale (VPN conectada/desconectada), QR, acceso autenticado, video y WebSocket, navegación y Atrás, pantalla de error al apagar el túnel, cambio de URL, reinicio y permisos. Si una función de OverDrive falla también en Chrome para la misma URL, el problema probablemente no está en esta envoltura.


## Ícono Android actualizado (v1.3.0)

- El nuevo icono conserva el emblema OD, las ondas de conexión y una silueta minimalista de vehículo, sobre el color oscuro de la identidad original.
- Se generaron PNG `mipmap-*` con tamaños **48, 72, 96, 144 y 192 px**, más sus variantes redondas.
- En Android 8+ se usa un **adaptive icon** con fondo oscuro fijo y símbolo transparente en `drawable-nodpi/ic_launcher_foreground.png`; el lanzador aplica la forma.
- `AndroidManifest.xml` ahora apunta a `@mipmap/ic_launcher` y `@mipmap/ic_launcher_round`. El icono dentro de las pantallas de configuración y error es `@drawable/app_logo`.
- No cambia el identificador `mx.overdrive.companion`, las URL guardadas, QR, cookies ni el software del vehículo.

**Importante al actualizar:** el workflow compila `app-debug.apk` con una firma de depuración que podría variar entre ejecuciones de GitHub. Si Android muestra un conflicto de firma, no desinstales sin antes considerar que al hacerlo se borrará la configuración local. Para actualizaciones continuas conviene usar una clave de firma estable.

**Estado de esta versión:** recursos generados, XML y referencias verificados estáticamente; no se ha compilado ni probado este cambio de icono en un dispositivo Android desde este entorno. GitHub Actions compilará y ejecutará las pruebas al subir la versión.

## Licencia / terceros

Este proyecto **no incluye, modifica ni redistribuye el código ni recursos de OverDrive**; la web la sirve tu propio vehículo. No es una aplicación oficial del proyecto OverDrive. ZXing Android Embedded es una biblioteca de terceros bajo Apache 2.0. Todos los accesos al vehículo están sujetos a la seguridad y responsabilidad de la instalación original.


## Registro de cambios v1.4.0

- Rediseño de primera configuración con QR prioritario.
- Perfiles múltiples: se migra la conexión única anterior desde `companion_connection` a JSON local en la misma preferencia.
- Primer perfil favorito inicial; por defecto se reabre la **última conexión elegida**. Marcar ★ habilita favorita fija; botón «Abrir la última usada al iniciar» revierte a último uso.
- Prueba `HEAD` con timeout y sin cookies, UI no bloqueante. Una respuesta HTTP no acredita autenticación ni disponibilidad interna del túnel.
- Mantiene el WebView, la corrección de insets, su navegación, el botón temporal superior derecho, los recursos del ícono v1.3 y el workflow de GitHub Actions.
- **Validación realizada aquí**: XML/referencias de recursos revisados y lógica `ConnectionUrl`, detección QR, `ProfileChoice` y `ConnectionProbe` compiladas con Kotlin/JVM; **compilación APK y pruebas en Android pendientes** hasta ejecutar GitHub Actions y probar el vehículo real.

## Versión 1.8.0 — Aviso previo de VPN e icono QR

- Al abrir un perfil **Tailscale**, Android consulta el estado de la red activa mediante `ConnectivityManager` y `NetworkCapabilities.TRANSPORT_VPN` (permiso normal `ACCESS_NETWORK_STATE`, sin diálogos de permiso adicionales).
- Si **no se detecta VPN activa**, se muestra inmediatamente un diálogo con tres opciones: **Abrir Tailscale**, **Conectar de todos modos** y **Mis conexiones**. **No afirma** que Tailscale esté desactivado, porque la comprobación no identifica a qué aplicación pertenece una VPN ni garantiza conectividad con el vehículo. Una conexión válida puede funcionar aun cuando Android no exponga el transporte VPN.
- Al regresar de Tailscale, se vuelve a comprobar **una vez**. Si Android ya informa VPN activa, se intenta cargar el panel; si no, la app permanece en Mis conexiones y puedes intentar abrirlo manualmente.
- Si hay VPN (o el estado es indeterminado), la carga sigue como siempre y conserva el **timeout de 10 segundos** y el mensaje especial al fallar el servidor Tailscale.
- Cloudflare y Zrok mantienen su comportamiento anterior. Ninguna comprobación modifica los túneles del coche, sus claves, cookies ni el panel original.
- Se añadió un vector QR nativo `res/drawable/ic_qr_code.xml` con tres patrones de esquina y módulos, utilizado en las acciones de escanear QR. No cambia el lector ZXing ni la identificación del proveedor.
- La lógica de aviso/retorno se separó en `TailscalePreflightPolicy.kt`, con pruebas JVM/JUnit. Los pasos de compilación del workflow **no fueron modificados**.

**Prueba en teléfono:** primero abre el APK con Tailscale desconectado (debería aparecer el aviso antes del WebView), pulsa **Conectar de todos modos** (debería continuar y usar el error a los 10 s si no responde), y prueba **Abrir Tailscale** activándolo y volviendo a Companion. Comprueba también con Tailscale ya activo y con un perfil Zrok/Cloudflare. Las pruebas JVM por sí solas no verifican cómo informa Android sobre VPN en tu modelo de teléfono.
