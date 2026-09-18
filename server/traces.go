package main

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Ingesta de trazas de la aplicacion Echo.
//
// Echo manda cada archivo por separado, el mas viejo primero, con el cuerpo crudo de la peticion
// (no multipart) y el nombre en la cabecera X-Filename. Lo que decide todo lo demas es esto:
// **el telefono borra su copia en cuanto recibe un 2xx**. Un 2xx es por tanto una promesa de que
// el archivo esta entero y en disco, y cualquier otra respuesta hace que el telefono lo conserve
// y lo reintente a los 30 segundos. De ahi que aqui se escriba a un temporal y solo se renombre
// al nombre definitivo despues de un copy y un sync correctos: nunca se responde 2xx por un
// archivo a medias.
//
// Echo tampoco puede mandar cabeceras propias, asi que el secreto viaja en la URL, que es lo
// unico configurable en la aplicacion.

const (
	envTokenKey    = "ECHO_TOKEN"
	envMaxBytesKey = "ECHO_MAX_UPLOAD_BYTES"

	// Un wav de cinco minutos a 48 kHz ronda los 30 MB, y el limite corta muy por encima de eso
	// a proposito: un archivo rechazado por tamano bloquea la cola del telefono, porque la tanda
	// se detiene en el primer fallo para no perder el orden.
	defaultMaxUploadBytes = 512 << 20

	// Nombres mas largos que esto no los produce Echo.
	maxFilenameLength = 128
)

// Las extensiones que Echo escribe, y ninguna otra: audio, tracks GPS, microvideos, capturas de
// pantalla, notas de texto y encuestas.
var traceExtensions = map[string]bool{
	".wav":  true,
	".gpx":  true,
	".mp4":  true,
	".jpg":  true,
	".png":  true,
	".txt":  true,
	".json": true,
}

type traceStore struct {
	dir      string
	token    string
	maxBytes int64
}

func newTraceStore(dir string) *traceStore {
	maxBytes := int64(defaultMaxUploadBytes)
	if v := strings.TrimSpace(os.Getenv(envMaxBytesKey)); v != "" {
		if parsed, err := strconv.ParseInt(v, 10, 64); err == nil && parsed > 0 {
			maxBytes = parsed
		} else {
			fmt.Fprintf(os.Stderr, "warning: %s no es un numero, usando %d\n",
				envMaxBytesKey, maxBytes)
		}
	}
	return &traceStore{
		dir:      dir,
		token:    strings.TrimSpace(os.Getenv(envTokenKey)),
		maxBytes: maxBytes,
	}
}

// enabled indica si hay token configurado. Sin token el endpoint no acepta nada: escribe
// archivos en disco, asi que lo correcto es quedarse cerrado, no abierto.
func (s *traceStore) enabled() bool {
	return s.token != ""
}

func (s *traceStore) handle(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.enabled() {
		log.Printf("trace rechazada: %s no esta configurado", envTokenKey)
		http.Error(w, envTokenKey+" is not configured", http.StatusServiceUnavailable)
		return
	}
	if !s.authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	name, err := traceFilename(r.Header.Get("X-Filename"))
	if err != nil {
		log.Printf("trace rechazada: %v", err)
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// Content-Length viene siempre: Echo lo fija antes de mandar. Comprobarlo aqui evita
	// recibir medio giga para descartarlo al final.
	if r.ContentLength > s.maxBytes {
		http.Error(w, "trace too large", http.StatusRequestEntityTooLarge)
		return
	}

	dir := filepath.Join(s.dir, dayOf(name, time.Now()))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Printf("no se puede crear %s: %v", dir, err)
		http.Error(w, "cannot store trace", http.StatusInternalServerError)
		return
	}

	final := filepath.Join(dir, name)
	// El telefono reenvia lo que ya mando si no consiguio borrarlo, o si la respuesta se perdio
	// de vuelta. Reconocer eso como exito es lo que le deja soltar su copia en el siguiente
	// intento en lugar de reintentar para siempre.
	if info, err := os.Stat(final); err == nil {
		if r.ContentLength >= 0 && info.Size() == r.ContentLength {
			// El cuerpo se lee entero aunque se descarte: cortar una subida a medias le llega
			// al telefono como un error de red, no como el 200 que acabamos de decidir.
			io.Copy(io.Discard, http.MaxBytesReader(w, r.Body, s.maxBytes))
			log.Printf("trace repetida %s (%d bytes), ya la teniamos", name, info.Size())
			writeStored(w, http.StatusOK, filepath.Base(dir), name, info.Size())
			return
		}
		// Mismo nombre y distinto tamano: son dos archivos distintos, asi que se guardan los
		// dos. Echo ya desempata dentro del mismo segundo, de modo que esto solo pasa entre
		// telefonos o entre instalaciones.
		final = uniquePath(dir, name)
	}

	started := time.Now()
	written, err := s.store(w, r, dir, final)
	if err != nil {
		log.Printf("trace %s fallida: %v", name, err)
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			http.Error(w, "trace too large", http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "cannot store trace", http.StatusInternalServerError)
		return
	}

	log.Printf("trace %s guardada (%d bytes en %s)", filepath.Base(final), written,
		time.Since(started).Round(time.Millisecond))
	writeStored(w, http.StatusCreated, filepath.Base(dir), filepath.Base(final), written)
}

