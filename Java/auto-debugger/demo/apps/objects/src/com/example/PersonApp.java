package com.example;

public class PersonApp {
  public static void main(String[] args) {
    PersonService svc = new PersonService();
    // invoke target method with different Person objects
    System.out.println(svc.greet(new Person("Alice", 30)));
    System.out.println(svc.greet(new Person("Bob", 25)));
    System.out.println(svc.greet(new Person("Charlie", 35)));
  }
}

