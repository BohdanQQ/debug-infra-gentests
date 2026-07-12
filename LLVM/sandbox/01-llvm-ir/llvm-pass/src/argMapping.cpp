#include "argMapping.hpp"
#include "../../custom-metadata-pass/ast-meta-add/llvm-metadata.h"
#include "typeAlias.hpp"
#include "typeids.h"
#include "utility.hpp"
#include "llvm/ADT/StringRef.h"
#include "llvm/IR/Metadata.h"
#include "llvm/IR/Module.h"
#include "llvm/Support/raw_ostream.h"
#include <algorithm>
#include <cassert>
#include <cstddef>
#include <iterator>
#include <ranges>
#include <type_traits>
#include <utility>
#include <vector>

namespace {

llvm::MDString *getStringOperand(const llvm::MDNode *N, unsigned int I) {
  if (N == nullptr || N->getNumOperands() <= I) {
    return nullptr;
  }
  return dyn_cast_if_present<llvm::MDString>(N->getOperand(I));
}

// extracts string metadat from a metadata node
Maybe<Str> getMetadataStrVal(llvm::NamedMDNode *Node) {
  if (Node == nullptr || Node->getNumOperands() == 0) {
    return NONE;
  }
  llvm::MDNode *Inner = Node->getOperand(0);

  if (auto *Op = getStringOperand(Inner, 0); Op != nullptr) {
    return Op->getString().str();
  }
  return NONE;
}
// parsing the argument mapping list (0-1#1-1#2-1#4294967295-0), see
// initParseArgMapping
bool parseArgMappingList(size_t LlArgCnt, size_t AstArgCnt, llvm::StringRef Str,
                         std::vector<size_t> &OutArgStarts, IdxMappingInfo Seps,
                         std::vector<size_t> &OutArgSizes) {
  // it does not make sense to parse more arguments than present in LLVM IR
  size_t Count = std::min(LlArgCnt, AstArgCnt);

  llvm::SmallVector<llvm::StringRef> Split;
  Str.split(Split, Seps.group, -1, true);

  if (Split.size() < Count) {
    llvm::errs() << "Malformed metadata - arg mapping list size ( " << Count
                 << " )\n";
    return false;
  }

  using ParseResT = Maybe<Pair<size_t, size_t>>;

  // creates a sequence of (index, span) of the llvm parguments
  // (one clang argument can span multiple llvm ir arguments)
  auto Parsed =
      std::ranges::take_view(Split, Count) |
      std::ranges::views::transform([Seps](llvm::StringRef S) -> ParseResT {
        llvm::SmallVector<llvm::StringRef> PairSplit;
        S.split(PairSplit, Seps.argParamPair);

        if (PairSplit.size() != 2) {
          llvm::errs() << "Malformed metadata - mapping pair\n";
          return NONE;
        }

        auto LlIdxStart = tryParse<size_t>(PairSplit[0]);
        auto LlArgSpan = tryParse<size_t>(PairSplit[1]);

        if (!LlIdxStart || !LlArgSpan) {
          llvm::errs() << "Malformed metadata - pair values";
          return NONE;
        }

        return std::make_pair(*LlIdxStart, *LlArgSpan);
      });

  auto Failed = std::ranges::any_of(
      Parsed, [](const ParseResT &V) { return !V.has_value(); });
  if (Failed) {
    return false;
  }

  // invalid Seps.invalidIndexValue is used for arguments that cannot be
  // instrumented (mostly this is just the result of the LLVM-clang mapping
  // creating "ghost" arguments)
  auto Filtered =
      std::ranges::views::transform(Parsed,
                                    [](const ParseResT &V) { return *V; }) |
      std::ranges::views::filter([Seps](const Pair<size_t, size_t> &V) {
        return V.first != Seps.invalidIndexValue;
      });

  for (auto &&[IdxStart, ArgSpan] : Filtered) {
    OutArgStarts.push_back(IdxStart);
    OutArgSizes.push_back(ArgSpan);
  }

  return true;
}

// parsing metadata stirng in the form
// 3 4 0-1#1-1#2-1#4294967295-0
// in the form
// LLVM-args AST-args LIST(size = AST-args)
// where LIST maps AST index to a pair (LLVM start index, LLVM arg span)
// Seps (in this case) should be { primary = ' ', group = '#', pair = '-',
// invalidIdx = 4294967295 }
bool parseArgMapping(llvm::MDNode *ArgMappingMetadata,
                     std::vector<size_t> &OutArgStarts, IdxMappingInfo Seps,
                     std::vector<size_t> &OutArgSizes) {
  // "0 0 " is the minimal valid metadata string
  constexpr auto MIN_MTV_SIZE = sizeof("0 0 ") - 1;
  llvm::StringRef MetaValue;

  IF_DEBUG if (ArgMappingMetadata != nullptr) {
    ArgMappingMetadata->dumpTree();
  }

  // obtain the value of metadata
  if (auto *Op = getStringOperand(ArgMappingMetadata, 0); Op != nullptr) {
    MetaValue = Op->getString();
    if (MetaValue.size() < MIN_MTV_SIZE) {
      llvm::errs() << "Malformed metadata - size\n";
      return false;
    }
  } else {
    llvm::errs() << "Missing string value\n";
    return false;
  }

  llvm::SmallVector<llvm::StringRef>
      Split; // split by primary separator (see above)
  MetaValue.split(Split, Seps.primary, -1, true);

  if (Split.size() != 3) {
    llvm::errs() << "Malformed metadata - primary split\n";
    return false;
  }

  Vec<size_t> ResStartLLIdxMap;
  Vec<size_t> ResLLArgSize;
  Arr<size_t, 2> ArgCounts;

  assert(ArgCounts.size() < Split.size());

  // we expect 2 numbers here - the LLVM and clang count of arguments
  auto Parsed = std::ranges::take_view(Split, ArgCounts.size()) |
                std::ranges::views::transform(
                    [](llvm::StringRef S) { return tryParse<size_t>(S); });

  size_t Idx = 0;
  // TODO if switched to C++23, use zip+iota or enumerate range
  for (auto MbParsed : Parsed) {
    if (!MbParsed) {
      llvm::errs() << "Malformed metadata - primary split at " << Idx << '\n';
      return false;
    }
    // ranges::take above should ensure correctness, .at just to be sure
    ArgCounts.at(Idx) = *MbParsed;
    Idx++;
  }

  auto [LlvmArgCount, AstArgCount] = ArgCounts;

  if (AstArgCount == 0) {
    return true;
  }
  return parseArgMappingList(LlvmArgCount, AstArgCount, Split[2], OutArgStarts,
                             Seps, OutArgSizes);
}

// parses the metadata that encode the indicies of a custom type
//
// indicies are separated by Sep and are base-10 string representations of
// numbers
Vec<size_t> parseCustTypeIndicies(llvm::StringRef MetaValue,
                                  bool IsInstanceMember, char Sep) {
  llvm::SmallVector<llvm::StringRef> Split;
  // use ssize to be able to indicate parsing failure
  Vec<ssize_t> Res;

  MetaValue.split(Split, Sep, -1, false);

  std::ranges::transform(Split, std::back_inserter(Res), [](llvm::StringRef S) {
    return valOrDefault(tryParse<long long>(S), -1LL);
  });

  // filter out non-numbers and check there are none
  auto [EndRes, _] =
      std::ranges::remove_if(Res, [](ssize_t I) { return I == -1; });

  Vec<size_t> RealRes;
  assert(std::distance(Res.begin(), EndRes) >= 0);
  RealRes.reserve(static_cast<size_t>(std::distance(Res.begin(), EndRes)));

  // transform the ssize to size
  // adding +1 here based on IsInstance member because the indicies
  // DO NOT account for the "this" pointer (passed as the first extra argument)
  std::transform(Res.begin(), EndRes, std::back_inserter(RealRes),
                 [IsInstanceMember](ssize_t I) {
                   assert(I >= 0);
                   return static_cast<size_t>(I) + (IsInstanceMember ? 1 : 0);
                 });

  return RealRes;
}

// obtains the positions of custom types by inspecting the specified MetadataKey
// attached to the function Fn
Maybe<Vec<size_t>> getCustomTypeIndicies(llvm::StringRef MetadataKey,
                                         const llvm::Function &Fn,
                                         bool IsInstanceMember,
                                         IdxMappingInfo Info) {
  if (llvm::MDNode *N = Fn.getMetadata(MetadataKey)) {
    if (N->getNumOperands() == 0) {
      llvm::errs()
          << "Warning - unexpected string metadata node with NO operands!\n";
      return NONE;
    }

    if (auto *Op = getStringOperand(N, 0); Op != nullptr) {
      return parseCustTypeIndicies(Op->getString(), IsInstanceMember,
                                   Info.custom);
    }

    llvm::errs()
        << "Warning - unexpected string metadata node with non-MDString "
           "0th operand!\n";
  } else {
    DEBUG_LOG << "No meta key " << MetadataKey << " found\n";
  }
  return NONE;
}

} // namespace

