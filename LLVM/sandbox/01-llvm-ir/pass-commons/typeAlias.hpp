#ifndef LLCPASS_ALIASES
#define LLCPASS_ALIASES

#include <array>
#include <bits/stdc++.h>
#include <cstdint>
#include <cstdlib>
#include <map>
#include <optional>
#include <set>
#include <tuple>
#include <utility>

using u64 = uint64_t;
using u32 = uint32_t;
using u16 = uint16_t;
using u8 = uint8_t;
using i64 = int64_t;
using i32 = int32_t;
using i16 = int16_t;
using i8 = int8_t;

template <class T> using Vec = std::vector<T>;
template <class T> using Maybe = std::optional<T>;

inline constexpr std::nullopt_t NONE = std::nullopt;

template <class T> using Set = std::set<T>;
template <class K, class V> using Map = std::map<K, V>;
template <class T, size_t N> using Arr = std::array<T, N>;
template <class T1, class T2> using Pair = std::pair<T1, T2>;
template <class... T> using Tuple = std::tuple<T...>;
using Str = std::string;

#endif // LLCPASS_ALIASES