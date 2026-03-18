#include <cstdint>
#include <expected>
#include <string>

template <typename T> using SResult = std::expected<T, std::string>;

[[nodiscard]] SResult<bool> performCheckpoint(const std::string &criuDumpDir,
                                              uint64_t criuLogId);