std::optional<IdxMappingInfo> IdxMappingInfo::parseFromModule(llvm::Module &M) {
  // metadata under the these keys used are inserted in the patched llvm
  constexpr Arr<char, 27> PARSE_GUIDE_META_KEY{"LLCAP-CLANG-LLVM-MAP-PRSGD"};
  constexpr Arr<char, 31> INVL_IDX_META_KEY{"LLCAP-CLANG-LLVM-MAP-INVLD-IDX"};
  IdxMappingInfo Result;

  // try get string from metadata (metadata are key-value pairs)
  if (auto MbStr =
          getMetadataStrVal(M.getNamedMetadata(PARSE_GUIDE_META_KEY.data()));
      MbStr && MbStr->size() == 3) {
    // this metadata contains the separators used in index mapping
    Result.primary = MbStr->at(0);
    Result.group = MbStr->at(1);
    Result.argParamPair = MbStr->at(2);
    Result.custom = LLCAP_SINGLECHAR_SEP;
  } else {
    llvm::errs() << "Module missing parse guide\n";
    return NONE;
  }

  Result.invalidIndexValue = std::numeric_limits<uint64_t>::max();
  if (auto MbStr =
          getMetadataStrVal(M.getNamedMetadata(INVL_IDX_META_KEY.data()));
      MbStr) {
    // this metadata contains the "invalid value" constant
    if (auto Parsed = tryParse<u64>(*MbStr); Parsed) {
      Result.invalidIndexValue = *Parsed;
    } else {
      llvm::errs() << "Module invalid index hint could not be parsed\n";
    }
  } else {
    llvm::errs() << "Module missing invalid index hint\n";
  }

  DEBUG_LOG << "Module Index Map parsing OK\n";
  return Result;
}

