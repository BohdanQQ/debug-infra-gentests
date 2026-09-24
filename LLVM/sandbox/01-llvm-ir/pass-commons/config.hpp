#ifndef LLCAP_LLVMPASS_CFG
#define LLCAP_LLVMPASS_CFG

#include "./llvm-metadata.h"
#include "typeAlias.hpp"

#define TOML_EXCEPTIONS 0
#include "toml/toml.hpp"
#include <llvm/Support/raw_ostream.h>
#include <optional>
#include <string_view>

struct FnHookDesc {
  // name of the hook fn in hooklib
  Str name;
  // name of the function in logs
  Str logName;
  bool isInvalidLlcapSize{false};
};

enum class MatchMode { Exact, Regex, IsUnsigned };

struct TypeMatcher {
  MatchMode mode;
  Str value;
};

struct Config {
  Map<Str, FnHookDesc> hookDescriptors;
  Map<Str, TypeMatcher> astTypeMatchers;
  bool useMangledNames{false};
  Maybe<Str> modMapsDir;
  bool performFnExitInstrumentation{false};
  bool selectingByRegex{false};
  bool verbose{false};
  bool debug{false};
  // either a Regex or the selection file (function that is being instrumented)
  Str SelectionStr;

  struct Defaultable {
    bool performFnExitInstrumentation{false};
    bool selectingByRegex{false};
    bool verbose{false};
    bool debug{false};
    bool useMangledNames{false};
    Str selectionStr;
  };

private:
  static Maybe<Str> helperGetStrValue(std::string_view Key,
                                      const toml::table &Tbl) {
    auto const *V = Tbl.get(Key);
    if ((V == nullptr) || !V->is_string()) {
      return std::nullopt;
    }

    return V->as_string()->get();
  }

public:
  void setDefaults() {
    static Map<Str, FnHookDesc> DefaultHooks{
        {LLCAP_TYPE_STD_STRING,
         FnHookDesc{
             .name = "llcap_hooklib_extra_cxx_string",
             .logName = "default std::string",
         }},
        {LLCAP_TYPE_STD_VECINT,
         FnHookDesc{
             .name = "llcap_vector_cint",
             .logName = "default std::vector<int>",
         }},
        {LLCAP_TYPE_STD_VECSTR,
         FnHookDesc{
             .name = "llcap_vector_stdstring",
             .logName = "default std::vector<std::string>",
         }},
        {LLCAP_UNSIGNED_IDCS, FnHookDesc{.name = "llcap_NOTHING_NEVER",
                                         .logName = "invalid type",
                                         .isInvalidLlcapSize = true}}};
    static Map<Str, TypeMatcher> DefaultMatchers{
        {LLCAP_TYPE_STD_STRING,
         TypeMatcher{
             .mode = MatchMode::Exact,
             .value = "class std::basic_string<char>",
         }},
        {LLCAP_TYPE_STD_VECINT,
         TypeMatcher{
             .mode = MatchMode::Exact,
             .value = "class std::vector<int>",
         }},
        {LLCAP_TYPE_STD_VECSTR,
         TypeMatcher{
             .mode = MatchMode::Exact,
             .value = "class std::vector<class std::basic_string<char> >",
         }},
        {LLCAP_UNSIGNED_IDCS,
         TypeMatcher{.mode = MatchMode::IsUnsigned, .value = ""}}};

    for (const auto &[K, defaultEntry] : DefaultHooks) {
      auto &Descs = this->hookDescriptors;
      if (Descs.find(K) != Descs.end()) {
        llvm::errs() << "Metadata key " << K
                     << " is a default. Make sure you understand you are "
                        "overriding the default behavior of the hooks!\n";
      } else {
        Descs[K] = defaultEntry;
      }
    }

    for (const auto &[K, defaultEntry] : DefaultMatchers) {
      auto &Matchers = this->astTypeMatchers;
      if (Matchers.find(K) != Matchers.end()) {
        llvm::errs() << "Metadata key " << K
                     << " is a default. Make sure you understand you are "
                        "overriding the default behavior of the matchers!\n";
      } else {
        Matchers[K] = defaultEntry;
      }
    }
  }

