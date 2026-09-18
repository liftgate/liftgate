FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 liftgate && useradd --system --uid 10001 --gid liftgate --shell /usr/sbin/nologin liftgate
COPY --from=build /src/build/install/liftgate-control-plane /opt/liftgate
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/opt/liftgate/bin/liftgate-control-plane"]
