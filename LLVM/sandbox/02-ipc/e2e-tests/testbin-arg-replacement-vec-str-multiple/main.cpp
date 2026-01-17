#include <cassert>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <numeric>
#include <vector>

using StrVec = std::vector<std::string>;

std::string test_target(StrVec& vec, StrVec& vec2, const char* x) {
  vec.push_back(x);
  vec2.push_back(x);

  return std::accumulate(vec.begin(), vec.end(), std::string());
}

int main() {
  StrVec u{"explore"};
  StrVec v{"hello", "from"};

  std::string res;
  for (const auto x : {"another", "world"}) {
    // renminder/note: replacement occurs in the function only! (we cannot modify e.g. const
    // objects)
    // Therefore the effect is only propagated in res
    // and outside of the test_target, NOTHING is changed in u, v
    // (they skip the loop's effect)
    res = test_target(v, u, x);
  }
  std::cout << res << std::endl;
  return res.size();
}