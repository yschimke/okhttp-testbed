package main

import (
	"compress/flate"
	"compress/gzip"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// The maximum request body /anything will read back. Enough for any test payload, small
// enough that a deployed instance can't be made to hold much.
const maxEchoBody = 1 << 20

func (s *server) handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = io.WriteString(w, "ok\n")
	})

	mux.HandleFunc("GET /info", s.info)
	mux.HandleFunc("GET /ca.pem", s.caPEM)
	mux.HandleFunc("GET /client.pem", s.clientPEM)
	mux.HandleFunc("/tls", s.tlsInfo)

	// The endpoint the httpbin family is worth having for: the whole request, echoed back as
	// JSON. It is the only honest check of what OkHttp sent rather than what it meant to send.
	mux.HandleFunc("/anything", s.anything)
	mux.HandleFunc("/anything/{path...}", s.anything)
	mux.HandleFunc("GET /headers", s.headers)

	mux.HandleFunc("/status/{code}", s.status)
	mux.HandleFunc("GET /redirect/{count}", s.redirect(false))
	mux.HandleFunc("GET /absolute-redirect/{count}", s.redirect(true))
	mux.HandleFunc("GET /redirect-to", s.redirectTo)
	mux.HandleFunc("/delay/{seconds}", s.delay)
	mux.HandleFunc("GET /bytes/{count}", s.bytes)
	mux.HandleFunc("GET /stream/{lines}", s.stream)
	mux.HandleFunc("GET /drip", s.drip)
	mux.HandleFunc("GET /gzip", s.compressed("gzip"))
	mux.HandleFunc("GET /deflate", s.compressed("deflate"))
	mux.HandleFunc("GET /trailers", s.trailers)
	mux.HandleFunc("GET /basic-auth/{user}/{password}", s.basicAuth)
	mux.HandleFunc("GET /cookies", s.cookies)
	mux.HandleFunc("GET /cookies/set", s.setCookies)
	mux.HandleFunc("GET /cache", s.cache)
	mux.HandleFunc("GET /cache/{seconds}", s.cacheControl)

	// Responses that are wrong on purpose: resets, truncated bodies, invalid framing. See
	// hostile.go.
	s.registerHostile(mux)

	mux.HandleFunc("GET /{$}", s.index)

	return logRequests(mux)
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s %s %s", scheme(r), r.Proto, r.Method, r.URL.RequestURI(), time.Since(start).Round(time.Millisecond))
	})
}

func (s *server) index(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	base := baseURL(r)
	_, _ = fmt.Fprintf(w, "okhttp-testbed test-server\n\n")
	_, _ = fmt.Fprintf(w, "Reached over %s as %s\n\n", r.Proto, base)
	for _, line := range endpointIndex {
		_, _ = fmt.Fprintf(w, "  %-34s %s\n", line.path, line.description)
	}
}

type endpointDoc struct {
	path        string
	description string
}

var endpointIndex = []endpointDoc{
	{"/health", "liveness"},
	{"/info", "listeners, certificate mode, and the URL this request arrived as"},
	{"/ca.pem", "the generated CA, when the server minted its own certificate"},
	{"/client.pem", "a client certificate and key this CA signed, for the mtls listener"},
	{"/tls", "the negotiated handshake and the ClientHello it was chosen from"},
	{"/anything", "the whole request echoed back as JSON (any method)"},
	{"/headers", "request headers, as net/http parsed them"},
	{"/status/{code}", "that status code"},
	{"/redirect/{n}", "n relative redirects, then /anything"},
	{"/absolute-redirect/{n}", "the same with absolute Location headers"},
	{"/redirect-to?url=&status=", "a redirect to a given URL"},
	{"/delay/{seconds}", "a response after a delay"},
	{"/bytes/{n}", "n random bytes with a Content-Length"},
	{"/stream/{n}", "n JSON lines, chunked"},
	{"/drip?duration=&bytes=&delay=", "a body dribbled out over time"},
	{"/gzip, /deflate", "an encoded body"},
	{"/trailers", "a chunked body with trailing headers"},
	{"/basic-auth/{user}/{password}", "401 until those credentials arrive"},
	{"/cookies, /cookies/set?a=b", "cookie round-trip"},
	{"/cache", "conditional GET: ETag and Last-Modified, 304 on revalidation"},
	{"/cache/{seconds}", "a cacheable response with max-age"},
	{"/hostile/...", "responses that are wrong on purpose; GET /hostile for the list"},
}

