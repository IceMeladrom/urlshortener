# stage 1: build with maven
FROM maven:3.9.4-eclipse-temurin-21 as build
WORKDIR /workspace

# Копируем только pom сначала для использования кэша слоёв
COPY pom.xml .
# Если есть .mvn, settings, wrapper - можно копировать
# COPY .mvn .mvn
# COPY mvnw .
# Делаем mvn dependency:go-offline при необходимости (опционально)
RUN mvn -B -DskipTests dependency:go-offline

# Копируем исходники и собираем
COPY src ./src
RUN mvn -B -DskipTests clean package

# stage 2: runtime image
FROM eclipse-temurin:21-jdk
WORKDIR /app
# Копируем jar из build stage
COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]