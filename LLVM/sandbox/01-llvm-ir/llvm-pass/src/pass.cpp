#include "llvm/Pass.h"
#include "argMapping.hpp"
#include "instrumentation.hpp"
#include "typeAlias.hpp"
#include "utility.hpp"
#include "llvm/ADT/APInt.h"
#include <llvm/ADT/StringRef.h>
#include <llvm/Analysis/TargetLibraryInfo.h>
#include <llvm/Demangle/Demangle.h>
#include "llvm/IR/BasicBlock.h"
#include <llvm/IR/Constants.h>
#include <llvm/IR/DerivedTypes.h>
#include "llvm/IR/Function.h"
#include <llvm/IR/GlobalVariable.h>
#include "llvm/IR/Instruction.h"
#include <llvm/IR/LLVMContext.h>
#include "llvm/IR/Metadata.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/PassManager.h"
#include <llvm/IR/Type.h>
#include <llvm/Passes/OptimizationLevel.h>
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Passes/PassPlugin.h"
#include "llvm/Support/CommandLine.h"
#include "llvm/Support/raw_ostream.h"
#include <string>

using namespace llvm;

namespace {
// arguments available for plugin behavior customization
namespace args {
enum class InstrumentationType : u8 { Call, Arg };

// -mllvm -llcap-filter-by-mangled
cl::opt<bool>
    MangleFilter("llcap-filter-by-mangled",
                 cl::desc("Filter functions by their mangled names instead of "
                          "their position within the AST"));
// -mllvm -llcap-verbose
cl::opt<bool> Verbose("llcap-verbose", cl::desc("Verbose output"));
// -mllvm -llcap-debug
cl::opt<bool> Debug("llcap-debug", cl::desc("Debugging output"));
// -mllvm -llcap-mapdir
cl::opt<std::string> MapFilesDirectory(
    "llcap-mapdir",
    cl::desc("Output directory for function ID maps (default: module-maps)"));
// -mllvm -Call
// -mllvm -Arg
cl::opt<InstrumentationType> InstrumentationType(
    cl::desc("Choose instrumentation type:"),
    cl::values(
        llvm ::cl ::OptionEnumValue{.Name = "Call",
                                    .Value = int(InstrumentationType::Call),
                                    .Description = "Call tracing"},
        llvm ::cl ::OptionEnumValue{
            .Name = "Arg",
            .Value = int(InstrumentationType::Arg),
            .Description = "Argument tracing/testing"}));

// -mllvm -llcap-fn-targets-file
cl::opt<std::string>
    TargetsFilePath("llcap-fn-targets-file",
                    cl::desc("Path to a file containing the module IDs and "
                             "function IDs of functions to be instrumented"));

// -mllvm -llcap-fn-target-regex
cl::opt<std::string> TargetFnRegex(
    "llcap-fn-target-regex",
    cl::desc("Regular expression specifying the fully qualified function name "
             "to instrument for argument capture/testing"));

// -mllvm -llcap-instrument-fn-exit
cl::opt<bool> InstrumentFnExit(
    "llcap-instrument-fn-exit",
    cl::desc("Whether to generate ret/resume function exit hooks"));
} // namespace args

bool instrumentArgs() {
  return args::InstrumentationType.getValue() == args::InstrumentationType::Arg;
}

struct InstrumentationPass : public PassInfoMixin<InstrumentationPass> {

  PreservedAnalyses run(Module &M, [[maybe_unused]] ModuleAnalysisManager &AM) {
    verbose(args::Verbose.getValue(), args::Verbose.getValue());
    debug(args::Debug.getValue(), args::Debug.getValue());

    VERBOSE_LOG << "Running pass on module " << M.getModuleIdentifier() << "\n";

    auto Cfg = std::make_shared<Instrumentation::Config>();
    Cfg->useMangledNames = args::MangleFilter;
    Cfg->modMapsDir = args::MapFilesDirectory.hasArgStr()
                          ? Maybe<Str>(args::MapFilesDirectory.getValue())
                          : "module-maps";
    Cfg->performFnExitInstrumentation = args::InstrumentFnExit.getValue();
    Cfg->selectingByRegex = args::TargetFnRegex.hasArgStr() &&
                            !args::TargetFnRegex.getValue().empty();
    Cfg->SelectionStr = Cfg->selectingByRegex
                            ? args::TargetFnRegex.getValue()
                            : args::TargetsFilePath.getValue();
    if (instrumentArgs()) {
      VERBOSE_LOG << "Instrumenting args...\n";
      ArgumentInstrumentation Work(M, Cfg);

      if (!Work.ready()) {
        errs() << "Failed to parse instrumentation targets\n";
        return PreservedAnalyses::none();
      }

      Work.instrument();

      if (!Work.finish()) {
        errs() << "Instrumentation failed - ArgumentInstrumentation!\n";
        exit(1);
      }
    } else {
      VERBOSE_LOG << "Instrumenting fn entry...\n";
      FunctionEntryInstrumentation Work(M, Cfg);

      if (!Work.ready()) {
        errs() << "Failed to parse instrumentation targets\n";
        return PreservedAnalyses::none();
      }

      Work.instrument();

      if (!Work.finish()) {
        errs() << "Instrumentation failed - FunctionEntryInstrumentation!\n";
        errs()
            << "FunctionEntryInstrumentation requires -mllvm -llcap-mapdir DIR "
               "directory!\n";
        exit(1);
      }
    }
    VERBOSE_LOG << "Instrumentation DONE!\n";

    return PreservedAnalyses::none();
  }
};
} // namespace

namespace {
// Register the pass for the new pass manager
llvm::PassPluginLibraryInfo getInsertFunctionCallPassPluginInfo() {
  const auto Callback = [](PassBuilder &PB) {
    PB.registerPipelineStartEPCallback(
        [](ModulePassManager &MPM, OptimizationLevel) {
          MPM.addPass(InstrumentationPass());
          return true;
        });
  };
  return {.APIVersion = LLVM_PLUGIN_API_VERSION,
          .PluginName = "Llcap-pass",
          .PluginVersion = "0.1.0",
          .RegisterPassBuilderCallbacks = Callback};
}
} // namespace

// Register the plugin
extern "C" LLVM_ATTRIBUTE_WEAK PassPluginLibraryInfo llvmGetPassPluginInfo() {
  return getInsertFunctionCallPassPluginInfo();
}