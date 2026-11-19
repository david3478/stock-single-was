# 빌드 단계: 테스트는 CI에서 실행하므로 여기서는 jar만 만든다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar \
    && cp build/libs/stock-single-was-*-SNAPSHOT.jar /app.jar

# 실행 단계: JRE만 포함한다. local 프로필을 켜지 않으므로 DB 접속 정보는 환경변수로만 받는다.
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
