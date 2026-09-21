# API Gateway — Laboratorio 3 (Sistemas Distribuidos, UPTC)

Único punto de entrada hacia los módulos. **Aquí se aplica toda la seguridad (JWT)**; los módulos no cambian.

Java 17 · Spring Boot 3.3.4 · Spring Cloud Gateway (2023.0.3) · Spring Security (OAuth2 Resource Server, HS256) · springdoc-openapi 2.6.0

## Puertos y orden de arranque

| Proceso | Puerto | Cómo se levanta |
|---|---|---|
| Estudiantes | 3001 | `docker compose up -d` · `npm run migrate` · `npm run dev` |
| Materias | 8081 | `docker compose up -d` · `mvn spring-boot:run` |
| Inscripciones | 8082 (configurable) | según su repositorio |
| **Gateway** | **8080** | ver abajo |

> Las bases de datos de Estudiantes y Materias publican por defecto el mismo puerto **5433**. Cambia uno
> (p. ej. `DB_PORT=5434` en el `.env` de Estudiantes) antes de levantar ambas.

## Ejecutar el Gateway

Requiere JDK 17+ y Maven 3.9+. Los secretos **no tienen valor por defecto**: si faltan, la app no arranca y dice cuál.

**Opción A — perfil local (recomendada):**
```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# edita gateway.jwt.secret (>= 32 caracteres) y gateway.admin.password
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```
> En **PowerShell** el `-D...` con punto se rompe si no va entre comillas. Usa:
> `mvn spring-boot:run "-Dspring-boot.run.profiles=local"`
> (o, sin comillas: `$env:SPRING_PROFILES_ACTIVE="local"; mvn spring-boot:run`).

**Opción B — variables de entorno:**
```powershell
# PowerShell
$env:JWT_SECRET="un-secreto-largo-de-al-menos-32-caracteres"; $env:ADMIN_PASSWORD="tu-clave-admin"; mvn spring-boot:run
```
```bash
# bash
JWT_SECRET="..." ADMIN_PASSWORD="..." mvn spring-boot:run
```

Swagger: <http://localhost:8080/swagger-ui.html> · Pruebas: `mvn test`

Variables opcionales: `JWT_EXPIRES_IN` (1h), `ADMIN_USERNAME` (admin), `PROXY_TIMEOUT` (10s), `RATE_LIMIT_MAX` (10/min),
`CORS_ORIGIN` (*), `ESTUDIANTES_URL`, `MATERIAS_URL`, `INSCRIPCIONES_URL`, `USERS_FILE` (./data/users.json).

## Enrutamiento

| Prefijo | Módulo |
|---|---|
| `/api/estudiantes/**`, `/api/programas/**`, `/api/documentos/**` | Estudiantes |
| `/api/materias/**` (incluye `/docentes` y `/cursos`) | Materias |
| `/api/inscripciones/**` | Inscripciones |

Si un módulo no responde: `503` (caído) o `504` (timeout); el Gateway sigue atendiendo a los demás.

## Seguridad

| Endpoint | Acceso |
|---|---|
| `POST /auth/login` · `POST /auth/register` | Público (máx. `RATE_LIMIT_MAX` intentos/min por IP) |
| `GET /auth/me` | Token |
| `/api/**` | Token. `ADMIN`: todos los métodos · `USER`: solo `GET` |
| `/health`, `/swagger-ui.html`, `/v3/api-docs`, `/docs/*.json` | Público |

- `register` siempre crea rol `USER`. El `ADMIN` se crea al arrancar con `ADMIN_USERNAME`/`ADMIN_PASSWORD` (si ya existe en `data/users.json`, no se sobrescribe).
- Token: HS256 con algoritmo, `iss` y `aud` fijos; el rol viaja en el claim `role`.
- Antes de reenviar, el Gateway quita `Authorization`, descarta cualquier `X-User-*` del cliente y agrega `X-User-Name` / `X-User-Role`.
- Usuarios: `data/users.json` (contraseñas con BCrypt), ignorado por git.
- Errores: `{ "error": { "code", "message", "details": [] } }`.

```bash
TOKEN=$(curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"TU_CLAVE"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

curl -H "Authorization: Bearer $TOKEN" "localhost:8080/api/materias?pageNumber=1&pageSize=5"
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/api/estudiantes?pageNumber=1&pageSize=5"
```

## Swagger del Gateway

`http://localhost:8080/swagger-ui.html` — contrato propio (auth + reglas de seguridad) y, en el selector
*Select a definition*, los contratos de Estudiantes y Materias reescritos para ejecutarse **a través del Gateway**.
Pulsa **Authorize** una sola vez con el token del login (sin la palabra Bearer).

## Estructura

```
src/main/java/co/edu/uptc/gateway/
  config/      GatewayProperties (validada) · OpenApiConfig
  security/    SecurityConfig (reglas por ruta/rol) · JwtConfig (firma y verificación)
  auth/        AuthController (/auth/**) · UserStore · TokenService · DTOs
  routing/     RoutesConfig (prefijo -> módulo) · IdentityHeadersFilter
  ratelimit/   AuthRateLimitFilter
  docs/        OpenApiSpecAdapter (contratos de módulos vía Gateway)
  error/       GatewayErrorHandler (503/504/404 controlados) · ApiExceptionHandler
```

## Pendiente / límites conocidos

- `GET /api/estudiantes/{id}/detalle` (composición, sección 6.1 del enunciado) aún no está implementado.
- Los módulos siguen accesibles directo en sus puertos; en despliegue deben quedar solo en red interna.
- Sin refresh tokens ni revocación: el token vive `JWT_EXPIRES_IN`.
