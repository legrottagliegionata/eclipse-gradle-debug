package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreetingsTest {

  @Test
  void greetsByName() {
    assertEquals("Hello, Eclipse!", Greetings.greet("Eclipse"));
  }
}