// store escribe el cuerpo en un temporal del mismo directorio y solo entonces lo renombra, de
// forma que en el destino nunca hay un archivo a medias. Devuelve los bytes escritos.
func (s *traceStore) store(w http.ResponseWriter, r *http.Request, dir, final string) (int64, error) {
	tmp, err := os.CreateTemp(dir, ".partial-*")
	if err != nil {
		return 0, err
	}
	tmpName := tmp.Name()
	// Cualquier salida que no sea la buena se lleva el temporal por delante.
	defer func() {
		if tmpName != "" {
			tmp.Close()
			os.Remove(tmpName)
		}
	}()

	written, err := io.Copy(tmp, http.MaxBytesReader(w, r.Body, s.maxBytes))
	if err != nil {
		return 0, err
	}
	// Una subida cortada llega aqui como un cuerpo mas corto de lo anunciado. Sin esta
	// comprobacion responderiamos 2xx por un archivo truncado, y el telefono borraria el suyo.
	if r.ContentLength >= 0 && written != r.ContentLength {
		return 0, fmt.Errorf("cuerpo incompleto: %d de %d bytes", written, r.ContentLength)
	}
	if err := tmp.Sync(); err != nil {
		return 0, err
	}
	if err := tmp.Close(); err != nil {
		return 0, err
	}
	if err := os.Chmod(tmpName, 0o600); err != nil {
		return 0, err
	}
	if err := os.Rename(tmpName, final); err != nil {
		return 0, err
	}
	tmpName = "" // ya no hay temporal que limpiar
	syncDir(dir)
	return written, nil
}

// syncDir asienta el renombrado, no solo el contenido. Sin esto un corte de corriente puede
// dejar el archivo sin nombre en el directorio, y el telefono ya lo habra borrado.
func syncDir(dir string) {
	d, err := os.Open(dir)
	if err != nil {
		return
	}
	defer d.Close()
	_ = d.Sync()
}

func (s *traceStore) authorized(r *http.Request) bool {
	given := r.URL.Query().Get("token")
	if given == "" {
		// Echo no puede mandar cabeceras propias, pero curl y cualquier otro cliente si.
		if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			given = strings.TrimSpace(strings.TrimPrefix(h, "Bearer "))
		}
	}
	return subtle.ConstantTimeCompare([]byte(given), []byte(s.token)) == 1
}

// traceFilename valida el nombre que manda el cliente. Es texto de fuera que acaba siendo una
// ruta, asi que se acepta por lista blanca y no por lista negra: solo lo que Echo produce.
func traceFilename(raw string) (string, error) {
	name := strings.TrimSpace(raw)
	if name == "" {
		return "", errors.New("falta la cabecera X-Filename")
	}
	if len(name) > maxFilenameLength {
		return "", errors.New("nombre demasiado largo")
	}
	// Descarta cualquier separador y cualquier "..": Base de una ruta con partes no es la ruta.
	if name != filepath.Base(name) || strings.HasPrefix(name, ".") {
		return "", errors.New("nombre no valido")
	}
	for _, c := range name {
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9':
		case c == '.', c == '_', c == '-':
		default:
			return "", errors.New("nombre no valido")
		}
	}
	if !traceExtensions[strings.ToLower(filepath.Ext(name))] {
		return "", errors.New("extension no admitida")
	}
	return name, nil
}

// dayOf agrupa por dia. Meses de grabacion son decenas de miles de archivos, y un directorio
// plano deja de ser navegable mucho antes de eso. Echo nombra todo por el reloj de su primera
// muestra (yyyyMMdd_HHmmss...), asi que el dia sale del propio nombre y no de cuando llego, que
// puede ser mucho despues si el telefono estuvo sin red.
func dayOf(name string, now time.Time) string {
	if len(name) >= 8 {
		if day, err := time.Parse("20060102", name[:8]); err == nil {
			return day.Format("2006-01-02")
		}
	}
	return now.Format("2006-01-02")
}

// uniquePath busca un nombre libre anadiendo _2, _3..., igual que hace Echo en el telefono.
func uniquePath(dir, name string) string {
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for i := 2; i < 1000; i++ {
		candidate := filepath.Join(dir, fmt.Sprintf("%s_%d%s", base, i, ext))
		if _, err := os.Stat(candidate); errors.Is(err, os.ErrNotExist) {
			return candidate
		}
	}
	return filepath.Join(dir, fmt.Sprintf("%s_%d%s", base, time.Now().UnixNano(), ext))
}

func writeStored(w http.ResponseWriter, status int, day, name string, size int64) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"stored": day + "/" + name,
		"bytes":  size,
	})
}
