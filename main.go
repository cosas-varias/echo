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

	// Load .env from the binary directory first, then fallback to cwd for development.
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

		filename, err := writeNote(notesDir, note)
		if err != nil {
			respond(w, r, http.StatusInternalServerError, "No se pudo guardar la nota")
			return
		}

		msg := fmt.Sprintf("Nota guardada: %s", html.EscapeString(filename))
		respond(w, r, http.StatusOK, msg)
	})

	addr := ":8080"
	if v := strings.TrimSpace(os.Getenv("PORT")); v != "" {
		addr = ":" + v
	}

	fmt.Printf("listening on %s\n", addr)
	if err := http.ListenAndServe(addr, securityHeaders(mux)); err != nil {
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

func writeNote(notesDir, body string) (string, error) {
	for i := 0; i < 3; i++ {
		name := time.Now().Format("20060102-150405") + ".md"
		path := filepath.Join(notesDir, name)
		f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if err == nil {
			defer f.Close()
			if _, werr := f.WriteString(body + "\n"); werr != nil {
				return "", werr
			}
			return name, nil
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
    input[type=number] { width: 140px; padding: 8px; font-size: 16px; }
    textarea { width: 100%; min-height: 320px; padding: 12px; font-size: 15px; font-family: "IBM Plex Mono", monospace; }
    button { margin-top: 16px; padding: 10px 18px; font-size: 16px; cursor: pointer; }
    .msg { margin-top: 16px; padding: 10px 12px; background: #f2f2f2; border-left: 4px solid #333; }
  </style>
</head>
<body>
  <h1>Agregar nota</h1>
  <form method="post" action="/notes" hx-post="/notes" hx-target="#result" hx-swap="innerHTML">
    <label for="pin">PIN</label>
    <input id="pin" name="pin" type="number" inputmode="numeric" required />
    <label for="note">Nota (Markdown)</label>
    <textarea id="note" name="note" placeholder="Escribe tu nota en Markdown" required></textarea>
    <button type="submit">Guardar</button>
  </form>
  <div id="result"></div>
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
