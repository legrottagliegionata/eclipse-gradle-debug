package demo;

import java.util.function.Supplier;

/** With a lambda, which javac and ECJ compile to methods with different names. */
public class Greeting {
  public String text() {
    Supplier<String> text = () -> "Hello from a JVM forked by Gradle";
    return text.get();
  }
}
