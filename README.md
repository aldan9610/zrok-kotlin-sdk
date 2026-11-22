# zrok-kotlin-sdk

SDK de **Kotlin/Android** para [zrok](https://github.com/openziti/zrok): compartición segura de
servicios y túneles TCP sobre redes [OpenZiti](https://openziti.io).

El SDK cubre las **dos capas** de zrok:

| Capa | Qué hace | Módulo |
| --- | --- | --- |
| **Control (REST)** | Habilitar entorno, crear/eliminar shares y accesses | `zrok-api` (generado desde la especificación oficial) + `zrok-core` |
| **Datos (túnel)** | Transporte real de bytes sobre la red OpenZiti | `zrok-core` (`ZrokTunnel`) + `org.openziti:ziti` |

```
┌────────────────────────────────────────────────────────────────┐
│                       Tu app Android / JVM                     │
├────────────────────────────────────────────────────────────────┤
│  ZrokManager (REST)          ZrokTunnel (datos)                │
│  · enable / disable          · hostTcpTunnel   (compartir)     │
│  · createProxyShare          · dialShare       (consumir)      │
│  · createTcpTunnelShare      · startLocalAccess (puente local) │
│  · createAccess / delete     · piping bidireccional            │
├────────────────────────────────────────────────────────────────┤
│  zrok-api (generado con OpenAPI Generator, Ktor + kotlinx)     │
├────────────────────────────────────────────────────────────────┤
│  org.openziti:ziti (ziti-jvm / ziti-android)  ← red OpenZiti   │
└────────────────────────────────────────────────────────────────┘
```

## Módulos

| Módulo | Contenido |
| --- | --- |
| `specs/` | Especificación Swagger de zrok fijada al tag **v2.0.11** ([specs/zrok.yml](specs/zrok.yml)). |
| `zrok-api` | Cliente REST **generado automáticamente** con OpenAPI Generator 7.27.0 (kotlin / jvm-ktor / kotlinx-serialization). 6 APIs `suspend`, ~95 modelos `@Serializable`, auth `x-token` automática. |
| `zrok-core` | Lógica de negocio: `ZrokEnvironment`, `ZrokManager`, `ZrokTunnel` y almacenes de estado (en memoria, fichero cifrado en disco, o el cifrado de Android). |
| `zrok-android` | `EncryptedZrokStateStore` (EncryptedSharedPreferences) y `ZrokTunnelService` (ForegroundService con reconexión automática de red). |
| `sample-android` | App de ejemplo completa. |

## Requisitos

- **Android**: minSdk 26, compileSdk 34.
- **JVM**: Java 17+.
- Para compilar: JDK 17+, Android SDK (platform 34). Los tests de `zrok-core` no
  necesitan Android.

## Inicio rápido en Android

### 1. Dependencias

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
}

// build.gradle.kts del módulo
dependencies {
    implementation("io.github.aldan9610.zrok:zrok-android:$version") // o project(":zrok-android")
}
```

Declara los permisos del servicio en tu `AndroidManifest.xml` (el del SDK ya los
declara; si tu app los sobrescribe, incluye):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 2. Habilitar el entorno (una vez por dispositivo)

El *account token* se obtiene en la consola de zrok (`https://console.zrok.io`
→ Your Account → Account Token). El SDK lo guarda **cifrado** junto con la
identidad OpenZiti que devuelve el servidor:

```kotlin
val environment = ZrokEnvironment(
    stateStore = EncryptedZrokStateStore(context),
)

// La primera vez (o tras un disable):
environment.enable(accountToken = "tu-token", description = "mi-app-android")
```

`enable` llama a `POST /api/v2/enable`, recibe la identidad ziti del entorno y
el JSON de identidad de OpenZiti, y lo persiste cifrado. En arranques
posteriores el `ZrokEnvironment` se recupera solo (si hay estado persistido,
`isEnabled` es `true`).

### 3. Compartir un servicio TCP (lado host)

```kotlin
val manager = ZrokManager(environment)

// Crea el share en la API REST (privado por defecto)
val share = manager.createTcpTunnelShare("127.0.0.1", 8080)

// Abre el túnel de datos y mantenlo vivo con el ForegroundService
ZrokTunnelService.startHostTunnel(
    context, share.token,
    targetHost = "127.0.0.1", targetPort = 8080,
)
```

Cada conexión que llegue por la red ziti se reenvía a `127.0.0.1:8080` con
piping bidireccional (misma semántica que el backend `tcpTunnel` del SDK Go).

Para exponer servicios web también hay `createProxyShare(url)` (público con
URL `https://<token>.share.zrok.io`) y `createWebShare(ruta)`.

### 4. Consumir un share privado (lado cliente)

```kotlin
val manager = ZrokManager(environment)

// Registra el access en la API REST
val access = manager.createAccess(shareToken = "token-que-te-compartieron")

// Puente local: la app se conecta a 127.0.0.1:puerto como a cualquier socket
ZrokTunnelService.startLocalAccess(context, shareToken = "token-que-te-compartieron")
// El puerto real queda en el estado del servicio:
lifecycleScope.launch {
    ZrokTunnelService.state.collect { estado ->
        if (estado is ZrokTunnelServiceState.Running && estado.localPort != null) {
            // conectar a 127.0.0.1:${estado.localPort}
        }
    }
}
```

Sin servicio, también puedes conectar directamente con streams:

