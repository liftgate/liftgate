FROM public.ecr.aws/docker/library/busybox:1.37.0-musl@sha256:5cec3fc171c87218698e85a52af7087de727372aae264a787b8112901a5b0092 AS build
COPY . /srv
RUN mkdir -p /srv/data && echo x > /srv/data/file

FROM scratch
COPY --from=build /bin/busybox /busybox
COPY --from=build /etc/passwd /etc/passwd
COPY --from=build --chown=1001:1001 /srv /srv
RUN ["/busybox","ln","-s","/etc/passwd","/srv/passwd-link"]
WORKDIR /srv
USER 1001
