FROM busybox:1.37.0@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e
ARG FOO
ARG API_TOKEN
RUN echo "$FOO" > /foo && echo "$API_TOKEN" > /api-token-arg
RUN --mount=type=secret,id=API_TOKEN sha256sum /run/secrets/API_TOKEN | cut -d ' ' -f 1 > /api-token.sha256
