# syntax=docker/dockerfile:1
# The build stage runs natively and cross-compiles, so building amd64 images on
# an arm64 machine (or vice versa) needs no slow emulation.
FROM --platform=$BUILDPLATFORM golang:1.27-bookworm AS build
ARG TARGETOS TARGETARCH
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags="-s -w" -o /out/pdfutils ./cmd/pdfutils

FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends ghostscript qpdf ca-certificates tini \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /nonexistent app
COPY --from=build /out/pdfutils /usr/local/bin/pdfutils
USER app
ENV TMPDIR=/tmp
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "--", "pdfutils"]
CMD ["server"]
