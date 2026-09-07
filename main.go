package main

import (
	"errors"
	"fmt"
	"html"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	envPinKey = "APP_PIN"
)

func main() {
	exeDir, err := executableDir()
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}

	_ = loadDotEnv(filepath.Join(exeDir, ".env"))
	_ = loadDotEnv(".env")

	pin := strings.TrimSpace(os.Getenv(envPinKey))
	if pin == "" {
		fmt.Fprintf(os.Stderr, "error: %s is not set (use .env)\n", envPinKey)
		os.Exit(1)
	}

	notesDir := filepath.Join(exeDir, "notes")
	if err := os.MkdirAll(notesDir, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "error: cannot create notes dir: %v\n", err)
		os.Exit(1)
	}

	tracesDir := filepath.Join(exeDir, "traces")
	if err := os.MkdirAll(tracesDir, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "error: cannot create traces dir: %v\n", err)
		os.Exit(1)
	}

	// La ingesta de trazas va aparte del PIN del formulario: aquel lo teclea una persona y son
	// cuatro digitos, y este viaja en la URL de un telefono que sube archivos solo. Sin token no
	// se acepta nada, porque el endpoint escribe en disco.
	traces := newTraceStore(tracesDir)
	if !traces.enabled() {
		fmt.Fprintf(os.Stderr, "warning: %s no esta definido, /traces respondera 503\n", envTokenKey)
	}

	defaultLoc, err := time.LoadLocation("Europe/Madrid")
	if err != nil {
		fmt.Fprintf(os.Stderr, "error loading timezone: %v\n", err)
		os.Exit(1)
	}

	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, pageHTML())
	})

	mux.HandleFunc("/notes", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}

		if err := r.ParseForm(); err != nil {
			respond(w, r, http.StatusBadRequest, "Solicitud invalida")
			return
		}

		providedPin := strings.TrimSpace(r.FormValue("pin"))
		if providedPin == "" || providedPin != pin {
			respond(w, r, http.StatusUnauthorized, "PIN incorrecto")
			return
		}

		note := strings.TrimSpace(r.FormValue("note"))
		if note == "" {
			respond(w, r, http.StatusBadRequest, "La nota esta vacia")
			return
		}

		tz := strings.TrimSpace(r.FormValue("tz"))
		loc := defaultLoc

		if tz != "" {
			if l, err := time.LoadLocation(tz); err == nil {
				loc = l
			}
		}

		filename, err := writeNote(notesDir, note, loc)
		if err != nil {
			respond(w, r, http.StatusInternalServerError, "No se pudo guardar la nota")
			return
		}

		msg := fmt.Sprintf("Nota guardada: %s", html.EscapeString(filename))
		respond(w, r, http.StatusOK, msg)
	})

	// Ingesta de Echo, ver traces.go. Se registran las dos formas porque esta URL la teclea una
	// persona en el telefono, y una barra de mas no deberia ser un fallo de subida.
	mux.HandleFunc("/traces", traces.handle)
	mux.HandleFunc("/traces/", traces.handle)

	addr := ":8080"
	if v := strings.TrimSpace(os.Getenv("PORT")); v != "" {
		addr = ":" + v
	}

	// Sin ReadTimeout ni WriteTimeout a proposito. Una traza de decenas de megas por red movil
	// es un cuerpo lento, y los dos plazos de Go empiezan a contar cuando se leen las cabeceras,
	// asi que cualquiera de los dos cortaria la subida, o la respuesta a esa subida, a mitad. Lo
	// que si hace falta es plazo para las cabeceras, que es lo que para a un cliente que abre
	// conexiones y luego no dice nada.
	server := &http.Server{
		Addr:              addr,
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 20 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	fmt.Printf("listening on %s\n", addr)
	if err := server.ListenAndServe(); err != nil {
		fmt.Fprintf(os.Stderr, "server error: %v\n", err)
		os.Exit(1)
	}
}

func executableDir() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	exe, err = filepath.EvalSymlinks(exe)
	if err != nil {
		return "", err
	}
	return filepath.Dir(exe), nil
}

func writeNote(notesDir, body string, loc *time.Location) (string, error) {
	for i := 0; i < 3; i++ {
		now := time.Now().In(loc)

		filename := now.Format("20060102_150405") + ".md"
		path := filepath.Join(notesDir, filename)

		f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if err == nil {
			defer f.Close()

			formatted := now.Format("Monday, 02 Jan 2006 15:04:05 MST")

			content := fmt.Sprintf(
				"<!-- tz: %s -->\n<!-- created: %s -->\n\n%s\n",
				loc.String(),
				formatted,
				body,
			)

			if _, werr := f.WriteString(content); werr != nil {
				return "", werr
			}
			return filename, nil
		}

		if !errors.Is(err, os.ErrExist) {
			return "", err
		}
		time.Sleep(1 * time.Second)
	}
	return "", fmt.Errorf("filename collision")
}

