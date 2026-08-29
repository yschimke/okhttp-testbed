FROM golang:1.27-alpine AS build

WORKDIR /src
COPY main.go .
COPY ct-fixture-ca.pem ct-fixture-ca-key.pem ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /fixture main.go

FROM alpine:3.24
COPY --from=build /fixture /fixture
ENTRYPOINT ["/fixture"]
