package main

import (
	"bytes"
	"testing"
)

func TestStripAttrleafPort(t *testing.T) {
	cases := map[string]string{
		"green.secret.test":               "green.secret.test",
		"_8443._https.green.secret.test":  "green.secret.test",
		"_443._https.green.secret.test":   "green.secret.test",
		"_https.green.secret.test":        "_https.green.secret.test",
		"_x._https.green.secret.test":     "_x._https.green.secret.test",
		"_8443._http.green.secret.test":   "_8443._http.green.secret.test",
		"_99999._https.green.secret.test": "_99999._https.green.secret.test",
		"alias.svcb.test":                 "alias.svcb.test",
	}
	for in, want := range cases {
		if got := stripAttrleafPort(in); got != want {
			t.Errorf("stripAttrleafPort(%q) = %q, want %q", in, got, want)
		}
	}
}

// OkHttp asks for `_8443._https.green.secret.test` because the origin is on 8443. That query
// must get the same ECH config list as the bare name, not an error.
func TestHttpsRecordForAttrleafQuery(t *testing.T) {
	echConfigList := []byte{0x00, 0x04, 0xfe, 0x0d, 0x00, 0x00}
	configs := map[string][]byte{greenName: echConfigList}

	for _, name := range []string{greenName, "_8443._https." + greenName} {
		response, err := dnsResponse(httpsQuery(name), configs, 8443)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if answers := int(response[6])<<8 | int(response[7]); answers != 1 {
			t.Fatalf("%s: answer count = %d, want 1", name, answers)
		}
		if !bytes.Contains(response, echConfigList) {
			t.Fatalf("%s: response does not carry the ECH config list", name)
		}
	}
}

func httpsQuery(name string) []byte {
	query := []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	query = appendDnsName(query, name)
	query = appendUint16(query, 65) // HTTPS
	return appendUint16(query, 1)   // IN
}
