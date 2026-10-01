FROM moby/buildkit:v0.33.0-rootless@sha256:80b15f0735e87bab7bf59ec4d695dfb4a7cfb25521cf56dc75d6f256285b63ef

ENV RAILPACK_VERSION=0.39.0
ARG TARGETARCH

USER root
RUN apk add --no-cache git curl bash jq \
    && arch=$([ "$TARGETARCH" = arm64 ] && echo arm64 || echo x86_64) \
    && curl -fsSL "https://github.com/railwayapp/railpack/releases/download/v${RAILPACK_VERSION}/railpack-v${RAILPACK_VERSION}-${arch}-unknown-linux-musl.tar.gz" \
        | tar -xz -C /usr/local/bin railpack \
    && railpack --version
COPY --chmod=755 build.sh clone.sh /usr/local/bin/

USER 1000:1000
ENV BUILDKITD_FLAGS=--oci-worker-no-process-sandbox
ENTRYPOINT ["/usr/local/bin/build.sh"]
