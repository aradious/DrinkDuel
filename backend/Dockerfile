FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
COPY frontend/src/assets/drinkduel/avatars/catalog.json /workspace/frontend/src/assets/drinkduel/avatars/catalog.json
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline

COPY backend/src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S drinkduel && adduser -S -G drinkduel drinkduel
WORKDIR /app
COPY --from=build --chown=drinkduel:drinkduel /workspace/backend/target/drinkduel-0.0.1-SNAPSHOT.jar app.jar

USER drinkduel
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
