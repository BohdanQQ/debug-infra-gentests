#ifndef LLCAP_ONESHOT_CHNL
#define LLCAP_ONESHOT_CHNL
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// reads data of the specified size from a oneshot "channel" into the target
// address
bool oneshot_shm_read(const char *data_sem_name, const char *ack_sem_name,
                      const char *shm_name, const char *shm_size_name, bool (handler)(const void* source, uint32_t size), uint32_t max_size);


#ifdef __cplusplus
}
#endif
#endif // LLCAP_ONESHOT_CHNL
