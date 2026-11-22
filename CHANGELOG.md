# Changelog

Todos los cambios notables de este proyecto se documentan aquí. El formato
sigue [Keep a Changelog](https://keepachangelog.com/es/1.1.0/) y el proyecto
usa [Versionado Semántico](https://semver.org/lang/es/).

## [0.2.0] — 2026-11-22

### Añadido

- **Backend SOCKS5** (`socks`): el host comparte su IP como proxy SOCKS5
  (RFC 1928, comando CONNECT). Un navegador remoto que use el endpoint del
  share como proxy SOCKS5 navega con la IP del dispositivo host. Auth opcional
  RFC 1929. `ZrokManager.createSocksShare`, `ZrokTunnel.hostSocks`,
  `ZrokTunnelService.startSocksTunnel`. 4 tests end-to-end.
- **Backend udpTunnel**: transporte de datagramas con framing de prefijo de
  longitud (2 bytes BE) sobre el stream ziti. `ZrokManager.createUdpTunnelShare`
  (ya existía en v0.1 a nivel REST), `ZrokTunnel.hostUdpTunnel`,
  `ZrokTunnel.startUdpAccess`, `ZrokTunnelService.startUdpHostTunnel` /
  `startUdpAccess`. 2 tests end-to-end.
- **Backend drive**: servidor WebDAV (RFC 4918 subset: OPTIONS, PROPFIND, GET,
  HEAD, PUT, DELETE, MKCOL, MOVE, COPY) sobre el stream ziti. Montable desde
  el Explorador de Windows, Cyberduck, rclone o Finder. `ZrokManager.createDriveShare`,
  `ZrokTunnel.hostDrive`, `ZrokTunnelService.startDriveTunnel`. 5 tests
  end-to-end.
- La app de ejemplo incluye un botón «Compartir tu IP como proxy SOCKS5».
- `ZrokTunnelService` con 4 modos nuevos y reconexión automática para todos.

### Limitaciones conocidas

- UDP público requiere el binario Go `zrok` como host (ziti-jvm 0.25.1 no
  preserva límites de datagrama en lectura; el framing de este SDK es
  Kotlin↔Kotlin).
- SOCKS5 solo CONNECT (no BIND ni UDP ASSOCIATE).
- Drive sin bloqueos WebDAV (LOCK/UNLOCK).

### Tests

- 33 tests (22 de v0.1 + 11 nuevos: 4 SOCKS, 2 UDP, 5 drive).

## [0.1.0] — 2026-11-22

### Añadido

- Cliente REST generado desde la especificación OpenAPI de zrok v2.0.11
  (módulo `zrok-api`, OpenAPI Generator 7.27, kotlinx-serialization).
- Fachada `ZrokEnvironment` / `ZrokManager` para el ciclo de vida de
  entorno, shares y accesses.
- Plano de datos `ZrokTunnel` sobre OpenZiti (ziti-jvm 0.25.1): tcpTunnel
  (host + access), proxy y web.
- Módulo Android (`zrok-android`) con `EncryptedZrokStateStore`
  (EncryptedSharedPreferences AES-256-GCM) y `ZrokTunnelService`
  (ForegroundService con reconexión de red).
- App de ejemplo con share tcpTunnel privado y access local.
- 22 tests con `MockZrokServer` y `FakeZitiGateway` sin red real.
