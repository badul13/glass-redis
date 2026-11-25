# 1) 대시보드 - React 빌드 결과(dist)만 다음 단계로
FROM node:22-alpine AS dashboard
WORKDIR /src/dashboard
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci
COPY dashboard/ ./
RUN npm run build

# 2) 서버 - 이 단계엔 npm 없음 → Gradle 은 대시보드 빌드 생략, 받아 온 dist 만 jar 에 담음
FROM eclipse-temurin:21-jdk AS server
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon --version
COPY src/ src/
COPY --from=dashboard /src/dashboard/dist/ dashboard/dist/
RUN ./gradlew --no-daemon installDist -x test

# 3) 실행 - JRE 와 설치본만
FROM eclipse-temurin:21-jre
COPY --from=server /src/build/install/glass-redis/ /app/
# dump.rdb·appendonlydir 위치 - 볼륨으로 붙이면 컨테이너를 지워도 유지
WORKDIR /data
VOLUME /data
EXPOSE 6380 8080
# 컨테이너 밖에서 접속 → 모든 주소에서 받기, 나머지 옵션은 docker run 뒤에 이어 붙임
ENTRYPOINT ["/app/bin/glass-redis", "--bind", "0.0.0.0", "--dashboard-bind", "0.0.0.0"]
