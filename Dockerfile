FROM gradle:8-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/server/build/install/server/ /app/
RUN mkdir -p /data
VOLUME ["/data"]
EXPOSE 8080
ENV DB_PATH=/data/soltradbot.db
CMD ["/app/bin/server"]
