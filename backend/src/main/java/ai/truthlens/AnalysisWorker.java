package ai.truthlens;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "truthlens.jobs.sync", havingValue = "false", matchIfMissing = true)
public class AnalysisWorker {
  private final AnalysisService analyses;
  private final Semaphore permits;
  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final String workerToken = UUID.randomUUID().toString();
  private final int leaseSeconds;
  private final long heartbeatMs;
  private final long sweepMs;
  private final long pollMs;
  private final Object wake = new Object();
  private volatile boolean running = true;

  public AnalysisWorker(
      AnalysisService analyses,
      @Value("${truthlens.jobs.workers:2}") int workers,
      @Value("${truthlens.jobs.lease-seconds:60}") int leaseSeconds,
      @Value("${truthlens.jobs.heartbeat-ms:10000}") long heartbeatMs,
      @Value("${truthlens.jobs.sweep-ms:30000}") long sweepMs,
      @Value("${truthlens.jobs.poll-ms:1000}") long pollMs) {
    this.analyses = analyses;
    this.permits = new Semaphore(Math.max(1, workers));
    this.leaseSeconds = leaseSeconds;
    this.heartbeatMs = heartbeatMs;
    this.sweepMs = sweepMs;
    this.pollMs = Math.max(1, pollMs);

    // Immediate sweep covers an immediate restart: jobs whose lease expired
    // while the previous instance was down are re-queued at once, so a
    // PROCESSING job is never stranded just because the app restarted quickly.
    analyses.recoverStale(leaseSeconds);

    Thread poller = new Thread(this::loop, "truthlens-job-poller");
    poller.setDaemon(true);
    poller.start();

    if (heartbeatMs > 0) {
      Thread heartbeat = new Thread(this::heartbeatLoop, "truthlens-job-heartbeat");
      heartbeat.setDaemon(true);
      heartbeat.start();
    }

    if (sweepMs > 0) {
      Thread sweeper = new Thread(this::sweepLoop, "truthlens-job-sweeper");
      sweeper.setDaemon(true);
      sweeper.start();
    }
  }

  @PreDestroy
  void stop() {
    running = false;
    synchronized (wake) {
      wake.notifyAll();
    }
    executor.shutdownNow();
  }

  private void loop() {
    while (running) {
      try {
        List<UUID> work = analyses.claimWork(2, workerToken);
        for (UUID id : work) {
          if (!permits.tryAcquire()) {
            break;
          }
          executor.submit(() -> {
            try {
              analyses.process(id);
            } finally {
              permits.release();
            }
          });
        }
        if (work.isEmpty()) {
          awaitWork();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        running = false;
      } catch (Exception ignored) {
        try {
          TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          running = false;
        }
      }
    }
  }

  /** Wakes the poller as soon as a job row is committed, rather than at its next scheduled poll. */
  @EventListener
  void onJobEnqueued(JobEnqueued event) {
    synchronized (wake) {
      wake.notifyAll();
    }
  }

  /** Idle wait between polls. The timeout keeps pickup independent of the event path. */
  private void awaitWork() throws InterruptedException {
    synchronized (wake) {
      wake.wait(pollMs);
    }
  }

  /** Periodically renews the lease of every job this worker actually holds. */
  private void heartbeatLoop() {
    while (running) {
      try {
        TimeUnit.MILLISECONDS.sleep(heartbeatMs);
        analyses.refreshLease(workerToken);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        running = false;
      } catch (Exception ignored) {
        // DB hiccup: worker keeps running; if the lease lapses the sweeper
        // re-queues the job, which is safe because reprocessing is idempotent.
      }
    }
  }

  /** Periodically re-queues abandoned jobs whose lease expired. */
  private void sweepLoop() {
    while (running) {
      try {
        TimeUnit.MILLISECONDS.sleep(sweepMs);
        analyses.recoverStale(leaseSeconds);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        running = false;
      } catch (Exception ignored) {
        // Keep sweeping; a transient DB error must not kill recovery.
      }
    }
  }
}