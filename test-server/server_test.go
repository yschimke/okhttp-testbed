package main

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The behaviour these tests are about is what a client sees, so they run against real
// listeners rather than calling handlers directly: the framing, the hijacked responses and
// the handshake reporting only exist on a connection.

func newTestServer(t *testing.T) (*server, *httptest.Server) {
	t.Helper()
	certs, err := loadCertificates()
	if err != nil {
		t.Fatalf("certificates: %v", err)
	}
	s := &server{certs: certs, hellos: newHelloCache()}
	httpServer := httptest.NewServer(s.handler())
	t.Cleanup(httpServer.Close)
	return s, httpServer
}

// Absolute URLs are built from the Host the request arrived with, which is what makes a
// deployment on a non-standard port behave like one on 443.
func TestAbsoluteRedirectKeepsTheRequestedPort(t *testing.T) {
	_, httpServer := newTestServer(t)

	client := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	response, err := client.Get(httpServer.URL + "/absolute-redirect/2")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	want := httpServer.URL + "/absolute-redirect/1"
	if got := response.Header.Get("Location"); got != want {
		t.Errorf("Location = %q, want %q", got, want)
	}
}

func TestRedirectChainEndsAtAnything(t *testing.T) {
	_, httpServer := newTestServer(t)

	response, err := http.Get(httpServer.URL + "/redirect/3")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	if got := response.Request.URL.Path; got != "/anything" {
		t.Errorf("final path = %q, want /anything", got)
	}
}

func TestAnythingEchoesTheRequest(t *testing.T) {
	_, httpServer := newTestServer(t)

	request, err := http.NewRequest(http.MethodPut, httpServer.URL+"/anything/deep/path?q=1", strings.NewReader("body"))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Testbed", "yes")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	var echoed echo
	if err := json.NewDecoder(response.Body).Decode(&echoed); err != nil {
		t.Fatal(err)
	}
	if echoed.Method != http.MethodPut {
		t.Errorf("method = %q, want PUT", echoed.Method)
	}
	if echoed.Body != "body" {
		t.Errorf("body = %q, want %q", echoed.Body, "body")
	}
	if echoed.Path != "/anything/deep/path" {
		t.Errorf("path = %q", echoed.Path)
	}
	if got := echoed.Headers["X-Testbed"]; len(got) != 1 || got[0] != "yes" {
		t.Errorf("X-Testbed = %v", got)
	}
	if got := echoed.Query["q"]; len(got) != 1 || got[0] != "1" {
		t.Errorf("q = %v", got)
	}
}

// A body that is not valid UTF-8 has to survive the round trip, and JSON cannot carry it as
// a string.
func TestAnythingBase64sABinaryBody(t *testing.T) {
	_, httpServer := newTestServer(t)

	response, err := http.Post(httpServer.URL+"/anything", "application/octet-stream", strings.NewReader("\xff\xfe"))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	var echoed echo
	if err := json.NewDecoder(response.Body).Decode(&echoed); err != nil {
		t.Fatal(err)
	}
	if echoed.Body != "" || echoed.BodyBase64 != "//4=" {
		t.Errorf("body = %q, bodyBase64 = %q", echoed.Body, echoed.BodyBase64)
	}
}

func TestTrailersArriveAfterTheBody(t *testing.T) {
	_, httpServer := newTestServer(t)

	response, err := http.Get(httpServer.URL + "/trailers")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	// The client moves the announced names out of Header and into Trailer, keyed but empty,
	// before the body has been read.
	if _, announced := response.Trailer["X-Testbed-Checksum"]; !announced {
		t.Errorf("trailers were not announced: header %v, trailer %v", response.Header, response.Trailer)
	}
	// Trailers are only populated once the body has been read to the end.
	if _, err := io.ReadAll(response.Body); err != nil {
		t.Fatal(err)
	}
	if got := response.Trailer.Get("X-Testbed-Checksum"); got == "" {
		t.Error("no X-Testbed-Checksum trailer")
	}
}

