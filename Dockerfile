# Etapa de compilación
FROM golang:1.22-alpine AS builder
WORKDIR /app
# Copiar archivos de dependencia
COPY go.mod ./
# Si tuvieras go.sum, descomenta la siguiente línea:
# COPY go.sum ./
RUN go mod download
# Copiar el código fuente
COPY . .
# Compilar el binario de forma estática
RUN CGO_ENABLED=0 GOOS=linux go build -o fast-notes .

# Etapa final (Imagen ligera)
FROM alpine:latest
RUN apk --no-cache add ca-certificates tzdata
WORKDIR /root/
# Copiar el binario desde la etapa anterior
COPY --from=builder /app/fast-notes .
# Crear el directorio de notas para que tenga los permisos correctos
RUN mkdir ./notes
# Exponer el puerto por defecto
EXPOSE 8081
# Comando para ejecutar la app
CMD ["./fast-notes"]
