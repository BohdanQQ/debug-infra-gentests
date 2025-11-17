#include "argMapping.hpp"
#include "constants.hpp"
#include "modMapping.hpp"
#include "utility.hpp"
#include <llvm/ADT/StringRef.h>
#include <llvm/Analysis/TargetLibraryInfo.h>
#include <llvm/Demangle/Demangle.h>
#include "llvm/IR/BasicBlock.h"
#include <llvm/IR/Constants.h>
#include <llvm/IR/DerivedTypes.h>
#include "llvm/IR/Function.h"
#include <llvm/IR/GlobalVariable.h>
#include "llvm/IR/Instruction.h"
#include <llvm/IR/Instructions.h>
#include <llvm/IR/LLVMContext.h>
#include "llvm/IR/Metadata.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/PassManager.h"
#include <llvm/IR/Type.h>
#include "llvm/Pass.h"
#include <llvm/Passes/OptimizationLevel.h>
#include <memory>
#include <utility>

namespace common {

// helper container for IR-level constants
// these are used in calls to hooklib functions which accept module and function
// IDs (the IDs are inserted during the instrumentation as constants)
struct SFnUidConstants {
  llvm::ConstantInt *module;
  llvm::ConstantInt *function;

  // creates the constant pair inside the supplied module
  static SFnUidConstants getModFunIdConstants(llcap::ModuleId ModuleIntId,
                                              llvm::Module &M,
                                              llcap::FunctionId FunctionIntId) {
    static_assert(sizeof(llcap::FunctionId) ==
                  4); // this does not imply incorrectness, just that everything
    // must be checked
    auto *FnIdConstant = llvm::ConstantInt::get(
        M.getContext(), llvm::APInt(llcap::FUNID_BITSIZE, FunctionIntId));
    static_assert(sizeof(llcap::ModuleId) == 4);
    auto *ModIdConstant = llvm::ConstantInt::get(
        M.getContext(), llvm::APInt(llcap::MODID_BITSIZE, ModuleIntId));
    return {.module = ModIdConstant, .function = FnIdConstant};
  }
};
}; // namespace common

class Instrumentation {
public:
  // a dumb wrapper around some src/pass.cpp "args" namespace items
  // which are required by the two instrumentation modes
  struct Config {
    bool useMangledNames{false};
    std::string modMapsDir;
    bool performFnExitInstrumentation{false};
    std::string SelectionPath;
  };

  virtual ~Instrumentation() = default;

  [[nodiscard]] bool ready() const { return m_ready; }

  virtual void instrument() = 0;
  // saves artifacts and deinitializes the instrumentation
  virtual bool finish() = 0;

protected:
  // module being instrumented
  llvm::Module &m_module;
  IdxMappingInfo m_idxInfo;
  // error state flag
  bool m_ready{false};
  // skip the module, instrument() shall not instrument
  bool m_skip{false};
  std::shared_ptr<const Config> m_cfg;
  Instrumentation(llvm::Module &M, std::shared_ptr<const Config> Cfg);
};

class FunctionEntryInstrumentation : public Instrumentation {
private:
  FunctionIDMapper m_fnIdMap;

public:
  FunctionEntryInstrumentation(llvm::Module &M,
                               std::shared_ptr<const Config> Cfg)
      : Instrumentation(M, std::move(Cfg)), m_fnIdMap(M.getModuleIdentifier()) {
    m_ready = true;
  }

  void instrument() override;

  bool finish() override;
};

class ArgumentInstrumentation : public Instrumentation {
public:
  struct IFunctionEndStrategy;
  struct IFunctionEndStrategy {
    struct InstrParams {
      common::SFnUidConstants Constants;
      llvm::Value* TestingFlag; 
    };
    // performs function end instrumentation
    // returns false on error
    virtual bool operator()(llvm::Module &, llvm::Function &, const InstrParams&) = 0;
  };
  struct NoOpEndStrategy : IFunctionEndStrategy {
    bool operator()(llvm::Module & /*unused*/, llvm::Function & /*unused*/, const InstrParams& /* unused */) override {
      return true;
    }
  };

  struct StopTestOnFnExitStrategy : IFunctionEndStrategy {
    bool operator()(llvm::Module &Mod, llvm::Function &Fn, const InstrParams& Params) override;
  };

private:
  std::unique_ptr<IFunctionEndStrategy> m_fnEndStrategy;
  llcap::ModuleId m_moduleId;
  std::map<std::string, llcap::FunctionId> m_tracedFns;

public:
  ArgumentInstrumentation(llvm::Module &M, std::shared_ptr<const Config> Cfg);

  void instrument() override;

  bool finish() override { return true; }
};