func TestConditionalGetRevalidates(t *testing.T) {
	_, httpServer := newTestServer(t)

	first, err := http.Get(httpServer.URL + "/cache")
	if err != nil {
		t.Fatal(err)
	}
	first.Body.Close()

	request, err := http.NewRequest(http.MethodGet, httpServer.URL+"/cache", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("If-None-Match", first.Header.Get("ETag"))
	second, err := http.DefaultTransport.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Body.Close()

	if second.StatusCode != http.StatusNotModified {
		t.Errorf("status = %d, want 304", second.StatusCode)
	}
}

// Each hostile endpoint has to be wrong in its own specific way, and every one of them has
// to leave the client with an error rather than a response.
func TestHostileEndpointsFail(t *testing.T) {
	_, httpServer := newTestServer(t)

	for _, path := range []string{
		"/hostile/no-response",
		"/hostile/reset",
		"/hostile/truncated-body",
		"/hostile/truncated-chunks",
		"/hostile/invalid-chunk-size",
		"/hostile/invalid-status-line",
	} {
		t.Run(path, func(t *testing.T) {
			response, err := http.Get(httpServer.URL + path)
			if err == nil {
				_, err = io.ReadAll(response.Body)
				response.Body.Close()
			}
			if err == nil {
				t.Errorf("%s produced a complete response; it is supposed to be broken", path)
			}
		})
	}
}

// The raw listener is the only place the bytes on the wire survive: net/http canonicalises
// header names and forgets their order.
func TestRawListenerEchoesHeaderCasingAndOrder(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() { _ = serveRaw(ln) }()

	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	if _, err := io.WriteString(conn, "GET /raw HTTP/1.1\r\nHost: x\r\nzZ-Odd-CASE: 1\r\nAccept: */*\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	echoed, err := io.ReadAll(conn)
	if err != nil {
		t.Fatal(err)
	}

	body := string(echoed)
	if !strings.Contains(body, "zZ-Odd-CASE: 1") {
		t.Errorf("header casing was not preserved:\n%s", body)
	}
	if strings.Index(body, "zZ-Odd-CASE") > strings.Index(body, "Accept:") {
		t.Errorf("header order was not preserved:\n%s", body)
	}
}

// The point of the whole TLS half: /tls reports the negotiated handshake and the offer it
// came from, over a certificate chain the client can actually verify.
func TestTLSReportsTheHandshakeAndTheOffer(t *testing.T) {
	s, plain := newTestServer(t)
	if !s.certs.selfMade {
		t.Skip("TLS_CERT_FILE is set; there is no fixture CA to trust")
	}

	tlsServer := httptest.NewUnstartedServer(s.handler())
	tlsServer.TLS = s.tlsServer("", tlsVersions{}, true).TLSConfig
	tlsServer.StartTLS()
	defer tlsServer.Close()

	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEMOf(t, plain)) {
		t.Fatal("the fixture CA did not parse")
	}
	client := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{
		RootCAs:    pool,
		MinVersion: tls.VersionTLS12,
	}}}

	response, err := client.Get(tlsServer.URL + "/tls")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	var report handshakeReport
	if err := json.NewDecoder(response.Body).Decode(&report); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(report.Version, "TLSv1.") {
		t.Errorf("version = %q", report.Version)
	}
	if report.Offered == nil {
		t.Fatalf("no ClientHello recorded: %s", report.OfferedUnavailable)
	}
	if len(report.Offered.CipherSuites) == 0 {
		t.Error("no offered cipher suites")
	}
	if len(report.Offered.SupportedVersions) == 0 {
		t.Error("no offered versions")
	}
	// The extension list is what the GREASE question is asked through, so an empty one would
	// make that suite pass vacuously against a server that never recorded anything.
	if len(report.Offered.Extensions) == 0 {
		t.Error("no offered extensions")
	}
	// Go's own client has no ECH configuration here and does not GREASE, so this is the
	// negative case: a false that means "not offered" rather than "never looked".
	if report.Offered.EncryptedClientHelloOffered {
		t.Error("Go's client offered encrypted_client_hello with nothing configured")
	}
}

func TestExtensionNames(t *testing.T) {
	for _, c := range []struct {
		extension uint16
		want      string
	}{
		{0, "server_name"},
		{extensionEncryptedClientHello, "encrypted_client_hello"},
		// RFC 8701's sixteen reserved values, named rather than left as mystery hex.
		{0x0a0a, "GREASE(0x0a0a)"},
		{0xfafa, "GREASE(0xfafa)"},
		// Not GREASE: the halves differ, so the pattern must not match on the low byte alone.
		{0x1a2a, "0x1a2a"},
		{0x1234, "0x1234"},
	} {
		if got := extensionName(c.extension); got != c.want {
			t.Errorf("extensionName(0x%04x) = %q, want %q", c.extension, got, c.want)
		}
	}
}

func caPEMOf(t *testing.T, plain *httptest.Server) []byte {
	t.Helper()
	response, err := http.Get(plain.URL + "/ca.pem")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	pem, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	return pem
}

// The per-version listeners are the fixture a ConnectionSpec test gets pointed at, so each
// one has to negotiate its version and refuse the others.
func TestPerVersionListenersPinTheirVersion(t *testing.T) {
	s, plain := newTestServer(t)
	if !s.certs.selfMade {
		t.Skip("TLS_CERT_FILE is set; there is no fixture CA to trust")
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEMOf(t, plain)) {
		t.Fatal("the fixture CA did not parse")
	}

	for _, version := range []struct {
		name  string
		id    uint16
		label string
	}{
		{"tls12", tls.VersionTLS12, "TLSv1.2"},
		{"tls13", tls.VersionTLS13, "TLSv1.3"},
	} {
		t.Run(version.name, func(t *testing.T) {
			tlsServer := httptest.NewUnstartedServer(s.handler())
			tlsServer.TLS = s.tlsServer("", tlsVersions{min: version.id, max: version.id}, false).TLSConfig
			tlsServer.StartTLS()
			defer tlsServer.Close()

			get := func(min, max uint16) (*http.Response, error) {
				client := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{
					RootCAs:    pool,
					MinVersion: min,
					MaxVersion: max,
				}}}
				return client.Get(tlsServer.URL + "/tls")
			}

			response, err := get(version.id, version.id)
			if err != nil {
				t.Fatalf("a client offering only %s was refused: %v", version.label, err)
			}
			var report handshakeReport
			if err := json.NewDecoder(response.Body).Decode(&report); err != nil {
				t.Fatal(err)
			}
			response.Body.Close()
			if report.Version != version.label {
				t.Errorf("negotiated %q, want %q", report.Version, version.label)
			}
			if report.NegotiatedProtocol == "h2" {
				t.Error("the per-version listeners offer http/1.1 only")
			}

			other := tls.VersionTLS12
			if version.id == tls.VersionTLS12 {
				other = tls.VersionTLS13
			}
			if response, err := get(uint16(other), uint16(other)); err == nil {
				response.Body.Close()
				t.Errorf("the %s listener accepted another version", version.label)
			}
		})
	}
}

