#include <cassert>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <numeric>
#include <vector>

int test_target(std::vector<int>& vec) {
  return std::accumulate(vec.begin(), vec.end(), 0);
}

int main() {
  std::vector<int> v{1, 2};
  for (const auto x : {-1, -2, -3}) {
    v.push_back(x);
    auto res = test_target(v);
    if (res < 0) {
      return -res;
    }
  }
  // can happen when the last call is replaced with v = {1, 2, -1} or v = {1, 2, -1, -2}
  return 0;
}