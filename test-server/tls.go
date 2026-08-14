package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// The certificates the TLS listeners present.
//
// Two modes, and the difference matters to what a suite can assert. With TLS_CERT_FILE and
// TLS_KEY_FILE set — a deployment holding a real certificate — the chain is whatever those
// files contain and clients trust it the ordinary way. With neither set, the server mints
// its own CA at startup and serves it at /ca.pem: a client that fetches it over the plain
// port gets an anchor nobody else controls, which is what lets a test assert that a chain
// *is* accepted rather than only that a bad one is refused.
type certificates struct {
	leaf     tls.Certificate
	caPEM    []byte
	selfMade bool
	hosts    []string
}

func loadCertificates() (*certificates, error) {
	hosts := certHosts()

	certFile, keyFile := env("TLS_CERT_FILE", ""), env("TLS_KEY_FILE", "")
	if (certFile == "") != (keyFile == "") {
		return nil, fmt.Errorf("TLS_CERT_FILE and TLS_KEY_FILE must be set together")
	}
	if certFile != "" {
		leaf, err := tls.LoadX509KeyPair(certFile, keyFile)
		if err != nil {
			return nil, err
		}
		return &certificates{leaf: leaf, hosts: hosts}, nil
	}

	caCert, caKey, caPEM, err := newCA()
	if err != nil {
		return nil, err
	}
	leaf, err := newLeaf(caCert, caKey, hosts)
	if err != nil {
		return nil, err
	}
	return &certificates{leaf: leaf, caPEM: caPEM, selfMade: true, hosts: hosts}, nil
}

// The names and addresses the generated leaf covers. Deployments add their own hostname
// with CERT_HOSTS; the loopback entries are always present so the same image works under
// Testcontainers without configuration.
func certHosts() []string {
	hosts := []string{"localhost", "127.0.0.1", "::1"}
	for _, host := range strings.Split(env("CERT_HOSTS", ""), ",") {
		if host = strings.TrimSpace(host); host != "" {
			hosts = append(hosts, host)
		}
	}
	return hosts
}

func newCA() (*x509.Certificate, *ecdsa.PrivateKey, []byte, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, nil, err
	}
	template := &x509.Certificate{
		SerialNumber:          serialNumber(),
		Subject:               pkix.Name{CommonName: "okhttp-testbed test-server CA"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(1, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, nil, nil, err
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, nil, nil, err
	}
	return cert, key, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), nil
}

func newLeaf(caCert *x509.Certificate, caKey *ecdsa.PrivateKey, hosts []string) (tls.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	template := &x509.Certificate{
		SerialNumber: serialNumber(),
		Subject:      pkix.Name{CommonName: hosts[0]},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(1, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	for _, host := range hosts {
		if ip := net.ParseIP(host); ip != nil {
			template.IPAddresses = append(template.IPAddresses, ip)
		} else {
			template.DNSNames = append(template.DNSNames, host)
		}
	}
	der, err := x509.CreateCertificate(rand.Reader, template, caCert, &key.PublicKey, caKey)
	if err != nil {
		return tls.Certificate{}, err
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return tls.Certificate{}, err
	}
	return tls.X509KeyPair(
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}),
	)
}

func serialNumber() *big.Int {
	limit := new(big.Int).Lsh(big.NewInt(1), 128)
	serial, err := rand.Int(rand.Reader, limit)
	if err != nil {
		// rand.Int fails only if the entropy source does, and then nothing else here works either.
		panic(err)
	}
	return serial
}

type tlsVersions struct {
	min uint16
	max uint16
}

func perVersionListeners() []versionListener {
	return []versionListener{
		{
			name: "tls10", addr: env("TLS10_ADDR", ":8410"), label: "TLSv1.0",
			version: tls.VersionTLS10, optional: true,
			note: "Go refuses TLS 1.0 and 1.1 by default; this listener enables them explicitly",
		},
		{
			name: "tls11", addr: env("TLS11_ADDR", ":8411"), label: "TLSv1.1",
			version: tls.VersionTLS11, optional: true,
			note: "Go refuses TLS 1.0 and 1.1 by default; this listener enables them explicitly",
		},
		{name: "tls12", addr: env("TLS12_ADDR", ":8412"), label: "TLSv1.2", version: tls.VersionTLS12},
		{name: "tls13", addr: env("TLS13_ADDR", ":8413"), label: "TLSv1.3", version: tls.VersionTLS13},
	}
}

