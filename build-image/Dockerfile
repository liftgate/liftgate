ARG BUSYBOX=busybox:1.37.0-musl@sha256:5cec3fc171c87218698e85a52af7087de727372aae264a787b8112901a5b0092
FROM $BUSYBOX AS busybox

FROM moby/buildkit:v0.34.2-rootless@sha256:c8252268760e1baba80780d63d738ef9f260fb7c7c0973c78494cb9ddd99eda1

ENV RAILPACK_VERSION=0.39.0
ARG BUSYBOX
ENV LIFTGATE_BUSYBOX=$BUSYBOX
ARG TARGETARCH

USER root
RUN apk add --no-cache git curl bash jq \
    && arch=$([ "$TARGETARCH" = arm64 ] && echo arm64 || echo x86_64) \
    && curl -fsSL "https://github.com/railwayapp/railpack/releases/download/v${RAILPACK_VERSION}/railpack-v${RAILPACK_VERSION}-${arch}-unknown-linux-musl.tar.gz" \
        | tar -xz -C /usr/local/bin railpack \
    && railpack --version
COPY --from=busybox /bin/busybox /opt/busybox/bin/
COPY --chmod=755 build.sh clone.sh /usr/local/bin/

USER 1000:1000
ENV BUILDKITD_FLAGS=--oci-worker-no-process-sandbox
ENTRYPOINT ["/usr/local/bin/build.sh"]
