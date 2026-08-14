package main

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"time"
)

// Chains that must be rejected, one per listener.
//
// This is the local half of the badssl matrix. badssl.com is best-effort by its own
// description, and its `expired` certificate is signed a day at a time — for the first
// twenty-four hours after it is generated it is not expired at all, so a test asserting
// rejection passes for the wrong reason. Minting the chains here instead makes the validity
// window exact and the failure honest.
//
// Each listener differs from the good one in exactly one way. That is the whole design: a
// client refusing `expired` and accepting `https` has told us something specific, where a
// certificate wrong in three ways at once would only tell us it was refused.
//
// What is deliberately absent: revocation. A revoked certificate needs an OCSP responder or a
// CRL distribution point that a client will actually fetch, which is a different piece of
// machinery — see issue #16 rather than bolting a fake onto this.
type badChain struct {
	// The name in /info, in the listener log, and in the environment variable.
	name string

	// One line for /info, saying what a client is expected to object to.
	why string

	// The certificate the listener presents, chain order as served.
	certificate tls.Certificate

	// For incomplete-chain, the PEM the server deliberately does not send. A test that
	// supplies it and then succeeds proves the chain is short rather than broken.
	withheld []byte
}

// The bad chains, minted from the fixture CA at startup alongside the good leaf.
//
// `hosts` is what the good leaf covers, so that every chain here is served on the same names
// and only the certificate differs.
func newBadChains(caCert *x509.Certificate, caKey *ecdsa.PrivateKey, hosts []string) ([]badChain, error) {
	now := time.Now()

	// A second CA that is never published at /ca.pem. A client that trusts the fixture CA has
	// no path to this one, which is the point.
	untrustedCA, untrustedKey, _, err := newCA()
	if err != nil {
		return nil, err
	}

	// An intermediate, so that `incomplete-chain` has something to omit. The good leaf is
	// signed directly by the CA, so without this there is no chain long enough to be missing
	// a link.
	intermediate, intermediateKey, err := newIntermediate(caCert, caKey)
	if err != nil {
		return nil, err
	}

	expired, err := newLeafWith(caCert, caKey, leafOptions{
		hosts:     hosts,
		notBefore: now.AddDate(0, 0, -30),
		notAfter:  now.AddDate(0, 0, -1),
	})
	if err != nil {
		return nil, err
	}

	// Valid, correctly signed, and for somebody else. Note it covers no loopback name at all:
	// a client reaching this listener on localhost must find nothing it can match.
	wrongHost, err := newLeafWith(caCert, caKey, leafOptions{
		hosts: []string{"wrong.host.invalid"},
	})
	if err != nil {
		return nil, err
	}

	selfSigned, err := newLeafWith(nil, nil, leafOptions{hosts: hosts})
	if err != nil {
		return nil, err
	}

	untrustedRoot, err := newLeafWith(untrustedCA, untrustedKey, leafOptions{hosts: hosts})
	if err != nil {
		return nil, err
	}

	// Signed by the intermediate and served without it. A client that happens to hold the
	// intermediate already would build the path anyway — which is exactly what makes this a
	// real-world failure rather than a synthetic one.
	incomplete, err := newLeafWith(intermediate, intermediateKey, leafOptions{hosts: hosts})
	if err != nil {
		return nil, err
	}

	return []badChain{
		{
			name:        "expired",
			why:         "the leaf expired yesterday; everything else about it is correct",
			certificate: expired,
		},
		{
			name:        "wrong-host",
			why:         "a valid chain for wrong.host.invalid, served on a name it does not cover",
			certificate: wrongHost,
		},
		{
			name:        "self-signed",
			why:         "the leaf is its own issuer, so there is no path to any anchor",
			certificate: selfSigned,
		},
		{
			name:        "untrusted-root",
			why:         "signed by a second CA this server mints and never publishes at /ca.pem",
			certificate: untrustedRoot,
		},
		{
			name:        "incomplete-chain",
			why:         "signed by an intermediate the server holds back, so the path cannot be built",
			certificate: incomplete,
			withheld:    pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: intermediate.Raw}),
		},
	}, nil
}

// The listener addresses, following the same shape as perVersionListeners: an environment
// variable per listener, and empty disables it.
func badChainAddr(name string) string {
	return env(badChainEnv(name), badChainDefaultAddr[name])
}

func badChainEnv(name string) string {
	switch name {
	case "expired":
		return "BADCHAIN_EXPIRED_ADDR"
	case "wrong-host":
		return "BADCHAIN_WRONG_HOST_ADDR"
	case "self-signed":
		return "BADCHAIN_SELF_SIGNED_ADDR"
	case "untrusted-root":
		return "BADCHAIN_UNTRUSTED_ROOT_ADDR"
	case "incomplete-chain":
		return "BADCHAIN_INCOMPLETE_CHAIN_ADDR"
	}
	return ""
}

var badChainDefaultAddr = map[string]string{
	"expired":          ":8420",
	"wrong-host":       ":8421",
	"self-signed":      ":8422",
	"untrusted-root":   ":8423",
	"incomplete-chain": ":8424",
}

// An intermediate CA, signed by the fixture root.
func newIntermediate(caCert *x509.Certificate, caKey *ecdsa.PrivateKey) (*x509.Certificate, *ecdsa.PrivateKey, error) {
	key, err := newKey()
	if err != nil {
		return nil, nil, err
	}
	template := &x509.Certificate{
		SerialNumber:          serialNumber(),
		Subject:               pkix.Name{CommonName: "okhttp-testbed test-server intermediate"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(1, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLenZero:        true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, caCert, &key.PublicKey, caKey)
	if err != nil {
		return nil, nil, err
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, nil, err
	}
	return cert, key, nil
}
