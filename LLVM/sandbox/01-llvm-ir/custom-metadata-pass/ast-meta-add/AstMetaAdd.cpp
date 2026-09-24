//===- Adapted from LLVM's PrintFunctionNames.cpp
//--------------------------===//
//
// Part of the LLVM Project, under the Apache License v2.0 with LLVM Exceptions.
// See https://llvm.org/LICENSE.txt for license information.
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//
#include "./pass-commons/config.hpp"
#include "./pass-commons/llvm-metadata.h"
#include "clang/AST/ASTConsumer.h"
#include "clang/AST/Decl.h"
#include "clang/AST/DeclCXX.h"
#include "clang/AST/PrettyPrinter.h"
#include "clang/AST/RecursiveASTVisitor.h"
#include "clang/Basic/Diagnostic.h"
#include "clang/Basic/LangOptions.h"
#include "clang/Frontend/CompilerInstance.h"
#include "clang/Frontend/FrontendPluginRegistry.h"
#include "clang/Sema/Sema.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Support/StringSaver.h"
#include "llvm/Support/raw_ostream.h"
#include <clang/AST/ASTContext.h>
#include <clang/AST/Decl.h>
#include <clang/Frontend/FrontendAction.h>
#include <sstream>
#include <string>
#include <vector>

using namespace clang;

namespace {

// used with StringRefs - they do not own data they reference - we must ensure
// the lifetime of our metadata strings survives up until the IR generation
static std::set<std::string> StringBackings;

bool isTargetTypeValRefPtr(const std::string &S, const std::string &Target) {
  return S == Target || S == Target + " *" || S == Target + " &" ||
         S == Target + " &&";
}

// PredicateT :: (ParmVarDecl* param, size_t ParamIndex) -> bool
// selects parameter indicies of FD which satisfy pred predicate
template <typename PredicateT>
std::vector<size_t> filterParmIndicies(const FunctionDecl *FD,
                                       PredicateT Predicate) {
  std::vector<size_t> Indicies;
  size_t ParamIndex = 0;
  for (ParmVarDecl *const *It = FD->param_begin(); It != FD->param_end();
       ++It) {
    auto *Param = *It;
    if (Predicate(Param, ParamIndex)) {
      Indicies.push_back(ParamIndex);
    }
    ++ParamIndex;
  }
  return Indicies;
}

// inserts metadata encoding argument indicies under the specified metadata key
// for the function represented by FD
void addIndiciesMetadata(const std::string MetaKey, const FunctionDecl *FD,
                         const std::vector<size_t> &Indicies) {
  std::stringstream ResStream("");
  for (size_t i = 0; i < Indicies.size(); ++i) {
    ResStream << std::to_string(Indicies[i]);
    if (i != Indicies.size() - 1) {
      ResStream << LLCAP_SINGLECHAR_SEP;
    }
  }
  // all this could be theoretically done in a much more lightweight fashion
  // using metadata with multiple numeric operands (but I have not yet exposed
  // this through the patched API)
  auto Res = ResStream.str();
  if (Res.length() > 0) {
    // TODO: use llvm::StringSaver &Saver
    StringBackings.emplace(Res);
    StringBackings.emplace(MetaKey);
    FD->setIrMetadata(*StringBackings.find(MetaKey), *StringBackings.find(Res));
  }
}

// Predicate :: (ParmVarDecl* param, size_t ParamIndex) -> bool
template <typename Predicate>
void encodeArgIndiciesSatisfying(const std::string MetadataKey,
                                 const FunctionDecl *FD, Predicate Pred) {
  auto Indicies = filterParmIndicies(FD, Pred);
  addIndiciesMetadata(MetadataKey, FD, Indicies);
}

// adds all metadata of interest to FD
// log parameter is only for debugging purposes
void addFunctionMetadata(const FunctionDecl *FD, const Config &Cfg,
                         llvm::StringSaver &Saver) {
  auto &SourceManager = FD->getASTContext().getSourceManager();
  auto Loc = SourceManager.getExpansionLoc(FD->getBeginLoc());
  bool InSystemHeader = SourceManager.isInSystemHeader(Loc) ||
                        SourceManager.isInExternCSystemHeader(Loc) ||
                        SourceManager.isInSystemMacro(Loc);
  if (Cfg.debug) {
    llvm::errs() << FD->getDeclName() << ' '
                 << FD->getSourceRange().printToString(
                        FD->getASTContext().getSourceManager())
                 << '\n';
    FD->getNameForDiagnostic(llvm::errs(), PrintingPolicy(LangOptions()), true);
    llvm::errs() << '\n';
  }
  if (!InSystemHeader) {
    for (const auto &[MetadataKey, Matcher] : Cfg.astTypeMatchers)
      switch (Matcher.mode) {
      case MatchMode::Exact:
        encodeArgIndiciesSatisfying(
            MetadataKey, FD, [Matcher, &Cfg](ParmVarDecl *Arg, size_t Idx) {
              auto TypeName = Arg->getType().getCanonicalType().getAsString();
              if (Cfg.verbose) {
                llvm::errs() << TypeName << " <+> ";
              }
              return isTargetTypeValRefPtr(TypeName, Matcher.value);
            });
        break;
      case MatchMode::IsUnsigned:
        encodeArgIndiciesSatisfying(
            MetadataKey, FD, [&Cfg](ParmVarDecl *Arg, size_t Idx) {
              if (Cfg.verbose) {
                llvm::errs() << Arg->getType().getCanonicalType().getAsString()
                             << " <+> ";
              }
              return Arg->getType()->isUnsignedIntegerType();
            });
        break;
      case MatchMode::Regex:
        llvm::errs() << "Regex type matching not yet supported\n";
        encodeArgIndiciesSatisfying(
            MetadataKey, FD, [&Cfg](ParmVarDecl *Arg, size_t) {
              if (Cfg.verbose) {
                llvm::errs() << Arg->getType().getCanonicalType().getAsString()
                             << " <+> ";
              }
              return false;
            });
        break;
      default:
        llvm::errs() << "Unknown matcher mode!\n";
        break;
      }

    // we also insert metadata regarding the locaiton of the function, we use
    // this to filter functions during IR instrumentation
    FD->setIrMetadata(LLCAP_FN_NOT_IN_SYS_HEADER_KEY,
                      LLCAP_FN_NOT_IN_SYS_HEADER_VAL);
    // we also delegate whether this pointer is present
    if (FD->isCXXInstanceMember()) {
      FD->setIrMetadata(LLCAP_THIS_PTR_MARKER_KEY, "");
    }
  } else if (Cfg.debug) {
    llvm::errs() << "Function in system header due to:\n"
                 << SourceManager.isInSystemHeader(Loc) << " "
                 << SourceManager.isInExternCSystemHeader(Loc) << " "
                 << SourceManager.isInSystemMacro(Loc) << '\n';
  }
}

class AddMetadataConsumer : public ASTConsumer {
  StringRef m_inFile;
  Config m_cfg;
  llvm::BumpPtrAllocator m_allocator;
  llvm::StringSaver m_saver{m_allocator};

public:
  AddMetadataConsumer(StringRef InFile, Config cfg)
      : m_inFile(InFile), m_cfg(std::move(cfg)) {}

