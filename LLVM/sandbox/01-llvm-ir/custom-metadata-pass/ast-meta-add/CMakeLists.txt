# If we don't need RTTI or EH, there's no reason to export anything
# from the plugin.
if( NOT MSVC ) # MSVC mangles symbols differently, and
               # AstMetaAdd.export contains C++ symbols.
  if( NOT LLVM_REQUIRES_RTTI )
    if( NOT LLVM_REQUIRES_EH )
      set(LLVM_EXPORTED_SYMBOL_FILE ${CMAKE_CURRENT_SOURCE_DIR}/AstMetaAdd.exports)
    endif()
  endif()
endif()
set(CMAKE_CXX_STANDARD 20)
add_llvm_library(AstMetaAdd MODULE AstMetaAdd.cpp PLUGIN_TOOL clang)
target_include_directories(AstMetaAdd PUBLIC ./pass-commons/)


if(WIN32 OR CYGWIN)
  set(LLVM_LINK_COMPONENTS
    Support
  )
  clang_target_link_libraries(AstMetaAdd PRIVATE
    clangAST
    clangBasic
    clangFrontend
    )
endif()