func (s *server) tlsServer(addr string, versions tlsVersions, http2 bool) *http.Server {
	config := &tls.Config{
		Certificates: []tls.Certificate{s.certs.leaf},
		MinVersion:   tls.VersionTLS12,
		// Client certificates are accepted but never required, so /tls can report whether one
		// was offered without the endpoint becoming unusable to a client that has none.
		ClientAuth: tls.RequestClientCert,
	}
	if versions.min != 0 {
		config.MinVersion, config.MaxVersion = versions.min, versions.max
	}
	if versions.min <= tls.VersionTLS11 && versions.min != 0 {
		// The obsolete versions have no suites in common with Go's modern default list.
		config.CipherSuites = obsoleteCipherSuites()
	}

	// Every ClientHello is stashed here on its way past, keyed by the connection it arrived
	// on, so /tls can report what the client offered rather than only what was negotiated.
	// crypto/tls exposes no other route to it: ConnectionState carries the outcome.
	config.GetConfigForClient = func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
		s.hellos.put(hello)
		return nil, nil
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           s.handler(),
		TLSConfig:         config,
		ReadHeaderTimeout: 30 * time.Second,
		ConnState: func(conn net.Conn, state http.ConnState) {
			if state == http.StateClosed || state == http.StateHijacked {
				s.hellos.drop(conn.RemoteAddr().String())
			}
		},
	}
	if !http2 {
		// Suppress net/http's automatic HTTP/2 setup, which would otherwise add h2 to ALPN.
		srv.TLSNextProto = map[string]func(*http.Server, *tls.Conn, http.Handler){}
	}
	return srv
}

// TLS 1.0 and 1.1 negotiate none of Go's default suites. Naming them explicitly is what makes
// those listeners usable at all; they are deliberately obsolete, which is the point of a
// fixture a ConnectionSpec test can be pointed at.
func obsoleteCipherSuites() []uint16 {
	var suites []uint16
	for _, suite := range tls.InsecureCipherSuites() {
		suites = append(suites, suite.ID)
	}
	for _, suite := range tls.CipherSuites() {
		suites = append(suites, suite.ID)
	}
	return suites
}

// What the client offered, captured from its ClientHello.
type helloCache struct {
	mu     sync.Mutex
	hellos map[string]*tls.ClientHelloInfo
}

func newHelloCache() *helloCache {
	return &helloCache{hellos: map[string]*tls.ClientHelloInfo{}}
}

func (c *helloCache) put(hello *tls.ClientHelloInfo) {
	if hello.Conn == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	// A connection reaching this map is one whose handshake is in progress, so the map holds
	// at most one entry per live connection and ConnState clears them. The bound is belt and
	// braces against a peer that opens connections and never completes a handshake.
	if len(c.hellos) > 1024 {
		c.hellos = map[string]*tls.ClientHelloInfo{}
	}
	c.hellos[hello.Conn.RemoteAddr().String()] = hello
}

func (c *helloCache) get(remoteAddr string) *tls.ClientHelloInfo {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.hellos[remoteAddr]
}

func (c *helloCache) drop(remoteAddr string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.hellos, remoteAddr)
}

// What /tls reports: the negotiated outcome, and the offer it was chosen from.
type handshakeReport struct {
	Version             string           `json:"version"`
	CipherSuite         string           `json:"cipherSuite"`
	NegotiatedProtocol  string           `json:"negotiatedProtocol"`
	ServerName          string           `json:"serverName"`
	Resumed             bool             `json:"resumed"`
	ECHAccepted         bool             `json:"echAccepted"`
	ClientCertificates  []string         `json:"clientCertificates"`
	HTTPProtocol        string           `json:"httpProtocol"`
	Offered             *clientHelloInfo `json:"offered,omitempty"`
	OfferedUnavailable  string           `json:"offeredUnavailable,omitempty"`
	CertificateSelfMade bool             `json:"certificateSelfMade"`
}

