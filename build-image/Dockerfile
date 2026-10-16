ARG BUSYBOX=busybox:1.37.0-musl@sha256:5cec3fc171c87218698e85a52af7087de727372aae264a787b8112901a5b0092
FROM $BUSYBOX AS busybox

FROM moby/buildkit:v0.34.0-rootless@sha256:65eca3c01cd1180c8f64565c4e3c5983af5145818b0e6fa863385d099eb73855

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
