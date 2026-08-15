// Command test-server is the testbed's own HTTP and TLS server: a fixture the suites can
// assert positive results against, and the one endpoint that can report what a client's
// handshake actually looked like.
//
// It is deliberately not built on OkHttp. A server sharing its framing and TLS setup with
// the client under test cannot answer whether that client's ClientHello or its HTTP/2
// SETTINGS are acceptable to anything else — the whole reason this repository exists. Go's
// stdlib is an independent implementation of both, and nothing here depends on anything
// outside it.
//
// Every listener's address comes from the environment and every URL the server generates is
// built from the Host the request arrived with, so a deployment on a non-standard port
// behaves exactly like one on 443. See README.md.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

// A listener the server offers, as reported by /info.
type listener struct {
	Name       string `json:"name"`
	Addr       string `json:"addr"`
	TLS        bool   `json:"tls"`
	MinVersion string `json:"minVersion,omitempty"`
	MaxVersion string `json:"maxVersion,omitempty"`
	ALPN       string `json:"alpn,omitempty"`
	Note       string `json:"note,omitempty"`
}

type server struct {
	certs     *certificates
	hellos    *helloCache
	listeners []listener
	mu        sync.Mutex
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)

	certs, err := loadCertificates()
	if err != nil {
		log.Fatalf("certificates: %v", err)
	}

	s := &server{certs: certs, hellos: newHelloCache()}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	var wg sync.WaitGroup
	serve := func(name string, run func() error) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := run(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				log.Fatalf("%s: %v", name, err)
			}
		}()
	}

	// Plain HTTP. HTTP/1.1 only: Go serves h2c only with an explicit handler wrapper from
	// golang.org/x/net, and this program stays on the standard library. The compose stack's
	// nghttp2 container is what covers h2c — see README.md.
	if addr := env("HTTP_ADDR", ":8080"); addr != "" {
		s.addListener(listener{Name: "http", Addr: addr, ALPN: "http/1.1"})
		srv := s.httpServer(addr)
		serve("http", func() error { return srv.ListenAndServe() })
		s.shutdownWith(ctx, srv)
	}

	// The main TLS listener. ALPN offers h2, so this is where the HTTP/2 assertions land.
	if addr := env("HTTPS_ADDR", ":8443"); addr != "" {
		s.addListener(listener{
			Name: "https", Addr: addr, TLS: true,
			MinVersion: "TLSv1.2", MaxVersion: "TLSv1.3", ALPN: "h2, http/1.1",
		})
		srv := s.tlsServer(addr, tlsVersions{}, true)
		serve("https", func() error { return srv.ListenAndServeTLS("", "") })
		s.shutdownWith(ctx, srv)
	}

	// A port per TLS version, the way badssl.com does it, so a ConnectionSpec can be checked
	// against a server that will negotiate one version and nothing else. HTTP/1.1 only: h2
	// requires TLS 1.2 or better, and offering it on the 1.0 and 1.1 ports would mean these
	// listeners differed in two ways rather than one.
	for _, v := range perVersionListeners() {
		if v.addr == "" {
			continue
		}
		s.addListener(listener{
			Name: v.name, Addr: v.addr, TLS: true,
			MinVersion: v.label, MaxVersion: v.label, ALPN: "http/1.1",
			Note: v.note,
		})
		srv := s.tlsServer(v.addr, tlsVersions{min: v.version, max: v.version}, false)
		version := v
		serve(v.name, func() error {
			err := srv.ListenAndServeTLS("", "")
			// Go's crypto/tls can drop support for an obsolete version between releases. That
			// should cost us that one listener, not the whole server.
			if err != nil && !errors.Is(err, http.ErrServerClosed) && version.optional {
				log.Printf("%s: unavailable in this Go release: %v", version.name, err)
				return nil
			}
			return err
		})
		s.shutdownWith(ctx, srv)
	}

	// TLS 1.3 with no classical key exchange fallback. A client reaches this listener only if its
	// TLS provider offers the hybrid post-quantum group standardized by RFC 9794.
	if addr := env("PQC_ADDR", ":8414"); addr != "" {
		s.addListener(listener{
			Name: "pqc", Addr: addr, TLS: true,
			MinVersion: "TLSv1.3", MaxVersion: "TLSv1.3", ALPN: "http/1.1",
			Note: "requires the X25519MLKEM768 post-quantum hybrid named group",
		})
		srv := s.tlsServer(addr, tlsVersions{min: tls.VersionTLS13, max: tls.VersionTLS13}, false)
		srv.TLSConfig.CurvePreferences = []tls.CurveID{tls.X25519MLKEM768}
		serve("pqc", func() error { return srv.ListenAndServeTLS("", "") })
		s.shutdownWith(ctx, srv)
	}

	// Mutual TLS. Only when the server minted its own CA: verifying a client certificate needs
	// a pool to verify it against, and a deployment holding a supplied certificate has no
	// signing key to have issued one with.
	if addr := env("MTLS_ADDR", ":8425"); addr != "" && s.certs.clientCAs != nil {
		s.addListener(listener{
			Name: "mtls", Addr: addr, TLS: true,
			MinVersion: "TLSv1.2", MaxVersion: "TLSv1.3", ALPN: "http/1.1",
			Note: "requires a client certificate signed by the fixture CA; see /client.pem",
		})
		srv := s.mtlsServer(addr)
		serve("mtls", func() error { return srv.ListenAndServeTLS("", "") })
		s.shutdownWith(ctx, srv)
	}

	// A listener per rejectable chain, the local half of the badssl matrix. Each one differs
	// from the https listener in exactly one way — the certificate it presents — so a client
	// refusing one of these and accepting https has said something specific. HTTP/1.1 only:
	// what is under test is the handshake, and none of these should reach a request.
	for _, chain := range s.certs.badChains {
		addr := badChainAddr(chain.name)
		if addr == "" {
			continue
		}
		s.addListener(listener{
			Name: "badchain-" + chain.name, Addr: addr, TLS: true,
			MinVersion: "TLSv1.2", MaxVersion: "TLSv1.3", ALPN: "http/1.1",
			Note: chain.why,
		})
		srv := s.tlsServerWith(addr, tlsVersions{}, false, chain.certificate)
		name := "badchain-" + chain.name
		serve(name, func() error { return srv.ListenAndServeTLS("", "") })
		s.shutdownWith(ctx, srv)
	}

	// The raw listener, which is not an HTTP server: it echoes the request head back byte for
	// byte. net/http canonicalises header names and drops their order, so /anything reports
	// what Go parsed rather than what OkHttp sent. This is the only endpoint that reports the
	// bytes on the wire — header order and casing included.
	if addr := env("RAW_ADDR", ":8081"); addr != "" {
		s.addListener(listener{Name: "raw", Addr: addr, Note: "echoes the request head verbatim; not an HTTP server"})
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			log.Fatalf("raw: %v", err)
		}
		go func() {
			<-ctx.Done()
			_ = ln.Close()
		}()
		serve("raw", func() error { return serveRaw(ln) })
	}

	for _, l := range s.snapshot() {
		log.Printf("listening: %-6s %s", l.Name, l.Addr)
	}

	wg.Wait()
	log.Print("stopped")
}

