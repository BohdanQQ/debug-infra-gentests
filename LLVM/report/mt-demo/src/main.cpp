#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>

// mostly generated with Gemini (12th July 2026)

std::queue<int> work_queue;
std::mutex queue_mutex;
std::condition_variable cv;

std::atomic<bool> keep_running{true};
std::atomic<unsigned long long> items_processed{0};

void do_work(int seed, int id) {
  double result = seed;
  for (int i = 0; i < 5000; ++i) {
    result = std::sin(result) + std::cos(result);
  }
  std::cout << "ID " << id << ' ' << seed << "result: " << result << '\n';
}

void producer(int id) {
  int counter = 0;
  while (keep_running) {
    {
      std::lock_guard<std::mutex> lock(queue_mutex);
      work_queue.push(counter++ + (id * 100000));
    }

    cv.notify_one();
    std::this_thread::sleep_for(std::chrono::milliseconds(400));
  }
}

// --- Consumer Thread ---
void consumer(int id) {
  while (keep_running || !work_queue.empty()) {
    int work_item = -1;

    {
      std::unique_lock<std::mutex> lock(queue_mutex);
      cv.wait_for(lock, std::chrono::milliseconds(10),
                  [] { return !work_queue.empty() || !keep_running; });

      if (!work_queue.empty()) {
        work_item = work_queue.front();
        work_queue.pop();
      }
    }

    if (work_item != -1) {
      do_work(work_item, id);
      items_processed++;
    }
  }
}

int main(int argc, char *argv[]) {
  // Default configurations
  int run_time_seconds = 30;
  int num_producers = 2;
  // Default consumers to available cores (minimum 1)
  int num_consumers =
      std::max(1, (int)std::thread::hardware_concurrency() - num_producers);

  // Parse command-line arguments
  try {
    if (argc > 1)
      run_time_seconds = std::stoi(argv[1]);
    if (argc > 2)
      num_producers = std::stoi(argv[2]);
    if (argc > 3)
      num_consumers = std::stoi(argv[3]);
  } catch (const std::exception &e) {
    std::cerr << "Usage: " << argv[0] << " [seconds] [producers] [consumers]\n";
    return 1;
  }

  // Validation to prevent deadlocks or infinite memory growth
  if (run_time_seconds <= 0)
    run_time_seconds = 30;
  if (num_producers < 0)
    num_producers = 0;
  if (num_consumers <= 0) {
    std::cerr << "Error: Must have at least 1 consumer.\n";
    return 1;
  }

  std::cout << "Configuration:\n"
            << "  Duration  : " << run_time_seconds << " seconds\n"
            << "  Producers : " << num_producers << "\n"
            << "  Consumers : " << num_consumers << "\n\n";

  std::vector<std::thread> threads;

  // 1. Launch threads
  for (int i = 0; i < num_producers; ++i) {
    threads.emplace_back(producer, i);
  }
  for (int i = 0; i < num_consumers; ++i) {
    threads.emplace_back(consumer, i);
  }

  // 2. Let the program run
  std::this_thread::sleep_for(std::chrono::seconds(run_time_seconds));

  // 3. Initiate graceful shutdown
  std::cout << "Time up. Initiating shutdown...\n";
  keep_running = false;
  cv.notify_all();

  // 4. Join threads
  for (auto &t : threads) {
    if (t.joinable()) {
      t.join();
    }
  }

  std::cout << "Shutdown complete.\n";
  std::cout << "Total items processed: " << items_processed.load() << "\n";

  return 0;
}