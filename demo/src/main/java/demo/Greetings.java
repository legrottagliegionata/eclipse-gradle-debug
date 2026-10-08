package demo;

public final class Greetings {

  private Greetings() {}

  public static String greet(String name) {
    String greeting = "Hello, " + name + "!";
    return greeting;
  }
}