func (s *server) info(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"server":    "okhttp-testbed/test-server",
		"listeners": s.snapshot(),
		// Echoed back so a deployment behind port mapping can be checked against what the
		// client believes it is talking to, without anything here being configured with it.
		"observed": map[string]any{
			"baseUrl":  baseURL(r),
			"host":     r.Host,
			"protocol": r.Proto,
			"tls":      s.handshake(r),
		},
		"certificate": map[string]any{
			"selfMade": s.certs.selfMade,
			"hosts":    s.certs.hosts,
			"caUrl":    caURL(r, s.certs.selfMade),
		},
	})
}

func caURL(r *http.Request, selfMade bool) string {
	if !selfMade {
		return ""
	}
	return baseURL(r) + "/ca.pem"
}

func (s *server) caPEM(w http.ResponseWriter, _ *http.Request) {
	if !s.certs.selfMade {
		http.Error(w, "this server presents a supplied certificate; there is no fixture CA", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/x-pem-file")
	_, _ = w.Write(s.certs.caPEM)
}

func (s *server) clientPEM(w http.ResponseWriter, _ *http.Request) {
	if len(s.certs.clientPEM) == 0 {
		http.Error(w, "this server presents a supplied certificate; there is no CA to sign a client identity", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/x-pem-file")
	_, _ = w.Write(s.certs.clientPEM)
}

func (s *server) tlsInfo(w http.ResponseWriter, r *http.Request) {
	handshake := s.handshake(r)
	if handshake == nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"tls":      false,
			"protocol": r.Proto,
		})
		return
	}
	writeJSON(w, http.StatusOK, handshake)
}

type echo struct {
	Method     string              `json:"method"`
	URL        string              `json:"url"`
	Path       string              `json:"path"`
	Query      map[string][]string `json:"query"`
	Protocol   string              `json:"protocol"`
	Host       string              `json:"host"`
	Scheme     string              `json:"scheme"`
	RemoteAddr string              `json:"remoteAddr"`
	Headers    map[string][]string `json:"headers"`
	HeaderList []string            `json:"headerList"`
	Body       string              `json:"body"`
	BodyBase64 string              `json:"bodyBase64,omitempty"`
	BodyLength int                 `json:"bodyLength"`
	Truncated  bool                `json:"truncated,omitempty"`
	TLS        *handshakeReport    `json:"tls,omitempty"`
}

func (s *server) anything(w http.ResponseWriter, r *http.Request) {
	body, truncated, err := readBody(r)
	if err != nil {
		http.Error(w, "cannot read request body: "+err.Error(), http.StatusBadRequest)
		return
	}

	result := echo{
		Method:     r.Method,
		URL:        baseURL(r) + r.URL.RequestURI(),
		Path:       r.URL.Path,
		Query:      r.URL.Query(),
		Protocol:   r.Proto,
		Host:       r.Host,
		Scheme:     scheme(r),
		RemoteAddr: r.RemoteAddr,
		Headers:    r.Header,
		HeaderList: headerList(r),
		BodyLength: len(body),
		Truncated:  truncated,
		TLS:        s.handshake(r),
	}
	// A binary body would not survive JSON, and silently mangling it would make the echo a
	// liar. Text goes back as text; anything else goes back base64.
	if utf8.Valid(body) {
		result.Body = string(body)
	} else {
		result.BodyBase64 = base64.StdEncoding.EncodeToString(body)
	}
	writeJSON(w, http.StatusOK, result)
}

func readBody(r *http.Request) ([]byte, bool, error) {
	body, err := io.ReadAll(io.LimitReader(r.Body, maxEchoBody+1))
	if err != nil {
		return nil, false, err
	}
	if len(body) > maxEchoBody {
		return body[:maxEchoBody], true, nil
	}
	return body, false, nil
}

// Header names and values as a sorted "name: value" list.
//
// This is Go's view, not the wire's: net/http canonicalises names to Title-Case and its map
// keeps no order. A test asserting on what OkHttp really put on the wire — casing, order,
// duplicates — wants the raw listener instead. See serveRaw.
func headerList(r *http.Request) []string {
	list := []string{}
	for name, values := range r.Header {
		for _, value := range values {
			list = append(list, name+": "+value)
		}
	}
	sort.Strings(list)
	return list
}

func (s *server) headers(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"headers":    r.Header,
		"headerList": headerList(r),
		"host":       r.Host,
		"protocol":   r.Proto,
	})
}

