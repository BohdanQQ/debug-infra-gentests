#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>

constexpr int ENSURED_SND_CALL{3};

int test_target(int i, float f) {
  static int call_counter = 0;
  ++call_counter;

  if (call_counter == 1 && i == 0) {
    // first gets killed on fourth set of arguments
    *((volatile int*)0);
  }

  if (call_counter == 2 && i != ENSURED_SND_CALL) {
    // exits on wrong instrumentation (every case but the diagonal)
    exit(100);
  }

  if (call_counter == 4 && i > 0) {
    // fourt call exits everytime but the diagonal
    exit(i);
  }
  return i * f;
}


int main() {
  int result = test_target(21, 3.0f);
  test_target(ENSURED_SND_CALL, 4.0f);
  // insertion of these 2 sleeps should demonstrate the CRIU approach speedup
  //sleep(1);
  test_target(44, 2.0f);
  //sleep(1);
  // if test_target is tested, one test will fail due to the check above
  test_target(0, 0);
  return result;
}