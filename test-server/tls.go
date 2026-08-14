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

	// Chains that must be rejected, one per listener. Empty when a certificate was supplied:
	// minting them needs the fixture CA's signing key, which a deployment holding a real
	// certificate does not have.
	badChains []badChain
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
	badChains, err := newBadChains(caCert, caKey, hosts)
	if err != nil {
		return nil, err
	}
	return &certificates{
		leaf:      leaf,
		caPEM:     caPEM,
		selfMade:  true,
		hosts:     hosts,
		badChains: badChains,
	}, nil
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

func newKey() (*ecdsa.PrivateKey, error) {
	return ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
}

// How a leaf differs from the ordinary one. The zero value is the good certificate: valid from
// an hour ago for a year, covering `hosts`. The bad chains in badchains.go are exactly the
// departures from that, one field at a time.
type leafOptions struct {
	hosts     []string
	notBefore time.Time
	notAfter  time.Time
}

func newLeaf(caCert *x509.Certificate, caKey *ecdsa.PrivateKey, hosts []string) (tls.Certificate, error) {
	return newLeafWith(caCert, caKey, leafOptions{hosts: hosts})
}

// A leaf signed by the given issuer, or self-signed when the issuer is nil.
//
// The returned certificate carries the leaf only, never the issuer: the good leaf is signed
// directly by the root, so there is nothing to send, and `incomplete-chain` depends on the
// intermediate being left out. Anything wanting a complete path has to add it deliberately.
func newLeafWith(caCert *x509.Certificate, caKey *ecdsa.PrivateKey, opts leafOptions) (tls.Certificate, error) {
	key, err := newKey()
	if err != nil {
		return tls.Certificate{}, err
	}

	notBefore, notAfter := opts.notBefore, opts.notAfter
	if notBefore.IsZero() {
		notBefore = time.Now().Add(-time.Hour)
	}
	if notAfter.IsZero() {
		notAfter = time.Now().AddDate(1, 0, 0)
	}

	template := &x509.Certificate{
		SerialNumber: serialNumber(),
		Subject:      pkix.Name{CommonName: opts.hosts[0]},
		NotBefore:    notBefore,
		NotAfter:     notAfter,
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	for _, host := range opts.hosts {
		if ip := net.ParseIP(host); ip != nil {
			template.IPAddresses = append(template.IPAddresses, ip)
		} else {
			template.DNSNames = append(template.DNSNames, host)
		}
	}

	// A nil issuer means the leaf signs itself: the self-signed chain, where the certificate is
	// its own issuer and so leads to no anchor at all.
	issuer, issuerKey := caCert, caKey
	if issuer == nil {
		issuer, issuerKey = template, key
	}

	der, err := x509.CreateCertificate(rand.Reader, template, issuer, &key.PublicKey, issuerKey)
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
	return s.tlsServerWith(addr, versions, http2, s.certs.leaf)
}

// The same server presenting a certificate of the caller's choosing, which is how the
// bad-chain listeners differ from the good one. Everything else — the ClientHello capture, the
// version pinning, the client-certificate policy — is deliberately identical, so that the
// certificate is the only variable.
func (s *server) tlsServerWith(addr string, versions tlsVersions, http2 bool, cert tls.Certificate) *http.Server {
	config := &tls.Config{
		Certificates: []tls.Certificate{cert},
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

// The offer.
//
// The extension IDs arrive in the order the client sent them, which is most of what a JA3 or
// JA4 fingerprint is computed from. The rest — the raw extension *contents* — crypto/tls parses
// and does not hand back, so this still answers what OkHttp offered rather than exactly how a
// CDN would fingerprint it.
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
	Extensions       []string `json:"extensions"`
	// True when extension 0xfe0d was offered at all, which is the question GREASE asks: a client
	// with no ECH configuration is meant to send one anyway, so that a client using ECH and a
	// client not using it look the same on the wire. Whether *this* one was real or GREASE is
	// not visible from here — indistinguishability is the design — so the name is deliberately
	// "offered" rather than "used".
	EncryptedClientHelloOffered bool `json:"encryptedClientHelloOffered"`
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
		Extensions:       []string{},
	}
	for _, extension := range hello.Extensions {
		offered.Extensions = append(offered.Extensions, extensionName(extension))
		if extension == extensionEncryptedClientHello {
			offered.EncryptedClientHelloOffered = true
		}
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

// RFC 9849 §5, the extension a GREASE-ing client sends even with nothing to put in it.
const extensionEncryptedClientHello = 0xfe0d

// The extensions worth naming. The list is the ones a modern client actually sends, not the
// whole IANA registry: an unnamed extension is reported as its hex code, which is readable
// enough to look up and honest about not being recognised here.
var extensionNames = map[uint16]string{
	0:                             "server_name",
	1:                             "max_fragment_length",
	5:                             "status_request",
	10:                            "supported_groups",
	11:                            "ec_point_formats",
	13:                            "signature_algorithms",
	14:                            "use_srtp",
	16:                            "application_layer_protocol_negotiation",
	17:                            "status_request_v2",
	18:                            "signed_certificate_timestamp",
	21:                            "padding",
	22:                            "encrypt_then_mac",
	23:                            "extended_master_secret",
	27:                            "compress_certificate",
	35:                            "session_ticket",
	41:                            "pre_shared_key",
	42:                            "early_data",
	43:                            "supported_versions",
	44:                            "cookie",
	45:                            "psk_key_exchange_modes",
	47:                            "certificate_authorities",
	49:                            "post_handshake_auth",
	50:                            "signature_algorithms_cert",
	51:                            "key_share",
	extensionEncryptedClientHello: "encrypted_client_hello",
	0xff01:                        "renegotiation_info",
	0x4469:                        "application_settings",
}

func extensionName(extension uint16) string {
	if name, ok := extensionNames[extension]; ok {
		return name
	}
	// GREASE reserves the sixteen values whose halves are equal and end in 0xa (RFC 8701). A
	// client sends them to keep servers tolerant of unknown values, so naming them as such
	// stops sixteen mystery hex codes from reading like sixteen unrecognised extensions.
	if extension&0x0f0f == 0x0a0a && extension>>8 == extension&0xff {
		return fmt.Sprintf("GREASE(0x%04x)", extension)
	}
	return fmt.Sprintf("0x%04x", extension)
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
