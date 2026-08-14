package main

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Responses that are wrong on purpose.
//
// A client's behaviour against a well-behaved server is the easy half. What a connection
// pool does with a reset mid-body, a truncated Content-Length, or invalid chunk framing is
// the half that produces the bug reports — and none of it can be provoked through
// http.ResponseWriter, which exists precisely to stop a handler emitting nonsense. So these
// hijack the connection and write the bytes directly.
//
// HTTP/2 has no hijack: the connection is shared, and corrupting it would corrupt every
// other stream on it. These endpoints answer 501 on h2 rather than pretending, so a suite
// runs them over the plain port or over TLS with http/1.1 negotiated.
func (s *server) registerHostile(mux *http.ServeMux) {
	mux.HandleFunc("GET /hostile", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		for _, doc := range hostileIndex {
			_, _ = fmt.Fprintf(w, "  %-38s %s\n", doc.path, doc.description)
		}
	})

	mux.HandleFunc("GET /hostile/no-response", s.hijacked(func(conn net.Conn, _ *http.Request) {
		// Accepted, then closed with nothing written: the empty response.
	}))

	mux.HandleFunc("GET /hostile/reset", s.hijacked(func(conn net.Conn, r *http.Request) {
		after := queryInt(r.URL.Query(), "after", 8, 0, 1<<16)
		writeString(conn, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1024\r\n\r\n")
		writeString(conn, strings.Repeat("x", after))
		// SetLinger(0) turns the close into an RST rather than a FIN, which is the difference
		// between "the peer finished early" and "the peer hung up on us".
		if tcp, ok := conn.(*net.TCPConn); ok {
			_ = tcp.SetLinger(0)
		}
	}))

	mux.HandleFunc("GET /hostile/truncated-body", s.hijacked(func(conn net.Conn, r *http.Request) {
		promised := queryInt(r.URL.Query(), "promised", 1024, 1, 1<<20)
		sent := queryInt(r.URL.Query(), "sent", 16, 0, 1<<20)
		writeString(conn, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "+strconv.Itoa(promised)+"\r\n\r\n")
		writeString(conn, strings.Repeat("x", min(sent, promised)))
	}))

	mux.HandleFunc("GET /hostile/truncated-chunks", s.hijacked(func(conn net.Conn, _ *http.Request) {
		// Chunks that stop before the terminating zero-length chunk.
		writeString(conn, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
		writeString(conn, "8\r\nxxxxxxxx\r\n")
	}))

	mux.HandleFunc("GET /hostile/invalid-chunk-size", s.hijacked(func(conn net.Conn, _ *http.Request) {
		writeString(conn, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
		writeString(conn, "zz\r\nxxxxxxxx\r\n0\r\n\r\n")
	}))

	mux.HandleFunc("GET /hostile/invalid-status-line", s.hijacked(func(conn net.Conn, _ *http.Request) {
		writeString(conn, "HTTP/1.1 2000 Not A Status\r\nContent-Length: 0\r\n\r\n")
	}))

	mux.HandleFunc("GET /hostile/duplicate-content-length", s.hijacked(func(conn net.Conn, _ *http.Request) {
		// Two Content-Lengths that disagree: a request-smuggling shape, which a client must
		// refuse rather than pick a winner from.
		writeString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nContent-Length: 8\r\n\r\nxxxx")
	}))

	mux.HandleFunc("GET /hostile/content-length-and-chunked", s.hijacked(func(conn net.Conn, _ *http.Request) {
		writeString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 8\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nxxxx\r\n0\r\n\r\n")
	}))

	mux.HandleFunc("GET /hostile/slow-headers", s.hijacked(func(conn net.Conn, r *http.Request) {
		// One header byte at a time, so a read timeout has something to fire on.
		pause := queryDuration(r.URL.Query(), "delay", 100*time.Millisecond)
		for _, b := range []byte("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok") {
			if _, err := conn.Write([]byte{b}); err != nil {
				return
			}
			time.Sleep(pause)
		}
	}))

	mux.HandleFunc("GET /hostile/huge-header", s.hijacked(func(conn net.Conn, r *http.Request) {
		size := queryInt(r.URL.Query(), "size", 64<<10, 1, 1<<20)
		writeString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-Testbed-Huge: "+strings.Repeat("x", size)+"\r\n\r\n")
	}))

	mux.HandleFunc("GET /hostile/informational-storm", s.hijacked(func(conn net.Conn, r *http.Request) {
		count := queryInt(r.URL.Query(), "count", 5, 1, 100)
		for range count {
			writeString(conn, "HTTP/1.1 100 Continue\r\n\r\n")
		}
		writeString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
	}))

	mux.HandleFunc("GET /hostile/half-close", s.hijacked(func(conn net.Conn, _ *http.Request) {
		// A complete response, then the write half shut: the client's pool has to notice the
		// connection is no longer reusable.
		writeString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
		if tcp, ok := conn.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
		time.Sleep(100 * time.Millisecond)
	}))
}

var hostileIndex = []endpointDoc{
	{"/hostile/no-response", "accepts the connection and closes it, writing nothing"},
	{"/hostile/reset?after=", "headers, part of a body, then RST"},
	{"/hostile/truncated-body?promised=&sent=", "fewer body bytes than Content-Length promised"},
	{"/hostile/truncated-chunks", "chunks with no terminating zero-length chunk"},
	{"/hostile/invalid-chunk-size", "a chunk size that is not hexadecimal"},
	{"/hostile/invalid-status-line", "a four-digit status code"},
	{"/hostile/duplicate-content-length", "two Content-Length headers that disagree"},
	{"/hostile/content-length-and-chunked", "both framings at once"},
	{"/hostile/slow-headers?delay=", "the response head, one byte at a time"},
	{"/hostile/huge-header?size=", "a response header far past any sane limit"},
	{"/hostile/informational-storm?count=", "a run of 100 Continue before the real response"},
	{"/hostile/half-close", "a complete response, then the write half shut"},
}

// hijacked takes the connection away from net/http so the handler can write whatever it
// likes, valid or not, and closes it afterwards.
func (s *server) hijacked(write func(conn net.Conn, r *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		hijacker, ok := w.(http.Hijacker)
		if !ok {
			http.Error(w,
				"this endpoint writes raw bytes and needs the connection to itself, which "+
					r.Proto+" does not allow; use the plain port or negotiate http/1.1",
				http.StatusNotImplemented)
			return
		}
		conn, buffered, err := hijacker.Hijack()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		defer conn.Close()
		// Anything net/http had already buffered for this response is dropped: the handler
		// writes the whole response itself, starting at the status line.
		_ = buffered.Writer.Flush()
		write(conn, r)
	}
}

func writeString(conn net.Conn, s string) {
	_, _ = io.WriteString(conn, s)
}

// serveRaw answers every connection with the request head it received, byte for byte.
//
// It exists because net/http cannot answer the question. Go canonicalises header names to
// Title-Case, merges what it likes into its own fields, and keeps no order — so /anything
// reports a normalised view. Header order and casing are half of how a CDN fingerprints a
// client, and this is where a test can see them.
func serveRaw(ln net.Listener) error {
	for {
		conn, err := ln.Accept()
		if err != nil {
			// The listener is closed on shutdown, and that is not a failure.
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				continue
			}
			return nil
		}
		go echoRequestHead(conn)
	}
}

func echoRequestHead(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))

	var head strings.Builder
	reader := bufio.NewReader(io.LimitReader(conn, 64<<10))
	for {
		line, err := reader.ReadString('\n')
		head.WriteString(line)
		if err != nil || line == "\r\n" || line == "\n" {
			break
		}
	}

	body := head.String()
	writeString(conn, "HTTP/1.1 200 OK\r\n")
	writeString(conn, "Content-Type: text/plain; charset=utf-8\r\n")
	writeString(conn, "Content-Length: "+strconv.Itoa(len(body))+"\r\n")
	writeString(conn, "Connection: close\r\n\r\n")
	writeString(conn, body)
}
