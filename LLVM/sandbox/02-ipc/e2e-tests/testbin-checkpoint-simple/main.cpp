#include <cassert>
#include <cstdio>
#include <cstdlib>


int test_target(int i, float f) {
  static int call_counter = 0;
  ++call_counter;

  if (call_counter == 3 && i > 0) {
    std::quick_exit(i);
  }
  return i * f;
}


int main() {
  int result = test_target(21, 3.0f);
  test_target(44, 2.0f);
  test_target(0, 0);
  return result;
}