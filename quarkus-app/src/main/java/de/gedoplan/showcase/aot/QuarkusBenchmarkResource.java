package de.gedoplan.showcase.aot;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.concurrent.atomic.AtomicLong;

@Path("/api")
@ApplicationScoped
public class QuarkusBenchmarkResource {

  private static final AtomicLong counter = new AtomicLong();

  @GET
  @Path("/hello")
  @Produces(MediaType.TEXT_PLAIN)
  public String hello() {
    return "Hello Quarkus AOT Benchmark! #" + counter.incrementAndGet();
  }

  @GET
  @Path("/load")
  @Produces(MediaType.TEXT_PLAIN)
  public String loadTest() {
    // Identische CPU-Simulation wie Spring
    long sum = 0;
    for (int i = 0; i < 1_000_000; i++) {
      sum += (long) (Math.sin(i) * Math.cos(i));
    }
    return "Quarkus Load sum: " + sum;
  }

  /// Shutdown-Endpoint für den AOT-Training-Run.
  /// Quarkus reagiert auf SIGTERM nicht mit einem geordneten Shutdown,
  /// der die JVM-Shutdown-Hooks (inkl. JEP-514-Cache-Schreiben) auslöst.
  /// Dieser Endpoint ruft System.exit(0) auf, was einen sauberen JVM-Shutdown
  /// inklusive aller Shutdown-Hooks garantiert.
  @POST
  @Path("/shutdown")
  @Produces(MediaType.TEXT_PLAIN)
  public String shutdown() {
    // Asynchron beenden, damit die HTTP-Response noch zurückgesendet wird
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(200);
      } catch (InterruptedException ignored) {
      }
      System.exit(0);
    });
    return "Shutting down...";
  }
}
