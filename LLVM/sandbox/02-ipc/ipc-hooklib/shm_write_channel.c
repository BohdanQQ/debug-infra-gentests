#include "shm_write_channel.h"
#include "shm_util.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h> /* For O_* constants */
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h> /* For mode constants */
#include <unistd.h>

/*
Shared memory buffering & synchronization

In this architecture, we expect to have N buffers that are being filled and
processed in circular fashion, starting from 0. We expect a SINGLE producer and
a SINGLE consumer to coordinate their buffers.

Producer starts filling buffer 0. When it deems the buffer full, it uses 2
semaphores to:
1. signal on a "full" semaphore there is a FULL buffer to be handled
2. wait on a "free" semaphore for a FREE buffer
3. after wake-up, starts filling another buffer

Consumer starts by waiting for the "full" semaphore. As soon as it is woken up,
it:
1. processes the buffer
2. signals "free" semaphore to indicate that number of free buffers has
increased
3. waits on the "full" semaphore again

Format of a buffer:

Bytes 0-3: 4B length of payload starting from byte 4 (after this field)
Bytes 4+ : payload from the foreign process
*/

/*
Special considerations w.r.t. program **crashing**.
  - semaphore & memory should be unregistered by the OS
Termination protocol: see the termination_sequence_raw function
*/

static const unsigned long MAX_NAME_LEN = 251; // including the null terminator
static const char *CHANNEL_NAME_BASE = "/llcap";

// allocates a channel-related resource name in the form "%s-%s-%s-%s" for the
// first 4 arguments not that for the semaphore and shared-memory resources,
// they must start with /
static bool alloc_name(const char *name_base, const char *name,
                       const char *type_id, const char *postfix, char **out) {
  // must ensure out is written only if allocation is successful!

#define FORMAT "%s-%s-%s-%s" // e.g. /llcap-TEST-01-meta-semfree
  unsigned long to_alloc = strlen(name_base) + 1 + strlen(name) + 1 +
                           strlen(type_id) + 1 + strlen(postfix) +
                           1; // null term

  if (to_alloc > MAX_NAME_LEN || to_alloc == 0) {
    printf("to_alloc invalid : %lu", to_alloc);
    return false;
  }

  char *buffer = (char *)malloc(to_alloc);
  if (buffer == NULL) {
    printf("error buff null\n");
    return false;
  }

  int printed =
      snprintf(buffer, to_alloc, FORMAT, name_base, name, type_id, postfix);

  if (printed <= 0 || (unsigned long)printed != to_alloc - 1) {
    free(buffer);
    printf("printed count invalid, expecting %lu, got %d\n", to_alloc, printed);
    return false;
  }

  *out = buffer;
  return true;
}

static void dealloc_channel_infra_names(ChannelNames *names) {
  free(names->name_sem_free);
  names->name_sem_free = NULL;
  free(names->name_sem_full);
  names->name_sem_full = NULL;
  free(names->name_buff_mem);
  names->name_buff_mem = NULL;
}

static bool alloc_channel_infra_names(const char *name_base,
                                      const char *channel_name,
                                      const char *type, ChannelNames *target) {
  // ensure target is freeable later (no junk -> free(NULL) -> nop)
  target->name_sem_free = NULL;
  target->name_sem_full = NULL;
  target->name_buff_mem = NULL;

  if (!alloc_name(name_base, channel_name, type, "semfree",
                  &target->name_sem_free)) {
    goto fail;
  }
  if (!alloc_name(name_base, channel_name, type, "semfull",
                  &target->name_sem_full)) {
    goto fail;
  }
  if (!alloc_name(name_base, channel_name, type, "buffmem",
                  &target->name_buff_mem)) {
    goto fail;
  }

  return true;
fail:
  dealloc_channel_infra_names(target);
  return false;
}

static bool semaphore_close(sem_t *sem, const char *name) {
  if (sem != SEM_FAILED) {
    if (sem_close(sem) == -1) {
      printf("Failed to close semaphore %s: %s\n", name, strerror(errno));
      return false;
    }
  }
  return true;
}

