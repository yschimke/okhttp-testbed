FROM golang:1.24-alpine AS build

WORKDIR /src
# The trailing slash is load-bearing. BuildKit accepts a bare `.` for a multi-file COPY, and
# `docker build` uses BuildKit — but Testcontainers builds through the Docker API's classic
# builder, which rejects it: "When using COPY with more than one source file, the destination
# must be a directory and end with a /". BadChainTest builds this image that way.
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /test-server .

FROM alpine:3.24

# curl earns its place: the image's own healthcheck uses it, and so does anyone debugging a
# deployment from inside the container.
RUN apk add --no-cache curl ca-certificates

COPY --from=build /test-server /test-server

# Plain HTTP, the raw echo listener, TLS, a port per TLS version, and a port per rejectable
# chain. Every one of these is an env var away from being something else — see README.md.
EXPOSE 8080 8081 8443 8410 8411 8412 8413 8420 8421 8422 8423 8424

HEALTHCHECK --interval=10s --timeout=3s --start-period=2s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/health || exit 1

USER nobody
ENTRYPOINT ["/test-server"]
