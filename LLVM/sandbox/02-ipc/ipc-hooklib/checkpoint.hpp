#include <expected>
#include <string>

template <typename T> using SResult = std::expected<T, std::string>;

[[nodiscard]] SResult<bool> performCheckpoint(const std::string &criuDumpDir,
                                const std::string &criuLogId);