// returns the total number of bytes in all buffers
static size_t get_buff_total_sz(ChannelInfo *info) {
  return (size_t)info->buff_count * info->buff_len;
}

int init_write_channel_with_info(const char *channel_name, const char *type,
                                 ChannelInfo *info, WriteChannel *target) {
  const int FAILED = -1;

  if (!alloc_channel_infra_names(CHANNEL_NAME_BASE, channel_name, type,
                                 &target->names)) {
    printf("Could not init infra names\n");
    return FAILED;
  }
  target->info = *info;
  target->bumper_offset = 0;
  target->current_buffer_idx = 0;

  //  If O_CREAT is specified, and a semaphore with the given name already
  //  exists, then mode and value are ignored. (this should be the case, because
  //  we will want to first run the server and then the instrumented binary)
  target->sem_full =
      sem_open(target->names.name_sem_full, O_CREAT, SEMPERMS, 0);
  if (target->sem_full == SEM_FAILED) {
    printf("Failed to initialize FULL semaphore %s: %s\n",
           target->names.name_sem_full, strerror(errno));
    goto fail_free_names;
  }

  target->sem_free = sem_open(target->names.name_sem_free, O_CREAT, SEMPERMS,
                              target->info.buff_count);
  if (target->sem_free == SEM_FAILED) {
    printf("Failed to initialize FREE semaphore %s: %s\n",
           target->names.name_sem_free, strerror(errno));
    goto fail_close_full_sem;
  }

  int sem_value = -1;
  sem_getvalue(target->sem_free, &sem_value);
  printf("Semaphore value: %d\n", sem_value);

  size_t buff_total_size = get_buff_total_sz(info);
  if (mmap_shmem(target->names.name_buff_mem, &target->buffer_base,
                 &target->file_descriptor, buff_total_size, true) != 0) {
    printf("Channel shmem mapping failed!");
    goto fail_close_free_sem;
  }

  return 0;

fail_close_free_sem:
  semaphore_close(target->sem_free, target->names.name_sem_free);
fail_close_full_sem:
  semaphore_close(target->sem_full, target->names.name_sem_full);
fail_free_names:
  dealloc_channel_infra_names(&target->names);
  return FAILED;
}

// ----------------------------- Channel data manipulation funcitons!

// waits for a free buffer and updates related data
static bool update_buffer_idx(WriteChannel *self) {
  // wait for a free buffer
  if (sem_wait(self->sem_free) != 0) {
    printf("Failed waiting for free buffer %s: %s\n", self->names.name_sem_free,
           strerror(errno));
    return false;
  }

  self->bumper_offset = 0;
  size_t *idx = &self->current_buffer_idx;
  *idx = *idx + 1 == self->info.buff_count ? 0 : *idx + 1;
  return true;
}

// used when local buffer is full and a new one is needed
static bool move_to_next_buff(WriteChannel *self) {
  // signal buffer is full
  if (sem_post(self->sem_full) != 0) {
    printf("Failed posting a full buffer%s: %s\n", self->names.name_sem_full,
           strerror(errno));
    return false;
  }

  return update_buffer_idx(self);
}

int termination_sequence_raw(sem_t *sem_full, uint32_t buffer_count) {
  // we'll post to the "full" semaphore exactly 2 * N times (N = number of
  // buffers) this is in order to guarantee N consecutive "empty" buffers being
  // sent

  // the above relies on the fact that the other side of the communication
  // sets the payload length (inside a buffer) to zero before "pushing it back"
  for (uint32_t i = 0; i < 2 * buffer_count; ++i) {
    if (sem_post(sem_full) != 0) {
      perror("Failed posting a full buffer in termination sequence");
      return -1;
    }
  }
  return 0;
}

// terminates the protocol on a channel
static bool termination_sequence(WriteChannel *self) {
  return termination_sequence_raw(self->sem_full, self->info.buff_count) == 0;
}

static void *get_buffer(WriteChannel *self, size_t idx) {
  static_assert(sizeof(char) == 1, "Byte is a byte");
  assert(idx < self->info.buff_count);
  return (void *)((char *)self->buffer_base + (idx * self->info.buff_len));
}

