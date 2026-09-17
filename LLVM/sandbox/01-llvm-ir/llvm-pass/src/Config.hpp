#ifndef LLCAP_LLVMPASS_CFG
#define LLCAP_LLVMPASS_CFG

#include "../../custom-metadata-pass/ast-meta-add/llvm-metadata.h"
#include "typeAlias.hpp"

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

struct Config {
  Map<Str, FnHookDesc> hookDescriptors;
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

  void setDefaults() {
    static Map<Str, FnHookDesc> Defaults{
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

    for (const auto &[K, defaultEntry] : Defaults) {
      if (this->hookDescriptors.contains(K)) {
        llvm::errs() << "Metadata key " << K
                     << " is a default. Make sure you understand you are "
                        "overriding the default behavior of the hooks!\n";
      } else {
        this->hookDescriptors[K] = defaultEntry;
      }
    }
  }

public:
  static Maybe<Config> parseConfigFrom(const Str &Path,
                                       const Defaultable &Defaults) {
    if (Path.empty()) {
      auto Res = Config();
      Res.setDefaults();
      return Res;
    }

    auto const Parsed = toml::parse_file(Path);

    auto const *Res = Parsed.get("nonllvm-types");
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
        }
        if (!Ok) {
          continue;
        }
        Cfg.hookDescriptors[*ConfigKey] =
            FnHookDesc{.name = *Name, .logName = *Logname};
      }
    }

    auto const *PassRes = Parsed.get("llvm-pass");
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