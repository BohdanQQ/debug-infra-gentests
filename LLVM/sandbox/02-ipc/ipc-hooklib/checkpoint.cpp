#include "checkpoint.hpp"
#include "debug.hpp"
#include "shm_commons.h"
#include <criu/criu.h>
#include <cstdint>
#include <expected>
#include <fcntl.h>
#include <filesystem>
#include <format>
#include <iostream>
#include <sstream>
#include <string>
#include <sys/types.h>
#include <unistd.h>

/* NOTES:

1)
--leave-running option makes output redirection tricky - the output file shall
match the "required" size (the size @ checkpoint) - this poses issues when
writing after performing a checkpoint (possibly other output files and side
effects will pose similar issue)

2)

CRIU seems to like "being root". Simply run as root. Commadns to run:

# run the program outside a terminal
setsid target_binary < /dev/null &> program.log &

# restore
criu restore -vvvv -D ./criu-dumps/

3)

Other restoration possibility would be a "restoration program" that simply:

a) connects to the llcap-server
b) receives checkpoint inputs (dump directory, log output)
c) performs the restore

(for now I think running the above command directly is better)
*/

// adapted from:
// https://github.com/checkpoint-restore/criu/blob/criu-dev/test/others/libcriu/lib.c#L7
static std::string criu_err_msg(int ret) {
  /* NOTE: errno is set by libcriu */
  switch (ret) {
  case -EBADE:
    return "RPC has returned fail";
  case -ECONNREFUSED:
    return "Unable to connect to CRIU";
  case -ECOMM:
    return "Unable to send/recv msg to/from CRIU";
  case -EINVAL:
    return "CRIU doesn't support this type of request."
           "You should probably update CRIU";
  case -EBADMSG:
    return "Unexpected response from CRIU."
           "You should probably update CRIU";
  default:
    return "Unknown error type code."
           "You should probably update CRIU";
  }
}

/*
 * Performs checkpointing configuration
 *
 * Preconditions  - function called in a thread-safe context, socket to the
 * llcap-server is connected and everything apart from the shared memory is
 * initialized.
 *
 * Postconditions - socket to the llcap-server is connected and everything apart
 * from the shared memory is initialized (PID remains the same but requires
 * re-connection)
 *
 * Returns a pointer to the CRIU config
 */
static SResult<criu_opts *> configureCheckpoint(const std::string &dumpDir,
                                                const std::string &criuSockPath,
                                                const std::string &logId,
                                                bool shellJob) {
  using err = std::unexpected<std::string>;
  std::filesystem::path logPath;
  try {
    logPath = logId;
    // CRIU wants the log path to be just a filename
    if (!logPath.has_filename() || logId.contains('/')) {
      return err("invalid log path - part");
    }
  } catch (...) {
    return err("invalid log path - system");
  }
  if constexpr (DBG) {
    std::cerr << std::format("Configuring Checkpoint\n\tdump dir: {}\n\tcriu "
                             "socket path: {}\n\tlog id: {}",
                             dumpDir, criuSockPath, logId)
              << std::endl;
  }

  // no local_ options, either way, does not work for now
  // criu_set_unprivileged(true);

  criu_opts *opts = nullptr;
  int rv = criu_local_init_opts(&opts);
  auto errmsg = [&rv](const std::string &prefix) {
    return err(prefix + ": " + criu_err_msg(rv));
  };
  if (0 != rv) {
    return errmsg("could not init");
  }
  rv = criu_local_set_service_address(opts, criuSockPath.c_str());
  if (0 != rv) {
    return errmsg("CRIU socket");
  }
  auto dump_fd = open(dumpDir.c_str(), O_DIRECTORY);
  if (dump_fd < 0) {
    return err("Images DIR open failed");
  }
  criu_local_set_images_dir_fd(opts, dump_fd);
  rv = criu_local_set_log_file(opts, logPath.c_str());
  if (0 != rv) {
    return errmsg("Log path");
  }
  criu_local_set_shell_job(opts, shellJob);
  criu_local_set_log_level(opts, 1); // max 4
  // TODO: maybe a limiatation (nested checkpoints report errors)
  // try removing this once everything works
  criu_local_set_network_lock(opts, CRIU_NETWORK_LOCK_SKIP);
  return opts;
}

/**
 * Performs a checkpoint while calling the respective callbacks
 *
 * A callback returns an empty string to indicate success or a non-empty string
 * to indicate error (with a message)
 *
 * Expected value is a result indicating whether restore took place
 */
SResult<bool> performCheckpoint(const std::string &criuDumpDir,
                                uint64_t criuLogId, bool shellJob) {
  std::stringstream idStrStream;
  idStrStream << std::hex << criuLogId;
  auto cfgRes = configureCheckpoint(criuDumpDir, CRIU_SOCKET_PATH,
                                    idStrStream.str(), shellJob);
  if (!cfgRes) {
    return std::unexpected(cfgRes.error());
  }
  auto dumpres = criu_local_dump(*cfgRes);
  if (dumpres < 0) {
    return std::unexpected("CRIU dump failed: " + criu_err_msg(dumpres));
  }
  return dumpres > 0;
}