func respond(w http.ResponseWriter, r *http.Request, status int, message string) {
	if strings.EqualFold(r.Header.Get("HX-Request"), "true") {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(status)
		io.WriteString(w, fmt.Sprintf("<div class=\"msg\">%s</div>", html.EscapeString(message)))
		return
	}
	if status == http.StatusOK {
		http.Redirect(w, r, "/?ok=1", http.StatusSeeOther)
		return
	}
	http.Error(w, message, status)
}

func pageHTML() string {
	return `<!doctype html>
<html lang="es">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Fast Notes</title>
  <script src="https://unpkg.com/htmx.org@1.9.12" crossorigin="anonymous"></script>
  <style>
    :root { color-scheme: light; }
    body { font-family: "IBM Plex Sans", "Segoe UI", sans-serif; margin: 40px; max-width: 820px; }
    h1 { font-size: 28px; margin-bottom: 16px; }
    label { display: block; font-weight: 600; margin: 14px 0 6px; }
    input, select { padding: 8px; font-size: 16px; }
    textarea { width: 100%; min-height: 320px; padding: 12px; font-size: 15px; font-family: "IBM Plex Mono", monospace; }
    button { margin-top: 16px; padding: 10px 18px; font-size: 16px; cursor: pointer; }
    .msg { margin-top: 16px; padding: 10px 12px; background: #f2f2f2; border-left: 4px solid #333; }
    .preview { margin-top: 10px; font-size: 14px; color: #444; }
    .filename { font-family: monospace; color: #222; }
  </style>
</head>
<body>
  <h1>Agregar nota</h1>

  <form method="post" action="/notes" hx-post="/notes" hx-target="#result" hx-swap="innerHTML">
    
    <label for="pin">PIN</label>
    <input id="pin" name="pin" type="number" inputmode="numeric" required />

    <label for="tzSelect">Zona horaria</label>
    <select id="tzSelect" name="tz"></select>

    <div class="preview">
      Fecha de guardado:<br>
      <strong id="previewDate"></strong>
    </div>

    <div class="preview">
      Nombre del archivo:<br>
      <span class="filename" id="previewFilename"></span>
    </div>

    <label for="note">Nota (Markdown)</label>
    <textarea id="note" name="note" placeholder="Escribe tu nota en Markdown" required></textarea>

    <button type="submit">Guardar</button>
  </form>

  <div id="result"></div>

  <script>
    const tzSelect = document.getElementById("tzSelect");
    const previewDate = document.getElementById("previewDate");
    const previewFilename = document.getElementById("previewFilename");

    const userTZ = Intl.DateTimeFormat().resolvedOptions().timeZone;

    function updatePreview() {
      const tz = tzSelect.value;
      const now = new Date();

      const formatted = new Intl.DateTimeFormat("es-ES", {
        timeZone: tz,
        dateStyle: "full",
        timeStyle: "medium"
      }).format(now);

      previewDate.textContent = formatted;

      const parts = new Intl.DateTimeFormat("en-CA", {
        timeZone: tz,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hourCycle: "h23"
      }).formatToParts(now);

      const map = {};
      parts.forEach(p => map[p.type] = p.value);

      const filename =
        map.year +
        map.month +
        map.day + "_" +
        map.hour +
        map.minute +
        map.second +
        ".md";

      previewFilename.textContent = filename;
    }

    const timezones = Intl.supportedValuesOf
      ? Intl.supportedValuesOf("timeZone")
      : ["Europe/Madrid", "UTC"];

    timezones.forEach(tz => {
      const opt = document.createElement("option");
      opt.value = tz;
      opt.textContent = tz;
      if (tz === userTZ) opt.selected = true;
      tzSelect.appendChild(opt);
    });

    tzSelect.addEventListener("change", updatePreview);

    updatePreview();
  </script>
</body>
</html>`
}

func loadDotEnv(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	lines := strings.Split(string(data), "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, val, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		val = strings.TrimSpace(val)
		if key == "" {
			continue
		}
		if _, exists := os.LookupEnv(key); !exists {
			_ = os.Setenv(key, val)
		}
	}
	return nil
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}