static void *get_buffer_end(WriteChannel *self) {
  // get an offset into the payload portion of the buffer:

  //  sizeof(s_bumper) bytes      s_bumper bytes of written data
  // |----s_bumber-4Bytes----|-----------data---->
  // s_bumper is offset into data -------^^^^

  return (void *)((char *)get_buffer(self, self->current_buffer_idx) +
                  sizeof(self->bumper_offset) + self->bumper_offset);
}

static int unchecked_write(WriteChannel *self, const void *source,
                           uint32_t len) {
  void *destination = get_buffer_end(self);

  if ((source > destination &&
       (const char *)source < (char *)destination + len) ||
      (destination > source &&
       (char *)destination < (const char *)source + len)) {
    printf("Aliasing regions of memory when pushing data to buffer dest: %p, "
           "src: %p, len: %u\n",
           destination, source, len);
    return -1;
  }

  memcpy(destination, source, len);
  self->bumper_offset += len;
  // in case of a crash, the last buffer's size MUST be known even if it was in
  // progress
  *(uint32_t *)get_buffer(self, self->current_buffer_idx) = self->bumper_offset;
  return 0;
}

// returns the number of bytes where data can be written
static uint32_t get_buff_data_space(WriteChannel *self) {
  static_assert(sizeof(self->bumper_offset) < (size_t)UINT32_MAX,
                "Needed for the line below");
  return self->info.buff_len - (uint32_t)sizeof(self->bumper_offset);
}

static uint32_t get_buff_data_free_space(WriteChannel *self) {
  return get_buff_data_space(self) - self->bumper_offset;
}

static int can_push_data_of_size(WriteChannel *self, size_t len) {
  // check overflow
  if (SIZE_MAX - len < get_buff_data_space(self)) {
    printf("Overflow on data size %lu\n", len);
    return -1;
  } else if (get_buff_data_space(self) == 0) {
    printf("The reserved buffer space is equal to the buffer size specified by "
           "llcap-server, thus no data can be transferred\n");
    return -1;
  }
  return 0;
}

int channel_start(WriteChannel *self) {
  self->current_buffer_idx = self->info.buff_count - 1;
  return update_buffer_idx(self) ? 0 : -1;
}

int channel_write(WriteChannel *self, const void *source, uint32_t len) {
  if (can_push_data_of_size(self, len) != 0) {
    return -1;
  }

  uint32_t written = 0;
  while (len > 0) {
    uint32_t free_space = get_buff_data_free_space(self);
    // we must ensure that 8-byte values are readable in 1 read (from 1 buffer)
    //  - limitation of how llcap-server reads function, module and thread IDs
    if (free_space < 8) {
      if (!move_to_next_buff(self)) {
        printf("Failed to obtain a free buffer!\n");
        return -1;
      }
      // update free space
      free_space = get_buff_data_free_space(self);
    }
    uint32_t to_write = free_space < len ? free_space : len;

    int rv = unchecked_write(self, (const char *)source + written, to_write);
    if (rv != 0) {
      printf("Failed partial push %p, written %u, to_write %u", source, written,
             to_write);
      return -1;
    }
    len -= to_write;
    written += to_write;

    // in other words, the entire pyaload has not been pushed
    // and thus the buffer must be full
    if (len != 0) {
      // obtain the next buffer
      if (!move_to_next_buff(self)) {
        printf("Failed to obtain a free buffer!\n");
        return -1;
      }
    } // the else branch here is performed via the loop condition
    // and repeat
  }

  return 0;
}

int deinit_channel(WriteChannel *self) {
  // does not have & ignores return values as the program is terminating at this
  // point
  if (!move_to_next_buff(self)) {
    printf("Failed to obtain a free buffer!\n");
  }

  if (!termination_sequence(self)) {
    printf("Failed to send termination sequence during deinit\n");
  }

  unmap_shmem(self->buffer_base, self->file_descriptor,
              self->names.name_buff_mem, self->info.total_len,
              UNMAP_SHMEM_FLAG_TRY_ALL);
  semaphore_close(self->sem_free, self->names.name_sem_free);
  semaphore_close(self->sem_full, self->names.name_sem_full);
  dealloc_channel_infra_names(&self->names);
  return 0;
}
