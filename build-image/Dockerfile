FROM moby/buildkit:rootless

USER root
RUN apk add --no-cache git curl \
    && curl -sSL https://railpack.com/install.sh | sh -s -- --bin-dir /usr/local/bin
COPY --chmod=755 build.sh /usr/local/bin/build.sh

USER 1000:1000
ENV BUILDKITD_FLAGS=--oci-worker-no-process-sandbox
ENTRYPOINT ["/usr/local/bin/build.sh"]
