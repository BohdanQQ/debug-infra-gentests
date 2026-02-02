#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <iostream>
#include <semaphore>
#include <stdexcept>
#include <thread>

using namespace std::this_thread;
using namespace std::chrono;

static int counter = 1;

void test_target(int count_up) {
  if (count_up < 0) {
    throw std::invalid_argument("count_up");
  }

  if (counter > 0) {
    counter += count_up;
  }
}

std::counting_semaphore sem(0);

// worker1 increments by its value (2)
// worker2 throws exception (due to the value being negative: -2)

// depending on the replacement, we expect
// diagonal cases to exit with exception
// replacing 2 with -2 in worker1 in the first call -> exception
// replacing -2 with 2 in worker2 in its first call -> pass

void worker1(int value) {
  sem.acquire();
  test_target(value);
  sleep_for(1s);
  sem.release();
}

void worker2(int value) {
  sem.acquire();
  sleep_for(1s);
  sem.acquire();
  test_target(value);
}

int main() {
  std::thread t1(worker1, 2);
  std::thread t2(worker2, -2);
  sem.release();
  sem.release();

  t1.join();
  t2.join();
  return counter;
}