```kotlin
val tunnel = ZrokTunnel(environment)
tunnel.dialShare("token-del-share").use { conn ->
    conn.outputStream.write("PING\n".toByteArray())
    conn.inputStream.read() // ...
}
```

### 5. Limpiar

```kotlin
ZrokTunnelService.stop(context)        // detiene el túnel y el servicio
manager.deleteShare(share.token)       // borra el share en el servidor
manager.deleteAccess(access)           // borra el access
environment.disable()                  // deshabilita el entorno (borra estado local)
```

## Inicio rápido en JVM (escritorio/servidor)

`zrok-core` funciona sin Android (usa `FileZrokStateStore`):

```kotlin
val environment = ZrokEnvironment(
    stateStore = FileZrokStateStore(), // ~/.zrok-kotlin/environment.json
)
environment.enable(accountToken = "tu-token")

val manager = ZrokManager(environment)
val tunnel = ZrokTunnel(environment)

val share = manager.createTcpTunnelShare("127.0.0.1", 5432)
val server = tunnel.hostTcpTunnel(share.token, "127.0.0.1", 5432)

readLine() // mantener vivo

server.close()
manager.deleteShare(share.token)
environment.disable()
```

## Arquitectura y decisiones de diseño

### Cliente REST generado, no escrito a mano

`zrok-api` se genera desde la spec oficial con la tarea
`./gradlew :zrok-api:openApiGenerate` (wired a `compileKotlin`, así el build
siempre está sincronizado con `specs/zrok.yml`):

- `generatorName=kotlin`, `library=jvm-ktor`, `serializationLibrary=kotlinx_serialization`,
  `dateLibrary=java8`.

**Nota técnica**: la combinación `jvm-ktor` + `kotlinx_serialization` no está
soportada oficialmente por OpenAPI Generator (el generador fuerza jackson
internamente). El flag de plantilla `kotlinx_serialization` sí se propaga, de
modo que los modelos se generan con `@Serializable`/`@SerialName` usando las
ramas oficiales de las plantillas; el único hueco (un `ContentNegotiation`
vacío en el `ApiClient` generado) se completa desde `zrok-core` inyectando el
converter kotlinx-json por constructor. Cero plantillas parcheadas.

### Content-Type de zrok

La spec declara `consumes/produces: application/zrok.v1+json` y el servidor
(go-openapi) valida la cabecera. El SDK registra el converter kotlinx para ese
tipo exacto y reescribe la cabecera en `HttpRequestPipeline.Before` —
verificado con tests contra un servidor HTTP real local.

### El servicio ziti se llama como el token del share

Semántica verificada en `sdk/golang/sdk/listener.go` y `dialer.go`: lado host
`Listen(shrToken)`, lado cliente `Dial(shrToken)`. En ziti-jvm:
`openServer()+bind(ZitiAddress.Bind(token))` y `open()+connect(ZitiAddress.Dial(token))`.
`ZitiGateway` abstrae esto (y permite los tests del plano de datos sin red).

### Versiones de ziti alineadas

`zrok-core` usa `org.openziti:ziti:0.25.1` porque es exactamente la versión
sobre la que está construido `org.openziti:ziti-android:0.30.0`: JVM y Android
comparten la misma implementación. En Android se excluye `lazysodium-java`
(escritorio) porque `ziti-android` aporta `lazysodium-android` vía su
`AndroidCryptoLoader` (ServiceLoader).

### Almacenamiento seguro

- Android: `EncryptedZrokStateStore` → EncryptedSharedPreferences con clave
  maestra en Android Keystore (token de cuenta + identidad ziti cifrados).
- JVM: `FileZrokStateStore` → JSON con permisos `600` (documentado como no
  cifrado; en Android usar el cifrado).

### ForegroundService y reconexión

`ZrokTunnelService` mantiene el túnel con notificación persistente
(`foregroundServiceType="dataSync"`, requisito de Android 14+) y reconecta:
al perder la red marca el estado y al recuperarla recrea el contexto ziti y los
endpoints. El estado es observable con `ZrokTunnelService.state`
(`StateFlow<ZrokTunnelServiceState>`).

## Tests

```bash
./gradlew :zrok-core:test
```

22 tests sin red zrok real: servidor HTTP local que verifica cabeceras y
cuerpos exactos de la API (`x-token`, `application/zrok.v1+json`), y un
`FakeZitiGateway` sobre canales asíncronos reales de loopback que ejercita el
piping bidireccional completo del túnel.

## Límites actuales (v0.1)

- Los shares `udpTunnel`/`caddy`/`drive`/`socks` pueden crearse por REST, pero
  el plano de datos de este SDK solo transporta TCP (`tcpTunnel`, proxy y web
  van sobre TCP).
- El `proxy` público del lado *frontend* lo sirven los frontends de la
  instancia zrok; el SDK solo necesita mantener el listener del share.
- No hay soporte de MFA ni de organizaciones/agentes de la API v2 (el cliente
  generado los expone; la fachada no).

## Desarrollo

```bash
./gradlew build                          # todo: tests + AAR + APK de ejemplo
./gradlew :zrok-api:openApiGenerate      # regenerar el cliente REST
```

Estructura de commits y decisiones documentadas en
[docs/spec-notes.md](docs/spec-notes.md).

## Licencia

Apache License 2.0, la misma que [zrok](https://github.com/openziti/zrok) y
[ziti-sdk-android](https://github.com/openziti/ziti-sdk-android). Véase
[LICENSE](LICENSE).