func (s *server) status(w http.ResponseWriter, r *http.Request) {
	code, err := strconv.Atoi(r.PathValue("code"))
	if err != nil || code < 100 || code > 599 {
		http.Error(w, "status must be between 100 and 599", http.StatusBadRequest)
		return
	}
	// A 3xx without a Location is a redirect a client cannot follow, which is its own useful
	// case; supply one only when asked.
	if location := r.URL.Query().Get("location"); location != "" {
		w.Header().Set("Location", location)
	}
	w.WriteHeader(code)
	if code != http.StatusNoContent && code != http.StatusNotModified && r.Method != http.MethodHead {
		_, _ = fmt.Fprintf(w, "%d %s\n", code, http.StatusText(code))
	}
}

func (s *server) redirect(absolute bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		count, err := strconv.Atoi(r.PathValue("count"))
		if err != nil || count < 1 || count > 100 {
			http.Error(w, "count must be between 1 and 100", http.StatusBadRequest)
			return
		}

		target := "/anything"
		if count > 1 {
			target = fmt.Sprintf("/%s/%d", strings.Trim(r.URL.Path[:strings.LastIndex(r.URL.Path, "/")], "/"), count-1)
		}
		if absolute {
			target = baseURL(r) + target
		}
		w.Header().Set("Location", target)
		w.WriteHeader(http.StatusFound)
	}
}

func (s *server) redirectTo(w http.ResponseWriter, r *http.Request) {
	target := r.URL.Query().Get("url")
	if target == "" {
		http.Error(w, "url is required", http.StatusBadRequest)
		return
	}
	code := http.StatusFound
	if raw := r.URL.Query().Get("status"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 300 || parsed > 399 {
			http.Error(w, "status must be between 300 and 399", http.StatusBadRequest)
			return
		}
		code = parsed
	}
	w.Header().Set("Location", target)
	w.WriteHeader(code)
}

func (s *server) delay(w http.ResponseWriter, r *http.Request) {
	seconds, err := strconv.ParseFloat(r.PathValue("seconds"), 64)
	if err != nil || seconds < 0 || seconds > 60 {
		http.Error(w, "seconds must be between 0 and 60", http.StatusBadRequest)
		return
	}
	select {
	case <-time.After(time.Duration(seconds * float64(time.Second))):
	case <-r.Context().Done():
		return
	}
	s.anything(w, r)
}

func (s *server) bytes(w http.ResponseWriter, r *http.Request) {
	count, err := strconv.Atoi(r.PathValue("count"))
	if err != nil || count < 0 || count > 10<<20 {
		http.Error(w, "count must be between 0 and 10485760", http.StatusBadRequest)
		return
	}
	body := make([]byte, count)
	if _, err := rand.Read(body); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(count))
	_, _ = w.Write(body)
}

