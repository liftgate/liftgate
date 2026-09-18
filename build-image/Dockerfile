FROM moby/buildkit:rootless

ARG RAILPACK_VERSION=0.39.0
ARG TARGETARCH

USER root
RUN apk add --no-cache git curl \
    && arch=$([ "$TARGETARCH" = arm64 ] && echo arm64 || echo x86_64) \
    && curl -fsSL "https://github.com/railwayapp/railpack/releases/download/v${RAILPACK_VERSION}/railpack-v${RAILPACK_VERSION}-${arch}-unknown-linux-musl.tar.gz" \
        | tar -xz -C /usr/local/bin railpack \
    && railpack --version
COPY --chmod=755 build.sh /usr/local/bin/build.sh

USER 1000:1000
ENV BUILDKITD_FLAGS=--oci-worker-no-process-sandbox
ENTRYPOINT ["/usr/local/bin/build.sh"]