type versionListener struct {
	name     string
	addr     string
	label    string
	version  uint16
	note     string
	optional bool
}

func (s *server) addListener(l listener) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.listeners = append(s.listeners, l)
}

func (s *server) snapshot() []listener {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]listener(nil), s.listeners...)
}

func (s *server) shutdownWith(ctx context.Context, srv *http.Server) {
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdown)
	}()
}

func (s *server) httpServer(addr string) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           s.handler(),
		ReadHeaderTimeout: 30 * time.Second,
	}
}

func env(name, fallback string) string {
	if value, ok := os.LookupEnv(name); ok {
		return strings.TrimSpace(value)
	}
	return fallback
}

// scheme reports what the client used to reach us, which is what absolute URLs have to be
// built from. A deployment behind a terminating proxy sets X-Forwarded-Proto.
func scheme(r *http.Request) string {
	if forwarded := r.Header.Get("X-Forwarded-Proto"); forwarded != "" {
		return forwarded
	}
	if r.TLS != nil {
		return "https"
	}
	return "http"
}

// baseURL is built from the Host header the request carried, port and all. Nothing here
// knows what port it was deployed on, which is what makes a non-standard port a
// non-event: a redirect to /status/200 from :18443 lands back on :18443.
func baseURL(r *http.Request) string {
	return fmt.Sprintf("%s://%s", scheme(r), r.Host)
}