ClangMetadataToLLVMArgumentMapping::ClangMetadataToLLVMArgumentMapping(
    llvm::Function &Fn, IdxMappingInfo Seps)
    : m_fn(Fn), m_seps(Seps) {
  IF_DEBUG { llvm::errs() << Fn.getName() << ": \n"; }
  // metadata under this key are inserted in the patched llvm
  // (the literal is part of the patch)
  assert(parseArgMapping(Fn.getMetadata("LLCAP-CLANG-LLVM-MAP-DATA"),
                         m_astArgIdxToLlvmArgIdx, m_seps,
                         m_astArgIdxToLlvmArgLen));

  m_instanceMember =
      m_fn.getMetadata(llvm::StringRef(LLCAP_THIS_PTR_MARKER_KEY)) != nullptr;
}

bool ClangMetadataToLLVMArgumentMapping::registerCustomTypeIndicies(
    const llvm::StringRef &MetadataKey, LlcapSizeType AssociatedSize) {
  auto CustTypeIdcs =
      getCustomTypeIndicies(MetadataKey, m_fn, m_instanceMember, m_seps);
  IF_DEBUG {
    llvm::errs() << "Custom type idxs: ";
    if (CustTypeIdcs) {
      for (auto &&I : *CustTypeIdcs) {
        llvm::errs() << I << " ";
      }
    }
    llvm::errs() << '\n';
  }
  std::optional<std::set<size_t>> CustTypeIdxMap =
      CustTypeIdcs
          ? std::optional(std::set(CustTypeIdcs->begin(), CustTypeIdcs->end()))
          : std::nullopt;
  if (CustTypeIdxMap) {
    m_typeIndicies[MetadataKey] =
        std::make_pair(AssociatedSize, *CustTypeIdxMap);
    return true;
  }
  return false;
}

