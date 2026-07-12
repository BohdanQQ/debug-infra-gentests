#include "modMapping.hpp"

#include "argMapping.hpp"
#include "constants.hpp"
#include "typeAlias.hpp"
#include <exception>
#include <filesystem>
#include <llvm/ADT/StringRef.h>
#include "llvm/IR/Function.h"
#include "llvm/Support/SHA256.h"
#include "llvm/Support/raw_ostream.h"
#include <cassert>
#include <fstream>
#include <iomanip>
#include <ranges>
#include <sstream>

namespace {

// coverts the shorter hash value to usable string and numerical representations
Pair<Str, llcap::FunctionId>
hashToUsableTypes(const FunctionIDMapper::ShortHashT Collapsed) {
  std::stringstream SStream;
  llcap::FunctionId NumResult;
  static_assert(sizeof(llcap::FunctionId) == Collapsed.size());

  // we want hexadceimal string representation (2 -> 02, 63 -> FF)
  SStream << std::hex << std::setfill('0');
  for (auto &&Byte : Collapsed) {
    // accumulate bytes into NumResult (shift resutl + add the byte, repeat)
    NumResult =
        (NumResult << sizeof(Byte) *
                          llcap::BYTE_BITS); // shift first to avoid overshift
                                             // in the last iteration
    NumResult += Byte;

    SStream << std::setw(llcap::BYTE_ENCODING_SIZE)
            << static_cast<unsigned int>(Byte);
  }

  return {SStream.str(), NumResult};
}

FunctionIDMapper::ShortHashT
collapseHash(const FunctionIDMapper::FullHashT &Data) {
  static_assert(FunctionIDMapper::SHA256_BYTES % sizeof(llcap::ModuleId) == 0,
                "invalid hash size or id size");
  FunctionIDMapper::ShortHashT Res = {0};

  // iterate in steps (take 4 bytes from Data, xor into Res, take another 4
  // Bytes, xor into Res, ...)

  for (size_t DataIdx = 0; DataIdx < FunctionIDMapper::SHA256_BYTES;
       DataIdx += Res.size()) {
    for (size_t ResIdx = 0; ResIdx < Res.size(); ResIdx += 1) {
      assert(DataIdx + ResIdx < Data.size() && "Wrong indicies");
      Res[ResIdx] = (Res[ResIdx] ^ Data[DataIdx + ResIdx]);
    }
  }

  return Res;
}

// encodes the module mapping file of an LLVM module
class ModuleMappingEncoding {
  char m_sep{'\n'};
  char m_innserSep{'\0'};
  std::ofstream m_file;
  bool m_failed{true};

  void setFailed() { m_failed = true; };
  void encodeModmapFnEntry(std::ofstream &OutStream, char Sep,
                           const std::string &FnName,
                           const llcap::FunctionId FnId,
                           std::ranges::input_range auto &&ArgSizes) {
    OutStream << FnName << Sep << FnId << Sep;
    OutStream << ArgSizes.size(); // if zero, nothing follows (only the outer
                                  // separator)

    // otherwise a list of "size" values follows
    for (const LlcapSizeType &Type : ArgSizes) {
      OutStream << Sep << std::underlying_type_t<LlcapSizeType>(Type);
    }
  }

public:
  ModuleMappingEncoding(const std::string &MapsDirectory,
                        const std::string &FileName,
                        const std::string &ModuleName) {
    if (!MapsDirectory.empty()) {
      try {
        std::filesystem::create_directories(MapsDirectory);
      } catch (std::exception& Err) {
        llvm::errs() << "Failed modmaps dir createion:" << Err.what() << '\n';
        setFailed();
        return;
      }
    }

    Str Path = MapsDirectory + '/' + FileName;
    if (std::filesystem::exists(Path)) {
      llvm::errs() << "Module ID hash collision! Path:" << Path << '\n';
      setFailed();
      return;
    }

    m_file = std::ofstream(Path);
    if (!m_file.is_open() || !m_file) {
      llvm::errs() << "Could not open function ID map. Path: " << Path << '\n';
      setFailed();
      return;
    }

    m_file << ModuleName << m_sep;
    // plain m_file just does not work?
    m_failed = !m_file;
  }

  static bool finish(ModuleMappingEncoding &&Self) {
    auto Local = std::move(Self);
    if (!Local.m_failed) {
      Local.m_file.flush();
    }

    return !!Local.m_file;
  }

  bool ready() const { return !m_failed; }

  bool encodeFunction(const std::string &FuncName, llcap::FunctionId FuncId,
                      std::ranges::input_range auto &&ArgSizes) {
    if (!ready()) {
      return false;
    }

    encodeModmapFnEntry(m_file, m_innserSep, FuncName, FuncId, ArgSizes);
    m_file << m_sep;

    if (!m_file) {
      llvm::errs() << "Could not add function " << '\n';
      setFailed();
    }
    return !!m_file;
  }
};

} // namespace

FunctionIDMapper::FunctionIDMapper(const Str &ModuleId)
    : m_fullModuleId(ModuleId) {
  llvm::SHA256 Hash;
  Hash.init();
  Hash.update(llvm::StringRef(ModuleId));
  std::stringstream SStream;
  SStream << std::hex << std::setfill('0');
  // we construct 4-byte module ID by hashing the LLVM-supplied and shortening
  // its
  const auto Collapsed = collapseHash(Hash.result());

  auto &&[StringRepr, NumRepr] = hashToUsableTypes(Collapsed);
  m_outFileName = StringRepr;
  m_moduleIntId = NumRepr;
}

llcap::FunctionId
FunctionIDMapper::addFunction(const Str &FnInfo,
                              ClangMetadataToLLVMArgumentMapping &Mapping) {
  auto Inserted = m_functionIdGenerator++;
  m_functionIds.emplace_back(FnInfo, Inserted, Mapping.getArgumentSizeTypes());
  return Inserted;
}

bool FunctionIDMapper::flush(FunctionIDMapper &&Mapper, const Str &TargetDir) {
  auto LocalMapper = std::move(Mapper);
  ModuleMappingEncoding Encoding(TargetDir, LocalMapper.getModuleMapId(),
                                 LocalMapper.getFullModuleId());

  for (auto &&[FnName, Id, Sizes] : LocalMapper.m_functionIds) {
    if (!Encoding.ready()) {
      llvm::errs() << "Encoding failed\n";
      return false;
    }

    Encoding.encodeFunction(FnName, Id,
                            Sizes | std::views::transform([](const auto &Pr) {
                              return Pr.second;
                            }));
  }

  if (!Encoding.ready()) {
    llvm::errs() << "Encoding failed @ end\n";
    return false;
  }
  return ModuleMappingEncoding::finish(std::move(Encoding));
}
