# API Gateway — Laboratorio 3 (Sistemas Distribuidos, UPTC)

Único punto de entrada hacia los módulos. **Aquí se valida toda la seguridad (JWT)**; los módulos no cambian.

> **Lab 4:** el Gateway ya **no tiene usuarios ni emite tokens**. Eso lo hace el módulo de Autenticación
> (`auth-service`, repositorio propio, PostgreSQL propia). El Gateway solo verifica la firma, el vencimiento, el emisor
> y la audiencia del JWT con la clave compartida `JWT_SECRET`, y enruta `/api/auth/**` hacia ese módulo.

Java 17 · Spring Boot 3.3.4 · Spring Cloud Gateway (2023.0.3) · Spring Security (OAuth2 Resource Server, HS256) · springdoc-openapi 2.6.0

## Puertos y orden de arranque

| Proceso | Puerto | Cómo se levanta |
|---|---|---|
| Estudiantes | 3001 | `docker compose up -d` · `npm run migrate` · `npm run dev` |
| Materias | 8081 | `docker compose up -d` · `mvn spring-boot:run` |
| Inscripciones | 8082 | `docker compose up -d` · `mvn spring-boot:run` (Java 23, Spring Boot 4) |
| **Autenticación** | **8083** | `docker compose up -d --build` (BD en el puerto **5435** + API) — ver el README de `auth-service` |
| **Gateway** | **8080** | ver abajo |

> Las bases de datos publican en el host: Estudiantes **5433** (por defecto), Materias **5432** e Inscripciones **5434**.

## Ejecutar el Gateway

Requiere JDK 17+ y Maven 3.9+. El secreto **no tiene valor por defecto**: si falta, la app no arranca y dice cuál. Arranca primero el módulo de Autenticación.

**Opción A — perfil local (recomendada):**
```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# edita gateway.jwt.secret (>= 32 caracteres, IGUAL al JWT_SECRET de auth-service)
mvn spring-boot:run -Dspring-boot.run.profiles=local
```
> En **PowerShell** el `-D...` con punto se rompe si no va entre comillas. Usa:
> `mvn spring-boot:run "-Dspring-boot.run.profiles=local"`
> (o, sin comillas: `$env:SPRING_PROFILES_ACTIVE="local"; mvn spring-boot:run`).

**Opción B — variables de entorno:**
```powershell
# PowerShell
$env:JWT_SECRET="un-secreto-largo-de-al-menos-32-caracteres"; mvn spring-boot:run
```
```bash
# bash
JWT_SECRET="..." mvn spring-boot:run
```

Swagger: <http://localhost:8080/swagger-ui.html> · Pruebas: `mvn test` (los tests fabrican sus propios tokens con `TestTokens`; no necesitan auth-service)

