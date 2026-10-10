# Demo server image. Build context: the root of the repository (see docker-compose.yml),
# because the demo uses the SDK built from this repository.
FROM eclipse-temurin:21-jdk AS build
# The Maven wrapper needs unzip: without it, it downloads the .tar.gz distribution, whose SHA-256 differs from
# the one pinned in java/.mvn/wrapper/maven-wrapper.properties (the .zip's), and refuses it.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY java/ java/
COPY testdata/ testdata/
COPY examples/spring-boot-demo/pom.xml examples/spring-boot-demo/pom.xml
COPY examples/spring-boot-demo/src/ examples/spring-boot-demo/src/
WORKDIR /src/java
RUN ./mvnw --batch-mode --no-transfer-progress install -DskipTests \
    && ./mvnw --batch-mode --no-transfer-progress -f ../examples/spring-boot-demo/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home demo
COPY --from=build /src/examples/spring-boot-demo/target/spring-boot-demo.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
