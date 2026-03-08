package de.gedoplan.showcase.aot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.atomic.AtomicLong;

@SpringBootApplication
public class SpringBenchmarkApplication {
  public static void main(String[] args) {
    SpringApplication.run(SpringBenchmarkApplication.class, args);
  }
}

@RestController
class BenchmarkController {

  private final ConfigurableApplicationContext context;
  private static final AtomicLong counter = new AtomicLong();

  BenchmarkController(ConfigurableApplicationContext context) {
    this.context = context;
  }

  @GetMapping("/hello")
  public String hello() {
    return "Hello Spring AOT Benchmark! #" + counter.incrementAndGet();
  }

  @GetMapping("/load")
  public String loadTest() {
    long sum = 0;
    for (int i = 0; i < 1_000_000; i++) {
      sum += (long) (Math.sin(i) * Math.cos(i));
    }
    return "Spring Load sum: " + sum;
  }

  /// Shutdown-Endpoint analog zu Quarkus /api/shutdown. Fährt den ApplicationContext
  /// sauber herunter → alle Shutdown-Hooks laufen durch → JEP-514-Cache wird auf Disk geschrieben.
  /// Kein Actuator erforderlich.
  @PostMapping("/shutdown")
  public String shutdown() {
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(100);
      } catch (InterruptedException ignored) {
      }
      context.close();
    });
    return "Shutting down...";
  }
}