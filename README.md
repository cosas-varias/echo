# Fast Notes

Aplicacion Go + HTMX para agregar notas en Markdown sin lectura de notas existentes.

## Requisitos

- Go 1.22+

## Configuracion

1. Copia `.env.example` a `.env` y define `APP_PIN`.
2. (Opcional) Cambia `PORT`.

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