  static Maybe<Config> parseConfigFrom(const Str &Path,
                                       const Defaultable &Defaults) {
    if (Path.empty()) {
      auto Res = Config();
      Res.setDefaults();
      return Res;
    }

    auto const Parsed = toml::parse_file(Path);

    auto const *Res = Parsed.table().get("nonllvm-types");
    Config Cfg;
    if (Res != nullptr && Res->is_table()) {
      llvm::errs()
          << "nonllvm-types is either missing or not a top-level table\n";
      for (const auto [k, v] : *(Res->as_table())) {
        if (!Res->is_table()) {
          llvm::errs() << "Key " << k << " is (unexpectedly) not a table\n";
          continue;
        }
        const auto &EntryTbl = *v.as_table();
        if (!EntryTbl.get("typenames")->is_array_of_tables()) {
          llvm::errs()
              << "Key " << k
              << ".typenames is (unexpectedly) not an array of tables\n";
          continue;
        }

        auto GetStr = [EntryTbl](std::string_view K) {
          return helperGetStrValue(K, EntryTbl);
        };

        const auto ConfigKey = GetStr("key");
        const auto Name = GetStr("hooklib-fn");
        const auto Logname = GetStr("log-name");
        if (!ConfigKey || !Name || !Logname) {
          llvm::errs() << "One of the keys under table " << k
                       << " is missing (key, hook-fn, log-name)\n";
          continue;
        }

        const auto &MatchersTableArr = *EntryTbl.get("typenames")->as_array();
        bool Ok{true};
        for (const auto &V : MatchersTableArr) {
          const auto &TypenamesTable = *V.as_table();
          const auto Matcher = helperGetStrValue("value", TypenamesTable);
          if (!Matcher) {
            llvm::errs() << "Table " << k
                         << ".typenames is in invalid format\n";
            Ok = false;
            break;
          }
          Cfg.astTypeMatchers[*ConfigKey] =
              TypeMatcher{.mode = MatchMode::Exact, .value = *Matcher};
        }
        if (!Ok) {
          continue;
        }
        Cfg.hookDescriptors[*ConfigKey] =
            FnHookDesc{.name = *Name, .logName = *Logname};
      }
    }

    auto const *PassRes = Parsed.table().get("llvm-pass");
    if (PassRes == nullptr || !PassRes->is_table()) {
      llvm::errs() << "llvm-pass is either missing or not a top-level table\n";
      return std::nullopt;
    }

    const auto &PassTable = *(PassRes->as_table());
    auto DefaultBool = [&PassTable](bool &Target, std::string_view K,
                                    bool Default) {
      const auto It = PassTable.find(K);
      if (It != PassTable.end()) {
        Target = It->second.as_boolean()->get();
      } else {
        Target = Default;
      }
    };
    DefaultBool(Cfg.useMangledNames, "mangling-std-filter",
                Defaults.useMangledNames);
    DefaultBool(Cfg.performFnExitInstrumentation, "instrument-for-fn-exit",
                Defaults.performFnExitInstrumentation);
    DefaultBool(Cfg.verbose, "verbose", Defaults.verbose);
    DefaultBool(Cfg.debug, "debug", Defaults.debug);
    auto Val = helperGetStrValue("fn-target-regex", PassTable);
    if (Val && !Val->empty()) {
      Cfg.selectingByRegex = true;
      Cfg.SelectionStr = *Val;
    } else {
      Cfg.selectingByRegex = Defaults.selectingByRegex;
      Cfg.SelectionStr = Defaults.selectionStr;
    }

    Cfg.setDefaults();
    return Cfg;
  }
};

#endif // LLCAP_LLVMPASS_CFG