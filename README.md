# Transfer ⚡

Aplicación web ligera para **subir, compartir y descargar ficheros** en tu propio servidor. Hecha con **Spring Boot 3** y **SQLite**, sin más dependencias: un único `.jar` y una carpeta de datos.

Pensada para uso personal o de un grupo pequeño: tú y quien tú des de alta.

---

## Características

- **Inicio de sesión** con usuarios guardados en SQLite y claves cifradas con BCrypt.
- **Administración de usuarios** (solo admins): crear, cambiar rol, cambiar clave y borrar.
- **Descargas**: lista con nombre, fecha y tamaño, con buscador instantáneo.
  - **Descargar**: el fichero se queda en el servidor.
  - **Descargar y borrar**: se borra del servidor cuando la descarga termina bien. Si se corta, se conserva.
  - **Borrar** sin descargar.
- **Subida** con arrastrar y soltar y barra de progreso.
- **Borrado automático** de los ficheros con más de 30 días (configurable), al entrar en la web y cada noche a las 3:00.

## Seguridad

- **Sin claves por defecto.** El admin inicial toma la clave de `INITIAL_ADMIN_PASS`. Si no la defines, se genera una aleatoria y se muestra **una sola vez** en el log del primer arranque.
- **Bloqueo de fuerza bruta**: tras 5 intentos fallidos desde la misma IP, el login queda bloqueado 15 minutos (configurable).
- **Protección CSRF** en todas las acciones que modifican algo (subir, borrar, descargar y borrar, administración).
- **Cuota de almacenamiento** y espacio libre mínimo en disco, para que nadie pueda llenar el servidor.
- Nombres de fichero saneados y guardados con un UUID (sin *path traversal*). Las descargas se sirven siempre como adjunto, nunca se ejecutan en el navegador.
- Cabeceras `Content-Security-Policy`, `X-Frame-Options` y cookie de sesión `HttpOnly` / `SameSite=Lax`.

> **Importante:** todos los usuarios ven y pueden borrar todos los ficheros. Da de alta solo a gente de confianza.

---

## Requisitos

- **Java 21** o superior (o Docker).
- No hace falta instalar Maven: se usa el *Maven Wrapper* incluido.

## Compilar

```bash
./mvnw clean package          # Linux / macOS
mvnw.cmd clean package        # Windows
```

Se genera `target/transfer-1.0.0.jar`.

## Ejecutar en local

```bash
INITIAL_ADMIN_PASS='una-clave-larga' java -jar target/transfer-1.0.0.jar
```

Abre http://localhost:8080 y entra con el usuario `admin` y esa clave.

---

## Configuración

Todo se configura con variables de entorno (también valen como `--propiedad=valor`). En [`transfer.env.example`](transfer.env.example) tienes todas las variables comentadas.

| Variable | Por defecto | Descripción |
|---|---|---|
| `PORT` | `8080` | Puerto HTTP |
| `DB_PATH` | `./data/transfer.db` | Fichero de la base de datos SQLite |
| `UPLOAD_DIR` | `./uploads` | Carpeta de los ficheros subidos |
| `INITIAL_ADMIN_USER` | `admin` | Usuario admin que se crea en el primer arranque |
| `INITIAL_ADMIN_PASS` | *(vacío)* | Su clave. Vacía: se genera y se muestra en el log |
| `RETENTION_DAYS` | `30` | Días que se conserva cada fichero |
| `MAX_STORAGE_MB` | `10240` | Espacio total máximo de los ficheros (0 = sin límite) |
| `MIN_FREE_DISK_MB` | `1024` | Espacio libre mínimo que debe quedar en el disco |
| `MAX_UPLOAD_SIZE` | `500MB` | Tamaño máximo de cada subida |
| `LOGIN_MAX_ATTEMPTS` | `5` | Intentos fallidos por IP antes de bloquear |
| `LOGIN_LOCK_MINUTES` | `15` | Minutos de bloqueo |
| `SESSION_COOKIE_SECURE` | `false` | Ponlo a `true` si lo sirves por HTTPS |

El admin inicial solo se crea si no existe. Cambiar `INITIAL_ADMIN_PASS` después **no** cambia su clave: hazlo desde *Administrar usuarios*.

---

## Despliegue en un servidor Linux

### Opción A: Docker Compose

```bash
cp transfer.env.example .env      # y edita las variables que quieras
docker compose up -d --build
docker compose logs transfer      # aquí sale la clave del admin si no la definiste
```

Los datos quedan en `./data` y `./uploads`. El contenedor solo escucha en `127.0.0.1:8080`: publícalo con un proxy inverso.

### Opción B: servicio systemd

```bash
sudo useradd --system --home /opt/transfer --shell /usr/sbin/nologin transfer
sudo mkdir -p /opt/transfer/data /opt/transfer/uploads
sudo cp target/transfer-1.0.0.jar /opt/transfer/transfer.jar
sudo cp transfer.env.example /opt/transfer/transfer.env   # edítalo
sudo chown -R transfer:transfer /opt/transfer
sudo chmod 600 /opt/transfer/transfer.env

sudo cp transfer.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now transfer
journalctl -u transfer -f
```

### Proxy inverso con HTTPS

La aplicación debe quedar detrás de un proxy (Nginx, Apache, Caddy…) que ponga HTTPS y envíe las cabeceras `X-Forwarded-*`. Así funcionan bien las redirecciones y el bloqueo por IP.

- Haz que la app escuche solo en local: `--server.address=127.0.0.1`, como en `transfer.service`.
- Ponle a la app `SESSION_COOKIE_SECURE=true`.
- Sube en el proxy el tamaño máximo de petición: por ejemplo, `client_max_body_size 500m;` en Nginx.

Ejemplo con Nginx, sirviéndola en `/transfer/`:

```nginx
location /transfer/ {
    proxy_pass http://127.0.0.1:8080/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Prefix /transfer;
    client_max_body_size 500m;
}
```

Para servirla en la raíz del dominio, usa `location /` y quita `X-Forwarded-Prefix`.

> La app se fía de las cabeceras `X-Forwarded-*`. No la expongas directamente a Internet sin proxy: cualquiera podría falsear su IP y saltarse el bloqueo de login.

---

## Tests

```bash
./mvnw test
```

Los tests usan una base de datos y una carpeta propias dentro de `target/`.

## Licencia

[MIT](LICENSE)