// The offer. Note what is missing: crypto/tls hands a server the parsed fields, not the
// ClientHello's extension list or its order, so this is not enough to compute a JA3 or JA4
// fingerprint. It answers what OkHttp offered; it does not answer how a CDN fingerprints it.
type clientHelloInfo struct {
	SupportedVersions []string `json:"supportedVersions"`
	CipherSuites      []string `json:"cipherSuites"`
	SupportedCurves   []string `json:"supportedCurves"`
	// Point formats are bytes, and a []uint8 would reach JSON base64-encoded rather than as
	// the numbers they are.
	SupportedPoints  []int    `json:"supportedPoints"`
	SignatureSchemes []string `json:"signatureSchemes"`
	ALPNProtocols    []string `json:"alpnProtocols"`
	ServerName       string   `json:"serverName"`
}

func (s *server) handshake(r *http.Request) *handshakeReport {
	if r.TLS == nil {
		return nil
	}
	state := r.TLS
	report := &handshakeReport{
		Version:             versionName(state.Version),
		CipherSuite:         tls.CipherSuiteName(state.CipherSuite),
		NegotiatedProtocol:  state.NegotiatedProtocol,
		ServerName:          state.ServerName,
		Resumed:             state.DidResume,
		ECHAccepted:         state.ECHAccepted,
		HTTPProtocol:        r.Proto,
		ClientCertificates:  []string{},
		CertificateSelfMade: s.certs.selfMade,
	}
	for _, cert := range state.PeerCertificates {
		report.ClientCertificates = append(report.ClientCertificates, cert.Subject.String())
	}

	hello := s.hellos.get(r.RemoteAddr)
	if hello == nil {
		// A resumed or coalesced HTTP/2 connection can outlive its entry, and a request that
		// arrived on a connection whose hello was already dropped has none to report.
		report.OfferedUnavailable = "no ClientHello recorded for this connection"
		return report
	}
	offered := &clientHelloInfo{
		ALPNProtocols:    hello.SupportedProtos,
		ServerName:       hello.ServerName,
		CipherSuites:     []string{},
		SupportedCurves:  []string{},
		SignatureSchemes: []string{},
		SupportedPoints:  []int{},
	}
	if offered.ALPNProtocols == nil {
		offered.ALPNProtocols = []string{}
	}
	for _, point := range hello.SupportedPoints {
		offered.SupportedPoints = append(offered.SupportedPoints, int(point))
	}
	for _, version := range hello.SupportedVersions {
		offered.SupportedVersions = append(offered.SupportedVersions, versionName(version))
	}
	for _, suite := range hello.CipherSuites {
		offered.CipherSuites = append(offered.CipherSuites, tls.CipherSuiteName(suite))
	}
	for _, curve := range hello.SupportedCurves {
		offered.SupportedCurves = append(offered.SupportedCurves, curveName(curve))
	}
	for _, scheme := range hello.SignatureSchemes {
		offered.SignatureSchemes = append(offered.SignatureSchemes, signatureSchemeName(scheme))
	}
	report.Offered = offered
	return report
}

func versionName(version uint16) string {
	switch version {
	case tls.VersionTLS10:
		return "TLSv1.0"
	case tls.VersionTLS11:
		return "TLSv1.1"
	case tls.VersionTLS12:
		return "TLSv1.2"
	case tls.VersionTLS13:
		return "TLSv1.3"
	default:
		// GREASE values land here, and reporting them as-is is the honest answer.
		return fmt.Sprintf("0x%04x", version)
	}
}

func curveName(curve tls.CurveID) string {
	if name := curve.String(); !strings.HasPrefix(name, "CurveID(") {
		return name
	}
	return fmt.Sprintf("0x%04x", uint16(curve))
}

func signatureSchemeName(scheme tls.SignatureScheme) string {
	if name := scheme.String(); !strings.HasPrefix(name, "SignatureScheme(") {
		return name
	}
	return fmt.Sprintf("0x%04x", uint16(scheme))
}
