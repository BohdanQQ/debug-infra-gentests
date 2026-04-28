#include "shm_oneshot_rx.h"
#include "shm_util.h"
#include <fcntl.h>
#include <semaphore.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/sem.h>
#include <sys/stat.h>

#define SEMPERMS (S_IROTH | S_IWOTH | S_IWGRP | S_IRGRP | S_IWUSR | S_IRUSR)

bool oneshot_shm_read(const char *data_sem_name, const char *ack_sem_name,
                      const char *shm_name, const char *shm_size_name, bool (handler)(const void* source, uint32_t size, void* extra), uint32_t max_size, void* extra_data) {
  // initialize channel semaphores
  // we have 2 - the "data available" semaphore and an "ack" semaphore (signals
  // we read the data and are ready to proceed)
  sem_t *semaphore = sem_open(data_sem_name, O_CREAT, SEMPERMS, 0);
  if (semaphore == SEM_FAILED) {
    printf("Failed to initialize oneshot data semaphore %s\n", data_sem_name);
    perror("");
    return false;
  }

  sem_t *ack = sem_open(ack_sem_name, O_CREAT, SEMPERMS, 0);
  if (semaphore == SEM_FAILED) {
    printf("Failed to initialize oneshot ack semaphore %s\n", ack_sem_name);
    perror("");
    sem_close(semaphore);
    return false;
  }

  bool rv = false;
  // wait for data to be ready
  if (sem_wait(semaphore) == -1) {
    printf("Oneshot readout from shared memory failed on semaphore wait %s\n",
      data_sem_name);
    goto close_sem;
  }
    
  // map memory synchronized by the semaphores
  int fd = -1;
  void *source;

  uint32_t sz_to_alloc = 0;
  if (mmap_shmem(shm_size_name, &source, &fd, sizeof(sz_to_alloc), false) == -1) {
      printf("Oneshot readout from shared memory failed on size read %s\n",
      shm_size_name);
      goto close_sem;
  }
  memcpy(&sz_to_alloc, source, sizeof(sz_to_alloc));
  unmap_shmem(source, fd, shm_size_name, sizeof(sz_to_alloc), UNMAP_SHMEM_FLAG_TRY_ALL);
  
  if (sz_to_alloc > max_size) {
    printf("Size to allocate is suspicious. Aborting.\n(size: %u, max: %u)\n", sz_to_alloc, max_size);
    goto close_sem;
  }
  
  if (mmap_shmem(shm_name, &source, &fd, sz_to_alloc, false) == -1) {
    printf("Oneshot readout from shared memory failed on data readout %s %u\n",
           shm_name, sz_to_alloc);
    goto close_sem;
  }

  rv = handler(source, sz_to_alloc, extra_data);

  if(!rv) {
    printf("Memhandler failed\n");
  }
  // inform we're done, cleanup
  if (sem_post(ack) != 0) {
    printf("Oneshot failed to ack on sem %s\n", ack_sem_name);
    goto end;
  }

end:
  unmap_shmem(source, fd, shm_name, sz_to_alloc, UNMAP_SHMEM_FLAG_TRY_ALL);
close_sem:
  sem_close(ack);
  sem_close(semaphore);
  return rv;
}
