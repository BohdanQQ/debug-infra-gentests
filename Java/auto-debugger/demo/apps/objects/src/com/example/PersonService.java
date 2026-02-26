package com.example;

public class PersonService {
  public String greet(Person p) {
    return "Hello, " + p.getName() + " (" + p.getAge() + ")";
  }
}

