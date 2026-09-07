# Fast Notes

Aplicacion Go + HTMX para agregar notas en Markdown sin lectura de notas existentes, y punto de
recogida de las trazas que sube la aplicacion [Echo](https://github.com/cosas-varias/echo).

## Requisitos

- Go 1.22+

## Configuracion

1. Copia `.env.example` a `.env` y define `APP_PIN`.
2. Para recibir trazas de Echo, define `ECHO_TOKEN` con algo largo y aleatorio
   (`openssl rand -hex 32`). Sin token, `/traces` responde 503 y no acepta nada.
3. (Opcional) `HOST_PORT` cambia el puerto publicado en la maquina, 8081 por defecto.

Dentro del contenedor la aplicacion escucha siempre en el 8080: es a donde apuntan el mapeo de
compose y el proxy de Caddy, asi que no es una preferencia que se pueda mover desde `.env`.
Ejecutando el binario a mano, fuera de Docker, `PORT` si elige en cual escucha.

## Ejecutar en desarrollo

```bash
go run .
```

## Build y run

```bash
go build -o fast-notes
./fast-notes
```

Las notas se guardan en `notes/` junto al binario y se nombran con el formato `Ymd-his.md`.

## Recibir trazas de Echo

Echo graba audio en memoria y va escribiendo trazas en el telefono: audio (`.wav`), tracks GPS
(`.gpx`), microvideos de las camaras (`.mp4`), capturas de pantalla (`.jpg`), notas de texto
(`.txt`) y encuestas de estado (`.json`). Con el envio al servidor activado las manda aqui, la
mas vieja primero, y **borra su copia en cuanto recibe un 2xx**.

Eso es lo que gobierna el endpoint entero: un 2xx es la promesa de que el archivo esta entero y
en disco. Cualquier otra respuesta, o ninguna, hace que el telefono lo conserve y lo reintente a
los 30 segundos, y que detenga esa tanda para no adelantar archivos posteriores.

### La URL que se teclea en Echo

En Echo, en la seccion *Envio al servidor*:

```
https://TU-DOMINIO/traces?token=EL_TOKEN
```

El token va en la URL porque la aplicacion no puede mandar cabeceras propias, y la URL es lo
unico que deja configurar. Con `curl` y cualquier otro cliente vale tambien
`Authorization: Bearer EL_TOKEN`.

Que el token viaje en la URL tiene una consecuencia que conviene tener presente: aparece en los
registros de acceso de Caddy y de cualquier proxy que haya delante. Rotalo si esos registros
salen de la maquina.

### El contrato

```
POST /traces?token=...
Content-Type: audio/wav | application/gpx+xml | video/mp4 | image/jpeg | image/png
              | text/plain; charset=utf-8 | application/json
X-Filename: 20260907_143012_back.mp4
Content-Length: 1234567

<bytes crudos del archivo, no multipart>
```

| Respuesta | Cuando | Que hace el telefono |
| --- | --- | --- |
| `201 Created` | la traza se guardo | borra su copia |
| `200 OK` | ya teniamos ese archivo con ese tamano | borra su copia |
| `400 Bad Request` | falta `X-Filename`, o el nombre o la extension no valen | la conserva y reintenta |
| `401 Unauthorized` | token incorrecto | la conserva y reintenta |
| `413 Payload Too Large` | pasa de `ECHO_MAX_UPLOAD_BYTES` | la conserva y reintenta |
| `503 Service Unavailable` | no hay `ECHO_TOKEN` configurado | la conserva y reintenta |

El cuerpo de una respuesta correcta es `{"stored":"2026-09-07/20260907_143012_back.mp4","bytes":1234567}`.

Prueba rapida:

```bash
curl -i --data-binary @prueba.wav \
  -H 'Content-Type: audio/wav' \
  -H 'X-Filename: 20260907_143012.wav' \
  'https://TU-DOMINIO/traces?token=EL_TOKEN'
```

### Donde acaban

```
traces/2026-09-07/20260907_143012.wav
                  20260907_143012.gpx
                  20260907_143012_back.mp4
                  20260907_143515_note.txt
```

Agrupadas por dia, y el dia sale del propio nombre y no de cuando llegaron: un telefono que
estuvo sin cobertura sube el lunes lo que grabo el sabado, y lo que interesa es el sabado. Cada
archivo se escribe primero en un temporal del mismo directorio y solo se renombra tras copiarlo
y sincronizarlo entero, de modo que en `traces/` nunca hay un archivo a medias: no se responde
2xx por algo que el telefono no deberia borrar todavia.

Un reenvio del mismo nombre con el mismo tamano se reconoce y devuelve 200 sin escribir nada,
que es lo que le deja al telefono soltar una copia que no consiguio borrar la vez anterior. Con
el mismo nombre y distinto tamano se guardan los dos, con el sufijo `_2`, `_3`, igual que hace
Echo en el telefono.

No hay endpoint de lectura, en la misma linea que las notas: esto recibe, no muestra.

## Despliegue con Caddy

`docker-compose.yml` levanta el servicio y un Caddy delante que saca el certificado solo y habla
HTTP/3, que es el transporte que Echo usa en Android 14 y posteriores.

```bash
cp .env.example .env      # define APP_PIN, ECHO_TOKEN y ECHO_DOMAIN
docker compose up -d --build
```

El 443 se publica tambien en UDP: HTTP/3 es QUIC y QUIC es UDP. Sin ese puerto todo sigue
funcionando, pero por TCP y sin h3. El puerto en claro del servicio queda en `127.0.0.1:$HOST_PORT`
(8081 por defecto), para que el token no pueda viajar sin cifrar desde otra maquina.
