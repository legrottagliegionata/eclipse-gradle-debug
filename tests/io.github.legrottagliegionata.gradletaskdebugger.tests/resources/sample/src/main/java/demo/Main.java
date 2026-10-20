package demo;

import library.Shouter;

public class Main {
  public static void main(String[] args) {
    Greeting greeting = new Greeting();
    System.out.println(greeting.text());
    System.out.println(Shouter.shout("and from a library"));
  }
}
