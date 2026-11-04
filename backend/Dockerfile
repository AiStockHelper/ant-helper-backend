# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test \
    && rm -f build/libs/*-plain.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar

# 설정(application-*.yml)은 이미지에 넣지 않고 /app/config 에 마운트해서 쓴다.
ENV LOG_DIR=/app/log TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
CMD ["--spring.config.additional-location=file:/app/config/", "--spring.profiles.group.prod-env=common,prod", "--spring.profiles.active=prod-env"]
