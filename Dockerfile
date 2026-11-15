ARG SOURCE_DATE_EPOCH=1794528000
ARG GO_DEPS_IMAGE=golang:1.26.11-alpine@sha256:ae52b5bc6ed045a9ed26ab03dc19327a802a9cc9aa8b27ed57f5c6b60579b0bb
FROM ${GO_DEPS_IMAGE} AS go-deps
WORKDIR /build
ARG GOPROXY="https://proxy.golang.org|https://goproxy.io"
ENV GOMAXPROCS=2 GOTOOLCHAIN=local
COPY p2p-helper/go.mod p2p-helper/go.sum ./
RUN go mod download

FROM go-deps AS go-build
COPY p2p-helper/ ./
RUN CGO_ENABLED=0 go build -mod=readonly -buildvcs=false -trimpath -o /out/torchnode-p2p-helper . && \
    CGO_ENABLED=0 go build -mod=readonly -buildvcs=false -trimpath -o /out/torchnode-discovery-helper ./cmd/discovery

FROM maven:3.9.16-eclipse-temurin-21-alpine@sha256:308cba8b638ed7e4658cea3f8399066219466211c805f6d5728c3c9c7614661b AS java-deps
WORKDIR /build
ENV MAVEN_OPTS="-Xmx768m"
COPY pom.xml ./
COPY .mvn/ ./.mvn/
# Optional verified local seed; empty directory uses normal Maven Central resolution.
COPY build-cache/maven/ /root/.m2/repository/
COPY tools/precompile-jsp.xml tools/jsp-mappings.xsl tools/precompiled-views.properties ./tools/
# Warm the actual package lifecycle; avoid unrelated dependency-plugin realms.
RUN mvn -B -DskipTests -Djsp.precompile.skip=true -Dmaven.wagon.httpconnectionManager.ttlSeconds=30 -Dmaven.wagon.rto=30000 -Dmaven.wagon.http.retryHandler.count=0 package && rm -rf target

FROM java-deps AS java-build
COPY src/ ./src/
# Recompile without network access; dependency resolution stays a separate input.
RUN --network=none mvn -o -B -DskipTests package
# Pin SQLite's JNI library in the immutable image; writable temporary storage can stay noexec.
RUN mkdir -p /out/sqlite && cd /out/sqlite && \
    jar xf /build/target/torchnode-1.0.0-jar-with-dependencies.jar org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so && \
    chmod 644 org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so

# Pinned, supported upstream distribution; assemble a headless runtime offline.
FROM eclipse-temurin:21-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 AS runtime-base
ARG SOURCE_DATE_EPOCH
# Assemble only complete runtime package file sets, preserving their exact APK records.
# Build-only shells, downloaders, zlib and package-manager binaries are not distributed.
RUN --network=none mkdir -p /minimal/lib/apk/db /minimal/app/lib /minimal/data /minimal/tmp /minimal/etc /minimal/opt/java && \
    awk 'BEGIN { RS=""; ORS="\n\n" } /\nP:(musl|tzdata|ca-certificates-bundle|alpine-release)\n/ { print }' /lib/apk/db/installed > /minimal/lib/apk/db/installed && \
    awk '/^P:/ { p=($0 ~ /^P:(musl|tzdata|ca-certificates-bundle|alpine-release)$/) } p && /^F:/ { d=substr($0,3) } p && /^R:/ { print d "/" substr($0,3) }' /lib/apk/db/installed > /tmp/runtime-files && \
    tar -cf - -T /tmp/runtime-files | tar -xf - -C /minimal && \
    cp -a /opt/java/openjdk /minimal/opt/java/openjdk && \
    printf 'root:x:0:0:root:/root:/sbin/nologin\ntorchnode:x:10001:10001:TorchNode:/app:/sbin/nologin\n' > /minimal/etc/passwd && \
    printf 'root:x:0:\ntorchnode:x:10001:\n' > /minimal/etc/group && \
    chmod 1777 /minimal/tmp && chown 10001:10001 /minimal/data && \
    test -s /minimal/lib/apk/db/installed && test -e /minimal/lib/ld-musl-x86_64.so.1 && \
    find /minimal -exec touch -h -d "@${SOURCE_DATE_EPOCH}" '{}' +
FROM scratch AS runtime
ARG SOURCE_DATE_EPOCH
COPY --link --from=runtime-base /minimal/ /
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8
LABEL org.opencontainers.image.source="https://github.com/ParsaEfte/torchnode" \
      org.opencontainers.image.title="TorchNode Observatory" \
      org.opencontainers.image.documentation="https://github.com/ParsaEfte/torchnode/releases/tag/v1.0.0" \
      org.opencontainers.image.description="Ethereum network evidence observatory" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="MIT AND (GPL-2.0-only WITH Classpath-exception-2.0) AND GPL-2.0-only AND GPL-3.0-only AND GPL-3.0-or-later AND LGPL-3.0-or-later AND LGPL-2.1-only AND EPL-2.0 AND MPL-2.0"
COPY --from=java-build --chown=torchnode:torchnode /build/target/torchnode-1.0.0-jar-with-dependencies.jar /app/torchnode.jar
COPY --from=java-build --chown=torchnode:torchnode /out/sqlite/org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so /app/lib/libsqlitejdbc.so
COPY --chown=torchnode:torchnode LICENSE /app/LICENSE
COPY --chown=torchnode:torchnode DISTRIBUTION-LICENSE.md /app/DISTRIBUTION-LICENSE.md
COPY --chown=torchnode:torchnode SOURCE-DELIVERY.md /app/SOURCE-DELIVERY.md
COPY --chown=torchnode:torchnode THIRD_PARTY_NOTICES.md /app/THIRD_PARTY_NOTICES.md
COPY --chown=torchnode:torchnode --chmod=u=rwX,go=rX third-party/ /app/third-party/
COPY --from=go-build --chown=torchnode:torchnode --chmod=755 /out/torchnode-p2p-helper /app/torchnode-p2p-helper
COPY --from=go-build --chown=torchnode:torchnode --chmod=755 /out/torchnode-discovery-helper /app/torchnode-discovery-helper
USER 10001:10001
WORKDIR /app
ENV TORCHNODE_DB=/data/torchnode.db \
    JAVA_TOOL_OPTIONS="-Dorg.sqlite.lib.path=/app/lib" \
    TORCHNODE_BIND=0.0.0.0 \
    TORCHNODE_PORT=8080 \
    TORCHNODE_P2P_HELPER=/app/torchnode-p2p-helper \
    TORCHNODE_DISCV5_HELPER=/app/torchnode-discovery-helper
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=6s --start-period=40s --retries=3 \
    CMD ["java", "-cp", "/app/torchnode.jar", "io.github.gavinruff007.torchnode.HealthCheck"]
ENTRYPOINT ["java", "-jar", "/app/torchnode.jar"]
