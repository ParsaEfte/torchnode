FROM golang:1.25.14-alpine AS go-build
WORKDIR /build
ARG GOPROXY="https://proxy.golang.org|https://goproxy.io"
COPY p2p-helper/go.mod p2p-helper/go.sum ./
RUN go mod download
COPY p2p-helper/ ./
RUN CGO_ENABLED=0 go build -trimpath -o /out/torchnode-p2p-helper . && \
    CGO_ENABLED=0 go build -trimpath -o /out/torchnode-discovery-helper ./cmd/discovery

FROM maven:3.9.16-eclipse-temurin-21-alpine AS java-build
WORKDIR /build
COPY pom.xml ./
COPY src/ ./src/
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-noble
LABEL org.opencontainers.image.source="https://github.com/ParsaEfte/torchnode" \
      org.opencontainers.image.title="TorchNode Observatory" \
      org.opencontainers.image.description="Ethereum network evidence observatory" \
      org.opencontainers.image.version="1.0-SNAPSHOT" \
      org.opencontainers.image.licenses="MIT"
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 torchnode && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home torchnode && \
    mkdir -p /app /data && chown torchnode:torchnode /data
COPY --from=java-build --chown=torchnode:torchnode /build/target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar /app/torchnode.jar
COPY --chown=torchnode:torchnode LICENSE /app/LICENSE
COPY --from=go-build --chown=torchnode:torchnode --chmod=755 /out/torchnode-p2p-helper /app/torchnode-p2p-helper
COPY --from=go-build --chown=torchnode:torchnode --chmod=755 /out/torchnode-discovery-helper /app/torchnode-discovery-helper
USER 10001:10001
WORKDIR /app
ENV TORCHNODE_DB=/data/torchnode.db \
    TORCHNODE_BIND=0.0.0.0 \
    TORCHNODE_PORT=8080 \
    TORCHNODE_P2P_HELPER=/app/torchnode-p2p-helper \
    TORCHNODE_DISCV5_HELPER=/app/torchnode-discovery-helper
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 2 http://127.0.0.1:8080/api/v1 >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/torchnode.jar"]
