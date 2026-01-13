#include <cassert>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <numeric>
#include <vector>

std::string test_target(std::vector<std::string>& vec, const char* x) {
  vec.push_back(x);
  return std::accumulate(vec.begin(), vec.end(), std::string());
}

int main() {
  std::vector<std::string> v{"hello", "from"};
  for (const auto x : {"another", "world"}) {
    auto res = test_target(v, x);
    std::cout << res << ' ' << x << std::endl;

    if (res.size() > 22) {
      // len of hellofromanotheranother is 23
      // that happens when 1st call is replaced with v = {hello, from, another} and "another" is appened
      return res.size();
    }
  }

  return 0;
}