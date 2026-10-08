package demo;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** A tiny HTTP server: http://localhost:8081/hello?name=Eclipse */
public class DemoServer {

  public static void main(String[] args) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
    server.createContext("/hello", exchange -> {
      String query = exchange.getRequestURI().getQuery();
      String name = query != null && query.startsWith("name=") ? query.substring(5) : "world";
      byte[] body = Greetings.greet(name).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
    System.out.println("Listening on http://localhost:8081/hello?name=Eclipse");
  }
}
