# zrok-kotlin-sdk

SDK de Kotlin/Android para [zrok](https://github.com/openziti/zrok): compartición segura de
servicios y túneles sobre redes [OpenZiti](https://openziti.io).

> Documentación completa en construcción. Este archivo se ampliará con guías de uso,
> ejemplos y referencia de la API a medida que avanza el desarrollo.

## Estado

Proyecto en desarrollo activo. Estructura prevista:

| Módulo | Descripción |
| --- | --- |
| `specs/` | Especificación OpenAPI/Swagger de zrok fijada (pin) por versión. |
| `zrok-api` | Cliente REST generado automáticamente con OpenAPI Generator (jvm-ktor + kotlinx-serialization). |
| `zrok-core` | Lógica de negocio: entorno, autenticación, shares, accesses y túnel de datos sobre ziti-jvm. |
| `zrok-android` | Integración Android: almacenamiento cifrado del token y ForegroundService para el túnel. |
| `sample-android` | Aplicación de ejemplo. |

## Licencia

Apache License 2.0 (en archivo LICENSE).
