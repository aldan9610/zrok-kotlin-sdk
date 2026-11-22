# Notas sobre la especificación OpenAPI de zrok

Fuente fijada: [specs/zrok.yml](../specs/zrok.yml), descargada del tag **v2.0.11** del
repositorio oficial <https://github.com/openziti/zrok>. La especificación es **Swagger 2.0**
(`info.version: 2.0.0`), con `basePath: /api/v2`.

## Puntos clave verificados contra la spec y el SDK Go de referencia

| Aspecto | Valor verificado |
| --- | --- |
| Esquema de seguridad | `apiKey` en cabecera **`x-token`** (valor: *account token*). El SDK Go usa `APIKeyAuth("X-TOKEN", "header", ...)`. |
| Content-Type | `application/zrok.v1+json` (declared en `consumes`/`produces`). Es el que envía el CLI/SDK Go. |
| Endpoint por defecto | `https://api-v2.zrok.io` (véase `environment/env_v0_4/root.go` en el repo de zrok: rebases automáticos de `api.zrok.io` a `api-v2.zrok.io`). |
| Servidor por defecto de la spec | La spec no declara `host`; el cliente generado usa localhost y **debe** configurarse con el endpoint real. |

## Operaciones que usa este SDK

- `POST /enable` — cuerpo `{ description, host }` → `201 { identity, cfg }`.
  **`cfg` es el JSON de identidad de OpenZiti**: es el material con el que el SDK de ziti
  (ziti-jvm / ziti-android) abre el contexto de red del entorno.
- `POST /disable` — cuerpo `{ identity }`.
- `POST /share` — cuerpo `shareRequest` → `201 shareResponse { shareToken, frontendProxyEndpoints[] }`.
  - `shareMode`: `public` \| `private`.
  - `backendMode`: `proxy` \| `web` \| `tcpTunnel` \| `udpTunnel` \| `caddy` \| `drive` \| `socks`.
  - `envZId`: identidad ziti del entorno (devuelta por `/enable`).
- `DELETE /unshare` — cuerpo `{ envZId, shareToken }` (operación DELETE con cuerpo).
- `POST /access` — cuerpo `{ envZId, shareToken, bindAddress?, description? }` → `201 { frontendToken, backendMode }`.
- `DELETE /unaccess` — cuerpo `{ frontendToken, shareToken, envZId }` (DELETE con cuerpo).
- `GET /shares`, `GET /accesses`, `GET /detail/share/{shareToken}`, `GET /configuration`,
  `GET /detail/account`, `GET /environments` — listados y metadatos.

## Plano de datos (la parte que la REST no cubre)

La API REST solo gestiona el ciclo de vida; **el transporte real lo hace la red OpenZiti**.
Semántica verificada en el SDK Go (`sdk/golang/sdk/listener.go` y `dialer.go`):

- **Lado host (share)**: `zctx.ListenWithOptions(shrToken, opts)` — el **nombre del servicio
  ziti es el token del share**. Cada conexión aceptada se debe comunicar (piping
  bidireccional) con el backend local indicado en `target`.
- **Lado cliente (access)**: `zctx.DialWithOptions(shrToken, opts)` — se marca (dial) el
  servicio ziti con el mismo nombre (token del share) y el flujo resultante conecta con el
  backend remoto compartido.
- Ambos lados cargan el contexto ziti desde la identidad `cfg` devuelta por `/enable`.

En ziti-jvm la equivalencia es:

| SDK Go (ziti) | ziti-jvm (Kotlin) |
| --- | --- |
| `NewContext(cfg)` | `Ziti.newContext(InputStream, CharArray)` |
| `ListenWithOptions(token, opts)` | `ctx.openServer()` + `server.bind(ZitiAddress.Bind(token))` |
| `DialWithOptions(token, opts)` | `ctx.dial(token)` o `ctx.open()` + `ch.connect(ZitiAddress.Dial(token))` |

## Regenerar el cliente

```bash
./gradlew :zrok-api:openApiGenerate
```

La tarea usa `specs/zrok.yml` como entrada; el resultado se versiona en `zrok-api/src/main/kotlin`.
