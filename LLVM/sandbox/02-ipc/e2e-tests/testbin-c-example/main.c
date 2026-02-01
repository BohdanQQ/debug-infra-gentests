#include "stdio.h"
#include <unistd.h>

int test_target(int i, float f) {
  printf("Returning %d\n", (int)(i*f));
  fflush(stdout);
  return i * f;
}


int main(void) {
  int result = test_target(21, 3.0f);
  test_target(3, 4.0f);
  if (result == 0) {
    fflush(stdout);
    *((volatile int*)0);
  }
  test_target(0, 1.0f);
  return result;
}