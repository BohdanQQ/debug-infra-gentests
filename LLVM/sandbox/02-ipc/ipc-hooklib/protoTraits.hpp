#include "protobuf/proto/main.pb.h"
#include <type_traits>

#define CHECK_EXTRACT_PAIR(fn_name, t)                                         \
  auto checker = &llcaproto::SingleArgVariant::has_##fn_name;                  \
  VariantExtractor<t> extractor = &llcaproto::SingleArgVariant::fn_name;       \
  return std::make_pair(checker, extractor)

template <typename T>
  requires std::copy_constructible<T>
bool assign(T &dest, const T &src) {
  dest = src;
  return true;
}

static bool assign_stringwrap(std::string &dest,
                              const llcaproto::StringWrap &src) {
  const auto &str_val = src.value();
  dest.reserve(src.capacity());
  dest.resize(str_val.size());
  if (nullptr != str_val.data()) {
    std::memcpy(dest.data(), str_val.data(), str_val.size());
  }
  return true;
}

template <class T, class... S> consteval bool oneOfT() {
  return (std::is_same_v<T, S> || ...);
}

template <class T>
bool constexpr IS_PROTO_PRIMITIVE_V =
    oneOfT<T, int32_t, int64_t, float, double, uint64_t, uint32_t>();

template <class T>
bool constexpr IS_PROTO_FALLBACK_PRIMITIVE_V =
    oneOfT<T, char, unsigned char, short, unsigned short>();

template <typename RetT>
using VariantExtractor = RetT (llcaproto::SingleArgVariant::*)() const;

using VariantChecker = bool (llcaproto::SingleArgVariant::*)() const;

template <class T> class ProtobufNestTrait {};

#define T_IS(S) std::is_same_v<T, S>
template <class T, class ResT>
consteval std::pair<VariantChecker, VariantExtractor<ResT>> primitiveChecker() {

  if constexpr (T_IS(float)) {
    CHECK_EXTRACT_PAIR(flt, ResT);
  } else if constexpr (T_IS(double)) {
    CHECK_EXTRACT_PAIR(dbl, ResT);
  } else if constexpr (T_IS(int32_t)) {
    CHECK_EXTRACT_PAIR(i32, ResT);
  } else if constexpr (T_IS(uint32_t)) {
    CHECK_EXTRACT_PAIR(u32, ResT);
  } else if constexpr (T_IS(int64_t)) {
    CHECK_EXTRACT_PAIR(i64, ResT);
  } else if constexpr (T_IS(uint64_t) || IS_PROTO_FALLBACK_PRIMITIVE_V<T>) {
    CHECK_EXTRACT_PAIR(u64, ResT);
  } else if constexpr (T_IS(std::string)) {
    VariantChecker checker = &llcaproto::SingleArgVariant::has_str;
    VariantExtractor<const llcaproto::StringWrap &> extractor =
        &llcaproto::SingleArgVariant::str;
    return std::make_pair(checker, extractor);
  } else {
    static_assert(false, "unsupported type");
  }
}

// ensures that
template <typename Tgt, typename Src>
concept ConvertibleIsh =
    std::is_convertible_v<Src, Tgt> && // types are generally convertible
    (sizeof(Src) <= sizeof(Tgt)) &&    // the Target can fit the Src byte-wise
    (std::is_signed_v<Tgt> == std::is_signed_v<Src>); // sign is the same

template <typename T, typename NumT>
  requires std::copy_constructible<T> && ConvertibleIsh<T, NumT>
static bool capture_into(llcaproto::SingleArgVariant *capture, NumT n) {
  if constexpr (T_IS(float)) {
    capture->set_flt(n);
  } else if constexpr (T_IS(double)) {
    capture->set_dbl(n);
  } else if constexpr (T_IS(int32_t)) {
    capture->set_i32(n);
  } else if constexpr (T_IS(uint32_t)) {
    capture->set_u32(n);
  } else if constexpr (T_IS(int64_t)) {
    capture->set_i64(n);
  } else if constexpr (T_IS(uint64_t)) {
    capture->set_u64(n);
  } else {
    static_assert(false, "invalid type");
  }
  return true;
}
#undef T_IS

template <class T>
  requires(IS_PROTO_PRIMITIVE_V<T> && !IS_PROTO_FALLBACK_PRIMITIVE_V<T>)
struct ProtobufNestTrait<T> {
  using ExtractorType = VariantExtractor<T>;
  constexpr static ExtractorType extractor = primitiveChecker<T, T>().second;
  constexpr static VariantChecker checker = primitiveChecker<T, T>().first;
  constexpr static bool construct(T &dest, T src) {
    return assign<T>(dest, src);
  }
  static bool variant_capture(llcaproto::SingleArgVariant *capture, T n) {
    return capture_into<T>(capture, n);
  }
};

template <class T>
  requires(IS_PROTO_FALLBACK_PRIMITIVE_V<T>)
struct ProtobufNestTrait<T> {
  using ExtractorType = VariantExtractor<uint64_t>;
  constexpr static ExtractorType extractor =
      primitiveChecker<T, uint64_t>().second;
  constexpr static VariantChecker checker =
      primitiveChecker<T, uint64_t>().first;
  constexpr static bool construct(T &dest, T src) {
    return assign<T>(dest, src);
  }
  static bool variant_capture(llcaproto::SingleArgVariant *capture, T n) {
    return capture_into<T>(capture, n);
  }
};

bool capture_stringwrap(llcaproto::SingleArgVariant *capture,
                        const std::string &str,
                        google::protobuf::Arena *arena) {
  if (str.size() > UINT32_MAX) {
    perror("strhook cerr: size error");
    return false;
  }
  ::llcaproto::StringWrap *protoString =
      google::protobuf::Arena::Create<llcaproto::StringWrap>(arena);

  protoString->set_capacity(str.capacity());
  // FIXME: avoid this copy?
  protoString->set_value(str);
  capture->set_allocated_str(protoString);
  return true;
}

template <> struct ProtobufNestTrait<std::string> {
  using ExtractorType = VariantExtractor<const llcaproto::StringWrap &>;
  // member function pointer to the protobuf-generated member function that GETs
  // the a reference to the protoBuf type representing the wrapper around the
  // custom type - here std::string is wrapped in a StringWrap
  constexpr static ExtractorType extractor = &llcaproto::SingleArgVariant::str;
  // same, just with checking the presence of the wrapper type
  constexpr static VariantChecker checker =
      &llcaproto::SingleArgVariant::has_str;

  // --- used in argument hijacking
  // returns a function wrapper that
  // takes in a MUTABLE reference to a target string
  // - if this function returns true, the first argument is a valid
  //   value of the type (meant to be constructed from the wrapper - second
  //   argument)
  static bool construct(std::string &dest, const llcaproto::StringWrap &src) {
    return assign_stringwrap(dest, src);
  }

  // --- used in argument capture
  // receives an allocated SignleArgVariant and captures the std::string
  // into it
  // returns true on success
  static bool variant_capture(llcaproto::SingleArgVariant *capture,
                              const std::string &str) {
    return capture_stringwrap(capture, str, capture->GetArena());
  }
};
