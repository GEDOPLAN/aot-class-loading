# AOT Benchmark: JEP 483 + JEP 514

Demonstriert den Effekt von **JEP 483** (Ahead-of-Time Class Loading & Linking)
und **JEP 514** (AOT Command-Line Ergonomics) für zwei Frameworks im Vergleich.

## Voraussetzungen

- JDK 25+
- Maven 3.9.12+

## Projektstruktur

```
aot-benchmark/
├── spring-app/          Spring Boot 4.0.3
│   ├── pom.xml
│   └── src/main/java/de/gedoplan/showcase/aot/
│       └── SpringBenchmarkApplication.java
├── quarkus-app/         Quarkus 3.32.1 (JVM-Mode)
│   ├── pom.xml
│   └── src/main/java/de/gedoplan/showcase/aot/
│       └── QuarkusBenchmarkResource.java
├── benchmark.ps1        Benchmark-Script (Windows Powershell)
├── benchmark.sh         Benchmark-Script (Linux, macOS)
└── benchmark.java       Benchmark-Script (alle Plattformen)
```

## Endpoints

| Framework   | Endpoint        | Beschreibung                  |
|-------------|-----------------|-------------------------------|
| Spring Boot | `/hello`        | Einfacher String + Counter    |
| Spring Boot | `/load`         | CPU-Simulation (1M Math-Ops)  |
| Quarkus     | `/api/hello`    | Einfacher String + Counter    |
| Quarkus     | `/api/load`     | CPU-Simulation (1M Math-Ops)  |

## Schritt 1: Bauen

```
mvn package
```

## Schritt 2: Benchmark ausführen

```
 Windows: benchmarks.ps1
 
 Linux, macOS: benchmark.sh

 alle Plattformen:
 java benchmark.java
```
 

Das Script:
1. Erstellt AOT-Caches mit **JEP 514** (`-XX:AOTCacheOutput`) – Ein-Schritt-Workflow
2. Startet jede App-Variante und misst die **Time-to-First-Request** (TTFR)
3. Wiederholt jede Messung 10× und berechnet den Median
4. Gibt eine Vergleichstabelle aus

## AOT-Workflow (JEP 514 vs. JEP 483)

**Cache erstellen** (JEP 514 – einmaliger Schritt):
```powershell
java -XX:AOTCacheOutput=app.aot -jar app.jar
# Training-Run + Cache-Erstellung in einem einzigen Aufruf
```

**Mit Cache starten** (JEP 483):
```powershell
java -XX:AOTCache=app.aot -jar app.jar
```

Zum Vergleich der ältere JDK-24-Zwei-Schritt-Workflow (JEP 483):
```powershell
# Schritt 1: Training-Run
java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -jar app.jar
# Schritt 2: Cache erstellen
java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -jar app.jar
```

## Wichtige Hinweise

- **Quarkus läuft im JVM-Mode** (kein Native Image) – damit ist der JEP-483-Effekt
  direkt vergleichbar mit Spring Boot, da beide denselben HotSpot-JVM-Mechanismus nutzen.
- Der Cache ist **plattform- und JDK-versionsspezifisch** – er muss neu erstellt werden,
  wenn JAR, JDK-Version oder Classpath sich ändern.
- ZGC wird von JEP 483 noch nicht unterstützt (G1GC ist Standard und funktioniert).
