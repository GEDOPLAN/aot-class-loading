/// AOT Benchmark – JEP 483 + JEP 514 Plattformübergreifendes Benchmark-Script (Windows, Linux, macOS)
///
/// Ausführung: java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 benchmark.java
///
/// Voraussetzungen:
///   - JDK 25+ (JEP 463: implizite Klassen, JEP 483: AOT Cache, JEP 514: AOTCacheOutput)

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.*;

// Konfiguration
static final String SPRING_JAR = "spring-app/target/spring-aot-benchmark.jar";
    static final String QUARKUS_JAR = "quarkus-app/target/quarkus-aot-benchmark-runner.jar";
    static final String SPRING_AOT = "spring-app/target/spring-aot-benchmark.aot";
    static final String SPRING_AOT_PROCESSED = "spring-app/target/spring-aot-benchmark-processed.aot";
    static final String QUARKUS_AOT = "quarkus-app/target/quarkus-aot-benchmark.aot";

    static final String SPRING_URL = "http://localhost:8080/hello";
    static final String SPRING_LOAD_URL = "http://localhost:8080/load";
    static final String SPRING_SHUTDOWN_URL = "http://localhost:8080/shutdown";
    static final String QUARKUS_URL = "http://localhost:8080/api/hello";
    static final String QUARKUS_LOAD_URL = "http://localhost:8080/api/load";
    static final String QUARKUS_SHUTDOWN_URL = "http://localhost:8080/api/shutdown";

    static final int RUNS = 10;
    static final int TIMEOUT_SEC = 60;
    static final int POLL_MS = 50;
    static final int SHUTDOWN_TIMEOUT_SEC = 30;

    // ANSI-Farben
    static final String RESET = "\033[0m";
    static final String RED = "\033[0;31m";
    static final String GREEN = "\033[0;32m";
    static final String YELLOW = "\033[1;33m";
    static final String CYAN = "\033[0;36m";
    static final String MAGENTA = "\033[0;35m";
    static final String WHITE = "\033[1;37m";
    static final String GRAY = "\033[0;90m";

    static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build();

    void main() throws Exception {
      // UTF-8 sicherstellen – relevant für Windows-Konsolen (Codepage 850/1252)
      System.setOut(new PrintStream(System.out, true, "UTF-8"));

      printHeader();
      checkJars();

      ensureAotCache("Spring Boot (JVM)",
          SPRING_JAR, SPRING_AOT,
          SPRING_URL, SPRING_LOAD_URL, SPRING_SHUTDOWN_URL);
      ensureAotCache("Spring Boot (JVM + AOT-Processing)",
          SPRING_JAR, SPRING_AOT_PROCESSED,
          SPRING_URL, SPRING_LOAD_URL, SPRING_SHUTDOWN_URL,
          "-Dspring.aot.enabled=true");
      ensureAotCache("Quarkus (JVM)",
          QUARKUS_JAR, QUARKUS_AOT,
          QUARKUS_URL, QUARKUS_LOAD_URL, QUARKUS_SHUTDOWN_URL);

      var results = new LinkedHashMap<String, long[]>();
      results.put("Spring Boot  - ohne AOT", new long[RUNS]);
      results.put("Spring Boot  - JEP 483", new long[RUNS]);
      results.put("Spring Boot  - AOT-Processing + JEP 483", new long[RUNS]);
      results.put("Quarkus JVM  - ohne AOT", new long[RUNS]);
      results.put("Quarkus JVM  - JEP 483", new long[RUNS]);

      for (int run = 1; run <= RUNS; run++) {
        System.out.printf("%n%s========== Durchlauf %d / %d ==========%s%n", MAGENTA, run, RUNS, RESET);
        // Erst alle Baselines, dann alle AOT-Varianten – verhindert Page-Cache-Verzerrung
        results.get("Spring Boot  - ohne AOT")[run - 1] =
            measureTtfr("Spring Boot  - ohne AOT", SPRING_JAR, SPRING_URL);
        results.get("Quarkus JVM  - ohne AOT")[run - 1] =
            measureTtfr("Quarkus JVM  - ohne AOT", QUARKUS_JAR, QUARKUS_URL);
        results.get("Spring Boot  - JEP 483")[run - 1] =
            measureTtfr("Spring Boot  - JEP 483", SPRING_JAR, SPRING_URL,
                "-XX:AOTCache=" + SPRING_AOT);
        results.get("Spring Boot  - AOT-Processing + JEP 483")[run - 1] =
            measureTtfr("Spring Boot  - AOT-Processing + JEP 483", SPRING_JAR, SPRING_URL,
                "-XX:AOTCache=" + SPRING_AOT_PROCESSED, "-Dspring.aot.enabled=true");
        results.get("Quarkus JVM  - JEP 483")[run - 1] =
            measureTtfr("Quarkus JVM  - JEP 483", QUARKUS_JAR, QUARKUS_URL,
                "-XX:AOTCache=" + QUARKUS_AOT);
      }

      printResults(results);
    }

    // =============================================================================
    // Messung
    // =============================================================================

    long measureTtfr(String label, String jar, String url, String... extraArgs) throws Exception {
      System.out.printf("%n%s--- %s ---%s%n", CYAN, label, RESET);

      var proc = startApp(jar, ProcessBuilder.Redirect.DISCARD, extraArgs);
      long startMs = System.currentTimeMillis();
      boolean ready = waitUntilReady(url);
      long elapsed = System.currentTimeMillis() - startMs;
      forceStop(proc);

      if (ready) {
        System.out.printf("%s  Time-to-First-Request: %d ms%s%n", GREEN, elapsed, RESET);
        return elapsed;
      } else {
        System.out.printf("%s  FEHLER: App nicht gestartet!%s%n", RED, RESET);
        return -1;
      }
    }

    // =============================================================================
    // AOT-Cache
    // =============================================================================

    void ensureAotCache(String label, String jar, String cacheFile,
        String readyUrl, String warmupUrl, String shutdownUrl,
        String... extraArgs) throws Exception {
      if (Files.exists(Path.of(cacheFile))) {
        System.out.printf("%s[AOT Cache] %s: bereits vorhanden (%s)%s%n", GRAY, label, cacheFile, RESET);
        return;
      }

      System.out.printf("%n%s[AOT Cache erstellen] %s%s%n", YELLOW, label, RESET);
      System.out.println("  Verwende JEP 514: -XX:AOTCacheOutput (Ein-Schritt-Workflow)");

      var allArgs = Stream.concat(
          Stream.of("-XX:AOTCacheOutput=" + cacheFile),
          Arrays.stream(extraArgs)
      ).toArray(String[]::new);

      var logFile = Path.of(cacheFile + ".training.log");
      var proc = startApp(jar, ProcessBuilder.Redirect.to(logFile.toFile()), allArgs);
      System.out.printf("  Training-Log: %s%n", logFile);

      boolean ready = waitUntilReady(readyUrl);
      if (!ready) {
        System.out.printf("%s  Training-Run fehlgeschlagen - Log pruefen: %s%s%n", RED, logFile, RESET);
        forceStop(proc);
        return;
      }

      // Warmup-Request: stellt sicher, dass alle typischen Produktions-Klassen
      // geladen werden, bevor der Cache geschrieben wird. Ohne diesen Schritt
      // erfasst der Cache nur die Startup-Klassen, nicht die Request-Handler-Klassen
      // (JAX-RS/MVC, Serialisierung, etc.) – der Cache waere dann unvollstaendig
      // und koennte die Laufzeit sogar verschlechtern.
      System.out.printf("  Warmup-Request an %s...%n", warmupUrl);
      sendGet(warmupUrl);

      System.out.printf("%s  Training abgeschlossen, initiiere Shutdown...%s%n", YELLOW, RESET);
      httpShutdown(shutdownUrl, proc);

      if (Files.exists(Path.of(cacheFile))) {
        long sizeMb = Files.size(Path.of(cacheFile)) / (1024 * 1024);
        System.out.printf("%s  Cache erstellt: %s (%d MB)%s%n", GREEN, cacheFile, sizeMb, RESET);
      } else {
        System.out.printf("%s  Cache-Datei nicht gefunden - Log pruefen: %s%s%n", RED, logFile, RESET);
      }
    }

    void ensureAotCache(String label, String jar, String cacheFile,
        String readyUrl, String warmupUrl, String shutdownUrl) throws Exception {
      ensureAotCache(label, jar, cacheFile, readyUrl, warmupUrl, shutdownUrl, new String[0]);
    }

    // =============================================================================
    // Prozess- und Shutdown-Management
    // =============================================================================

    void httpShutdown(String shutdownUrl, Process proc) throws Exception {
      var request = HttpRequest.newBuilder(URI.create(shutdownUrl))
          .POST(HttpRequest.BodyPublishers.noBody())
          .timeout(Duration.ofSeconds(5))
          .build();
      try {
        HTTP.send(request, HttpResponse.BodyHandlers.discarding());
      } catch (Exception ignored) {
      }
      boolean exited = proc.waitFor(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS);
      if (!exited) {
        System.out.printf("%sWarnung: Prozess laeuft noch nach %ds, erzwinge Kill...%s%n",
            YELLOW, SHUTDOWN_TIMEOUT_SEC, RESET);
        proc.destroyForcibly();
        proc.waitFor();
      }
    }

    void sendGet(String url) {
      var request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(10))
          .GET().build();
      try {
        HTTP.send(request, HttpResponse.BodyHandlers.discarding());
      } catch (Exception ignored) {
      }
    }

    void forceStop(Process proc) throws InterruptedException {
      if (!proc.isAlive())
        return;
      proc.destroyForcibly();
      proc.waitFor();
      Thread.sleep(300);
    }

    Process startApp(String jar, ProcessBuilder.Redirect outputRedirect,
        String... extraArgs) throws IOException {
      return new ProcessBuilder(buildJavaCmd(jar, extraArgs))
          .redirectErrorStream(true)
          .redirectOutput(outputRedirect)
          .start();
    }

    // =============================================================================
    // Hilfsmethoden
    // =============================================================================

    boolean waitUntilReady(String url) {
      var deadline = Instant.now().plusSeconds(TIMEOUT_SEC);
      var request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(1))
          .GET().build();
      while (Instant.now().isBefore(deadline)) {
        try {
          var resp = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
          if (resp.statusCode() == 200)
            return true;
        } catch (Exception ignored) {
        }
        try {
          Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      System.out.printf("%s  Timeout: %s hat nicht innerhalb von %ds geantwortet.%s%n",
          RED, url, TIMEOUT_SEC, RESET);
      return false;
    }

    List<String> buildJavaCmd(String jar, String... extraArgs) {
      var cmd = new ArrayList<String>();
      cmd.add("java");
      cmd.addAll(Arrays.asList(extraArgs));
      cmd.add("-jar");
      cmd.add(jar);
      return cmd;
    }

    // =============================================================================
    // Statistik
    // =============================================================================

    record Stats(long median, long mean, long stddev, long ci95lo, long ci95hi) {
    }

    Stats computeStats(long[] values) {
      var filtered = Arrays.stream(values).filter(v -> v >= 0).toArray();
      if (filtered.length == 0)
        return new Stats(-1, -1, -1, -1, -1);

      var sorted = Arrays.stream(filtered).sorted().toArray();
      int mid = sorted.length / 2;
      long median = sorted.length % 2 == 0
          ? (sorted[mid - 1] + sorted[mid]) / 2
          : sorted[mid];

      double mean = Arrays.stream(filtered).average().orElse(0);
      double variance = Arrays.stream(filtered)
          .mapToDouble(v -> (v - mean) * (v - mean))
          .average().orElse(0);
      long stddev = (long) Math.sqrt(variance);

      // 95%-Konfidenzintervall: t(0.025, df=9) = 2.262
      double margin = 2.262 * Math.sqrt(variance) / Math.sqrt(filtered.length);
      return new Stats(median, (long) mean, stddev,
          (long) (mean - margin), (long) (mean + margin));
    }

    String formatImprovement(long before, long after) {
      if (before <= 0 || after <= 0)
        return "n/a";
      double pct = (1.0 - (double) after / before) * 100;
      return String.format("%+.1f%%", -pct);
    }

    // =============================================================================
    // Ausgabe
    // =============================================================================

    void printHeader() {
      var version = Runtime.version();
      System.out.printf("%n%s============================================================%s%n", WHITE, RESET);
      System.out.printf("%s AOT Benchmark - JEP 483 + JEP 514%s%n", WHITE, RESET);
      System.out.printf("%s Spring Boot 4.0.3 vs. Quarkus 3.32.1 (JVM-Mode)%s%n", WHITE, RESET);
      System.out.printf("%s============================================================%s%n", WHITE, RESET);
      System.out.printf("%nJava: %s%n", version);
      if (version.feature() < 25) {
        System.out.printf("%sWarnung: JDK 25+ erforderlich (JEP 514: AOTCacheOutput).%s%n", YELLOW, RESET);
      }
    }

    void checkJars() {
      for (var jar : List.of(SPRING_JAR, QUARKUS_JAR)) {
        if (!Files.exists(Path.of(jar))) {
          System.out.printf("%sJAR nicht gefunden: %s%s%n", RED, jar, RESET);
          System.out.println("Bitte zuerst 'mvn package' in spring-app/ und quarkus-app/ ausfuehren.");
          System.exit(1);
        }
      }
    }

    void printResults(Map<String, long[]> allResults) {
      var sbBaseStats = computeStats(allResults.get("Spring Boot  - ohne AOT"));
      var qkBaseStats = computeStats(allResults.get("Quarkus JVM  - ohne AOT"));

      System.out.printf("%n%s============================================================%s%n", WHITE, RESET);
      System.out.printf("%s ERGEBNISSE (n=%d, 95%%-Konfidenzintervall)%s%n", WHITE, RUNS, RESET);
      System.out.printf("%s============================================================%s%n%n", WHITE, RESET);

      System.out.printf("  %-40s %8s %8s %14s %22s%n",
          "Variante", "Median", "Stddev", "vs. Baseline", "95%-KI");
      System.out.printf("  %s%n", "-".repeat(96));

      for (var entry : allResults.entrySet()) {
        var label = entry.getKey();
        var stats = computeStats(entry.getValue());
        var isBase = label.contains("ohne AOT");
        var base = label.startsWith("Spring") ? sbBaseStats : qkBaseStats;
        var improve = isBase ? "(Baseline)" : formatImprovement(base.median(), stats.median());

        System.out.printf("  %-40s %7dms %7dms %14s   [%dms - %dms]%n",
            label, stats.median(), stats.stddev(), improve, stats.ci95lo(), stats.ci95hi());
      }

      System.out.printf("%n%s  Gemessen: Time-to-First-Request (TTFR), n=%d Durchlaeufe.%s%n", GRAY, RUNS, RESET);
      System.out.printf("%s  AOT-Cache erstellt mit JEP 514 (-XX:AOTCacheOutput) inkl. Warmup-Request.%s%n", GRAY, RESET);
      System.out.printf("%s  AOT-Cache genutzt   mit JEP 483 (-XX:AOTCache).%s%n", GRAY, RESET);
      System.out.printf("%s  Spring AOT-Processing: -Dspring.aot.enabled=true.%s%n", GRAY, RESET);
      System.out.printf("%s============================================================%s%n", WHITE, RESET);
    }