func (s *server) stream(w http.ResponseWriter, r *http.Request) {
	lines, err := strconv.Atoi(r.PathValue("lines"))
	if err != nil || lines < 1 || lines > 1000 {
		http.Error(w, "lines must be between 1 and 1000", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	// No Content-Length and a flush per line: chunked on HTTP/1.1, DATA frames on HTTP/2.
	for i := range lines {
		_, _ = fmt.Fprintf(w, "{\"line\":%d,\"protocol\":%q}\n", i, r.Proto)
		flush(w)
	}
}

func (s *server) drip(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	total := queryInt(query, "bytes", 10, 1, 1<<20)
	duration := queryDuration(query, "duration", 1*time.Second)
	initial := queryDuration(query, "delay", 0)

	if initial > 0 {
		select {
		case <-time.After(initial):
		case <-r.Context().Done():
			return
		}
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(total))
	pause := duration / time.Duration(max(total, 1))
	for range total {
		if _, err := w.Write([]byte{'*'}); err != nil {
			return
		}
		flush(w)
		select {
		case <-time.After(pause):
		case <-r.Context().Done():
			return
		}
	}
}

func (s *server) compressed(encoding string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		payload, err := json.Marshal(map[string]any{
			"encoding": encoding,
			"protocol": r.Proto,
			"headers":  r.Header,
		})
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Encoding", encoding)
		// Encoded whether or not the client offered it. A client that asked for identity and
		// gets gzip anyway is a case worth being able to test.
		var encoder io.WriteCloser
		if encoding == "gzip" {
			encoder = gzip.NewWriter(w)
		} else {
			encoder, err = flate.NewWriter(w, flate.DefaultCompression)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
		}
		_, _ = encoder.Write(payload)
		_ = encoder.Close()
	}
}

func (s *server) trailers(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	// Announcing them up front is what HTTP/1.1 requires and what lets a client know to keep
	// reading; the values are set after the body, which is the whole point of a trailer.
	w.Header().Set("Trailer", "X-Testbed-Checksum, X-Testbed-Protocol")

	body := "trailers\n"
	_, _ = io.WriteString(w, body)
	flush(w)

	sum := sha256.Sum256([]byte(body))
	w.Header().Set("X-Testbed-Checksum", fmt.Sprintf("%x", sum[:8]))
	w.Header().Set("X-Testbed-Protocol", r.Proto)
}

func (s *server) basicAuth(w http.ResponseWriter, r *http.Request) {
	wantUser, wantPassword := r.PathValue("user"), r.PathValue("password")
	user, password, ok := r.BasicAuth()
	if !ok || user != wantUser || password != wantPassword {
		w.Header().Set("WWW-Authenticate", `Basic realm="okhttp-testbed"`)
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"authenticated": true, "user": user})
}

func (s *server) cookies(w http.ResponseWriter, r *http.Request) {
	cookies := map[string]string{}
	for _, cookie := range r.Cookies() {
		cookies[cookie.Name] = cookie.Value
	}
	writeJSON(w, http.StatusOK, map[string]any{"cookies": cookies})
}

func (s *server) setCookies(w http.ResponseWriter, r *http.Request) {
	for name, values := range r.URL.Query() {
		for _, value := range values {
			http.SetCookie(w, &http.Cookie{Name: name, Value: value, Path: "/"})
		}
	}
	w.Header().Set("Location", "/cookies")
	w.WriteHeader(http.StatusFound)
}

// A conditional GET. The entity is fixed, so a revalidation always matches and the client's
// own cache handling is what is under test.
func (s *server) cache(w http.ResponseWriter, r *http.Request) {
	const etag = `"okhttp-testbed"`
	modified := time.Date(2026, time.January, 1, 0, 0, 0, 0, time.UTC)

	w.Header().Set("ETag", etag)
	w.Header().Set("Last-Modified", modified.Format(http.TimeFormat))

	if match := r.Header.Get("If-None-Match"); match == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	if since, err := http.ParseTime(r.Header.Get("If-Modified-Since")); err == nil && !modified.After(since) {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"cached": true, "protocol": r.Proto})
}

func (s *server) cacheControl(w http.ResponseWriter, r *http.Request) {
	seconds, err := strconv.Atoi(r.PathValue("seconds"))
	if err != nil || seconds < 0 || seconds > 86400 {
		http.Error(w, "seconds must be between 0 and 86400", http.StatusBadRequest)
		return
	}
	w.Header().Set("Cache-Control", fmt.Sprintf("public, max-age=%d", seconds))
	writeJSON(w, http.StatusOK, map[string]any{"maxAge": seconds, "protocol": r.Proto})
}

func writeJSON(w http.ResponseWriter, code int, body any) {
	payload, err := json.MarshalIndent(body, "", "  ")
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	payload = append(payload, '\n')
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
	w.WriteHeader(code)
	_, _ = w.Write(payload)
}

func flush(w http.ResponseWriter) {
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
}

func queryInt(query map[string][]string, name string, fallback, low, high int) int {
	values := query[name]
	if len(values) == 0 {
		return fallback
	}
	value, err := strconv.Atoi(values[0])
	if err != nil || value < low || value > high {
		return fallback
	}
	return value
}

func queryDuration(query map[string][]string, name string, fallback time.Duration) time.Duration {
	values := query[name]
	if len(values) == 0 {
		return fallback
	}
	seconds, err := strconv.ParseFloat(values[0], 64)
	if err != nil || seconds < 0 || seconds > 60 {
		return fallback
	}
	return time.Duration(seconds * float64(time.Second))
}