  void HandleNamespaceDecl(const NamespaceDecl *ND) {
    for (auto It = ND->decls_begin(); It != ND->decls_end(); ++It) {
      Decl *D = *It;
      HandleDecl(D);
    }
  }

  void HandleDecl(Decl *D) {
    if (const FunctionDecl *FD = dyn_cast<FunctionDecl>(D)) {
      addFunctionMetadata(FD, m_cfg, m_saver);
    } else if (const NamespaceDecl *ND = dyn_cast<NamespaceDecl>(D)) {
      HandleNamespaceDecl(ND);
    }
    HandleAllLambdaExprsInDecl(D);
  }

  // Handling of lambdas is different - lambdas are expressions => we have to
  // inspect the AST a bit more to get to the operator() of the anonymous type
  // that gets created for the closure
  struct LambdaVisitor : public RecursiveASTVisitor<LambdaVisitor> {
    Config m_cfg;
    llvm::StringSaver &m_saver;
    LambdaVisitor(Config &cfg, llvm::StringSaver &saver)
        : m_cfg(cfg), m_saver(saver) {};
    bool VisitLambdaExpr(const LambdaExpr *LE) {
      if (CXXMethodDecl *MD = LE->getCallOperator(); MD != nullptr) {
        addFunctionMetadata(MD->getAsFunction(), m_cfg, m_saver);
      }
      return true;
    }
  };

  void HandleAllLambdaExprsInDecl(Decl *D) {
    LambdaVisitor Lv(m_cfg, m_saver);
    Lv.TraverseDecl(D);
  }

  bool HandleTopLevelDecl(DeclGroupRef DG) override {
    // NOTE - HandleDecl is recursive via HandleNamespaceDecl -> if
    // NamespaceDecls are NOT acyclic, we need to setup a set of visited/handled
    // namespaces
    for (DeclGroupRef::iterator i = DG.begin(), e = DG.end(); i != e; ++i) {
      Decl *D = *i;
      HandleDecl(D);
    }
    return true;
  }

  void HandleInlineFunctionDefinition(FunctionDecl *FD) override {
    addFunctionMetadata(FD, m_cfg, m_saver);
  }
};

class AddMetadataAction : public PluginASTAction {
  Config m_cfg;

protected:
  std::unique_ptr<ASTConsumer>
  CreateASTConsumer(CompilerInstance &CI, llvm::StringRef InFile) override {
    return std::make_unique<AddMetadataConsumer>(InFile, m_cfg);
  }

  bool ParseArgs(const CompilerInstance &CI,
                 const std::vector<std::string> &args) override {
    llvm::errs() << "[AST] Arg parsing" << '\n';
    m_cfg.setDefaults();
    for (const auto &arg : args) {
      auto config = arg.rfind("--config=");
      if (config == std::string::npos) {
        llvm::errs() << "[AST] Skipping argument" << arg << '\n';
        continue;
      }
      auto cfg_path = arg.substr(config + sizeof("--config=") - 1);
      llvm::errs() << "[AST] Loading config file from " << cfg_path << '\n';
      auto cfg = Config::parseConfigFrom(cfg_path, Config::Defaultable{});
      if (!cfg) {
        llvm::errs() << "[AST] Warning: ACF frontend plugin failed to parse "
                        "configuration\n";
        CI.getDiagnostics().Report(StoredDiagnostic(
            DiagnosticsEngine::Error, 0, "Could not parse configuration file"));
        return false;
      }
      m_cfg = *cfg;
      return true;
    }
    return true;
  }

  ActionType getActionType() override { return AddBeforeMainAction; }
};
} // namespace

static FrontendPluginRegistry::Add<AddMetadataAction>
    X("LlcapMetaAdd", "Inserts metadata alongside non-system functions");