bool ClangMetadataToLLVMArgumentMapping::llvmArgNoMatches(
    size_t LlvmArgNo, const llvm::StringRef &MetadataKey) const {
  IF_DEBUG {
    llvm::errs() << "Checking argument idx match for argument with llvm idx "
                 << LlvmArgNo << '\n'
                 << "Custom type idxs " << MetadataKey << ": ";
  }
  if (!m_typeIndicies.contains(MetadataKey)) {
    // this function does not have any arguments that registered with this
    // metadata key
    DEBUG_LOG << "none\n";
    return false;
  }

  const auto &[Sz, CustTypes] = m_typeIndicies.at(MetadataKey);
  IF_DEBUG { // ignore this when reading for the first time
    for (const auto &I : CustTypes) {
      llvm::errs() << I << " ";
    }
    llvm::errs() << "\nCustom type size enum: "
                 << std::underlying_type_t<LlcapSizeType>(Sz) << '\n';

    llvm::errs() << "Starts: ";

    for (const auto &I : m_astArgIdxToLlvmArgIdx) {
      llvm::errs() << I << " ";
    }

    llvm::errs() << "\nSizes : ";

    for (const auto &I : m_astArgIdxToLlvmArgLen) {
      llvm::errs() << I << " ";
    }
    llvm::errs() << '\n';
  }

  // CustTypes contains AST indicies matching the custom type in this function
  // example: if the metadata key corresponds to type Tauri and the m_fn is
  // void foo(Tauri& x, int b, Tauri& z), the CustTypes will be [0, 2]
  auto Res = std::ranges::any_of(CustTypes, [&](size_t AstIndex) {
    // m_astArgIdxToLlvmArgIdx then maps the AST index the LLVM IR argument
    // position and if it matches, the argument at this LLVM IR position must be
    // "Tauri" (or the custom type that corresponds to the metadata key)
    return m_astArgIdxToLlvmArgIdx.at(AstIndex) == LlvmArgNo;
  });

  return Res;
}

Vec<Pair<size_t, LlcapSizeType>>
ClangMetadataToLLVMArgumentMapping::getArgumentSizeTypes() const {
  Vec<Pair<size_t, LlcapSizeType>> Res;
  Res.reserve(m_fn.arg_size());

  for (unsigned int I = 0; I < m_fn.arg_size(); ++I) {
    Res.emplace_back(I, llvmArgNoSizeType(I));
  }
  return Res;
}

LlcapSizeType ClangMetadataToLLVMArgumentMapping::llvmArgNoSizeType(
    unsigned int LlvmArgNo) const {
  LlcapSizeType Res = LlcapSizeType::LLSZ_INVALID;

  // first check for custom types
  for (auto &&[CustTName, Desc] : m_typeIndicies) {
    auto &&[SizeType, _] = Desc;
    const auto Sz = SizeType;

    if (Sz != LlcapSizeType::LLSZ_INVALID &&
        llvmArgNoMatches(LlvmArgNo, CustTName)) {
      if (Res == LlcapSizeType::LLSZ_INVALID) {
        Res = Sz;
      } else {
        llvm::errs() << "Serious error!!!\nLLVM Arg Number" << LlvmArgNo
                     << " of function " << m_fn.getName()
                     << " found to be associated with > 1 custom size "
                        "types!\nThe current one is: "
                     << CustTName << "\nThis should not happen!";
        exit(1);
      }
    }
  }

  if (Res != LlcapSizeType::LLSZ_INVALID) {
    return Res;
  }

  const auto *Arg = m_fn.getArg(LlvmArgNo);
  const auto *ArgT = Arg->getType();

  // then try float/double
  if (ArgT->isFloatTy()) {
    return LlcapSizeType::LLSZ_32;
  }

  if (ArgT->isDoubleTy()) {
    return LlcapSizeType::LLSZ_64;
  }

  // then all other primitive-sized types
  const Arr<Pair<unsigned int, LlcapSizeType>, 4> IntTypeSizeMap = {
      Pair{8, LlcapSizeType::LLSZ_8},
      {16, LlcapSizeType::LLSZ_16},
      {32, LlcapSizeType::LLSZ_32},
      {64, LlcapSizeType::LLSZ_64}};

  for (auto &&[I, S] : IntTypeSizeMap) {
    if (ArgT->isIntegerTy(I)) {
      return S;
    }
  }

  return LlcapSizeType::LLSZ_INVALID;
}