// The local half of the badssl matrix. Every one of these must be refused by a client that
// trusts the fixture CA — and refused for its own reason, not because the fixture is broken.
//
// The guard against that last risk is the good listener: the same client, the same trust
// anchor, the same handler, succeeding over the ordinary certificate. A run where https also
// failed would mean the test proved nothing.
func TestBadChainsAreRejected(t *testing.T) {
	s, plain := newTestServer(t)
	if !s.certs.selfMade {
		t.Skip("TLS_CERT_FILE is set; there are no fixture chains to mint")
	}

	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEMOf(t, plain)) {
		t.Fatal("the fixture CA did not parse")
	}
	client := func(roots *x509.CertPool) *http.Client {
		return &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{
			RootCAs:    roots,
			MinVersion: tls.VersionTLS12,
		}}}
	}

	// The control: the good certificate, verified by the same client that is about to refuse
	// everything else.
	good := httptest.NewUnstartedServer(s.handler())
	good.TLS = s.tlsServer("", tlsVersions{}, false).TLSConfig
	good.StartTLS()
	defer good.Close()

	response, err := client(pool).Get(good.URL + "/tls")
	if err != nil {
		t.Fatalf("the good chain was refused, so this test can prove nothing: %v", err)
	}
	_ = response.Body.Close()

	if len(s.certs.badChains) == 0 {
		t.Fatal("no bad chains were minted")
	}

	for _, chain := range s.certs.badChains {
		t.Run(chain.name, func(t *testing.T) {
			server := httptest.NewUnstartedServer(s.handler())
			server.TLS = s.tlsServerWith("", tlsVersions{}, false, chain.certificate).TLSConfig
			server.StartTLS()
			defer server.Close()

			response, err := client(pool).Get(server.URL + "/tls")
			if err == nil {
				_ = response.Body.Close()
				t.Fatalf("%s was accepted; it must be refused (%s)", chain.name, chain.why)
			}

			// Nothing here asserts on the message. Which error a client reports for a bad chain
			// is the client's business — and reporting that difference is what the OkHttp suite
			// pointed at this fixture is for.
			t.Logf("%s refused: %v", chain.name, err)
		})
	}
}

// incomplete-chain has to fail for the reason it claims: the path is short, not the leaf bad.
// Supplying the intermediate the server withholds must make the same certificate verify.
func TestIncompleteChainVerifiesOnceCompleted(t *testing.T) {
	s, plain := newTestServer(t)
	if !s.certs.selfMade {
		t.Skip("TLS_CERT_FILE is set; there are no fixture chains to mint")
	}

	var incomplete *badChain
	for i := range s.certs.badChains {
		if s.certs.badChains[i].name == "incomplete-chain" {
			incomplete = &s.certs.badChains[i]
		}
	}
	if incomplete == nil {
		t.Fatal("no incomplete-chain fixture")
	}
	if len(incomplete.withheld) == 0 {
		t.Fatal("incomplete-chain withholds nothing, so there is nothing for a client to supply")
	}

	server := httptest.NewUnstartedServer(s.handler())
	server.TLS = s.tlsServerWith("", tlsVersions{}, false, incomplete.certificate).TLSConfig
	server.StartTLS()
	defer server.Close()

	// The CA alone: the intermediate is missing, so no path can be built.
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEMOf(t, plain)) {
		t.Fatal("the fixture CA did not parse")
	}
	client := func(roots *x509.CertPool) *http.Client {
		return &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{
			RootCAs:    roots,
			MinVersion: tls.VersionTLS12,
		}}}
	}
	if response, err := client(pool).Get(server.URL + "/tls"); err == nil {
		_ = response.Body.Close()
		t.Fatal("the incomplete chain verified without the intermediate")
	}

	// The CA and the withheld intermediate: the same leaf, now reachable.
	completed := x509.NewCertPool()
	if !completed.AppendCertsFromPEM(caPEMOf(t, plain)) {
		t.Fatal("the fixture CA did not parse")
	}
	if !completed.AppendCertsFromPEM(incomplete.withheld) {
		t.Fatal("the withheld intermediate did not parse")
	}
	response, err := client(completed).Get(server.URL + "/tls")
	if err != nil {
		t.Fatalf("the chain did not verify even with the intermediate supplied, "+
			"so it is broken rather than incomplete: %v", err)
	}
	_ = response.Body.Close()
}
