#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <iostream>

void inner_void(int x) {
  if (x%3 == 0) {
    printf("side effect");
  }
}

int inner(int num) {
  if (num == 0) {
    throw "exception";
  }
  return num * num;
}

int test_target(int i, float f) {
  static int call_counter = 0;
  if (call_counter == 0) {
    ++call_counter;
    return 0;
  }
  // can throw an exception
  auto v = inner(i);
  inner_void(i);
  
  return i * f * v;
}


int main() {
  test_target(11, 12);
  int result = test_target(21, 3.0f);

  test_target(3, 4.0f);
  if (result == 0) {
    *((volatile int*)0);
  }

  try {
    test_target(44, 2.0f);
  } catch (const char*) {
    return 0;
  }

  test_target(0, 0);
  return result;
}