Variables opcionales: `JWT_ISSUER` / `JWT_AUDIENCE` (deben coincidir con las de auth-service), `PROXY_TIMEOUT` (10s),
`RATE_LIMIT_MAX` (10/min), `CORS_ORIGIN` (*), `ESTUDIANTES_URL`, `MATERIAS_URL`, `INSCRIPCIONES_URL`,
`AUTH_URL` (http://localhost:8083),
`COMPOSITION_TIMEOUT` (5s, timeout de las llamadas de `/detalle` a los tres módulos).

## Enrutamiento

| Prefijo | Módulo |
|---|---|
| `/api/estudiantes/**`, `/api/programas/**`, `/api/documentos/**` | Estudiantes |
| `/api/materias/**` (incluye `/docentes` y `/cursos`) | Materias |
| `/api/inscripciones/**` | Inscripciones |
| `/api/auth/**` | Autenticación |

Si un módulo no responde: `503` (caído) o `504` (timeout); el Gateway sigue atendiendo a los demás.

## Endpoint de composición (BFF) — sección 6.1

`GET /api/estudiantes/{id}/detalle` sí es lógica propia del Gateway (a diferencia del enrutamiento
simple, que es solo un proxy). Requiere token, igual que el resto de `/api/**`. Orquesta, en este orden:

1. `GET Estudiante/{id}` al módulo Estudiantes.
2. `GET Inscripciones?estudianteId={id}` al módulo Inscripciones — recorre **todas** las páginas
   (el módulo solo admite `pageSize` 10/20/50; el Gateway usa 50 y pide las páginas que falten).
3. Por cada inscripción, `GET Curso/{cursoId}` al módulo Materias.
4. Por cada curso, `GET Docente/{docenteId}` al módulo Materias.
5. El Gateway ensambla todo en un solo JSON.

`curso` y `docente` repetidos entre inscripciones (mismo curso, mismo docente) se piden **una sola vez**.

**Decisiones de diseño, por si preguntan en la sustentación:**
- **Sin respuestas parciales.** Si cualquier módulo falla en cualquier paso, toda la operación falla
  con un error controlado (`404` si el estudiante no existe, `502` si un módulo respondió un error,
  `503`/`504` si está caído o lento) — nunca un JSON a medias. Es más fácil de explicar y de depurar
  que decidir, campo por campo, qué mostrar cuando falta un dato.
- **`estado` viaja tal cual lo da Inscripciones** (`"ACTIVA"`, en mayúsculas — el enum real del
  módulo), no como el `"activa"` en minúsculas del ejemplo ilustrativo del enunciado.
- **El paso 4 se hace igual, aunque ya sobra un dato.** `GET Curso/{cursoId}` ya trae `docenteNombre`,
  pero el enunciado pide una llamada aparte a `Docente/{docenteId}`, así que se hace para seguir el
  contrato al pie de la letra (y porque valida que el endpoint de Docentes también funcione).
- **`estudiante` no se toca.** Va tal cual lo devuelve el módulo Estudiantes, como pide el enunciado.

Documentado con Swagger en el tag **Composición (BFF)** (`/swagger-ui.html`), con el contrato completo
(200/401/404/502/503/504) y ejemplos. Hay una prueba de integración (`CompositionTest`) que simula
los tres módulos y comprueba el ensamblado, el recorrido de páginas y que curso/docente no se pidan
dos veces.

## Seguridad

| Endpoint | Acceso |
|---|---|
| `POST /api/auth/login` | Público (máx. `RATE_LIMIT_MAX` intentos/min por IP). Lo atiende auth-service |
| `/api/**` | Token. `ADMIN`: todos los métodos · `USER`: solo `GET` |
| `POST /api/auth/register` | Solo `ADMIN` (cae en la regla anterior) |
| `GET /api/auth/me` | Token |
| `/health`, `/swagger-ui.html`, `/v3/api-docs`, `/docs/*.json` | Público |

- Usuarios y contraseñas (BCrypt) viven en la base de datos de **auth-service**; el Gateway no guarda ninguno.
- Token: HS256 con algoritmo, `iss` y `aud` fijos; el rol viaja en el claim `role`. Lo firma auth-service y lo valida el Gateway.
- Antes de reenviar, el Gateway quita `Authorization`, descarta cualquier `X-User-*` del cliente y agrega `X-User-Name` / `X-User-Role`.
  **Excepción:** hacia `/api/auth/**` conserva `Authorization`, porque auth-service valida el JWT por su cuenta (no confía en cabeceras).
- Errores: `{ "error": { "code", "message", "details": [] } }`.

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"TU_CLAVE"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

curl -H "Authorization: Bearer $TOKEN" "localhost:8080/api/materias?pageNumber=1&pageSize=5"
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/api/estudiantes?pageNumber=1&pageSize=5"
```

## Swagger del Gateway

`http://localhost:8080/swagger-ui.html` — contrato propio (auth + reglas de seguridad) y, en el selector
*Select a definition*, los contratos de Estudiantes, Materias e Inscripciones reescritos para ejecutarse **a través del Gateway**.
Pulsa **Authorize** una sola vez con el token del login (sin la palabra Bearer).

## Estructura

```
src/main/java/co/edu/uptc/gateway/
  config/      GatewayProperties (validada) · OpenApiConfig
  security/    SecurityConfig (reglas por ruta/rol) · JwtConfig (verificación) · Role · JwtClaims
  routing/     RoutesConfig (prefijo -> módulo) · IdentityHeadersFilter
  ratelimit/   AuthRateLimitFilter (freno en POST /api/auth/login)
  docs/        OpenApiSpecAdapter (contratos de módulos vía Gateway)
  composition/ CompositionController · EstudianteDetalleService (endpoint /detalle, sección 6.1)
  error/       GatewayErrorHandler (503/504/404 controlados) · ApiExceptionHandler
```

## Pendiente / límites conocidos

- Los módulos siguen accesibles directo en sus puertos; en despliegue deben quedar solo en red interna.
- Sin refresh tokens ni revocación: el token vive `JWT_EXPIRES_IN`.
- `/detalle` no cachea nada: cada llamada vuelve a pedir todo a los tres módulos.
