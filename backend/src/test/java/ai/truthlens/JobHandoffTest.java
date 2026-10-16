package ai.truthlens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Job hand-off latency. A submitted analysis must reach the worker without waiting for the poller's
 * previous wait to expire, because that wait was measured as 0.5-1.0 s of dead time per job, while
 * the queue row has to be committed before the worker is told the job exists.
 */
class JobHandoffTest {
  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final AnalysisService analyses = new AnalysisService(
      db,
      mock(AiGateway.class),
      new ObjectMapper(),
      mock(StorageService.class),
      mock(AccessControl.class),
      mock(SystemSettingsService.class),
      events,
      false);

  @Test
  void textSubmissionAnnouncesTheJobOnlyAfterTheQueueRowIsInserted() {
    when(db.queryForObject(anyString(), eq(String.class), any())).thenReturn("SUBMITTED");
    UUID userId = UUID.randomUUID();

    Map<String, Object> created =
        analyses.submitText(new CurrentUser(userId, "USER", true), Map.of("title", "T", "body", "The bank printed notes."));
    UUID analysisId = (UUID) created.get("id");

    InOrder order = inOrder(db, events);
    order.verify(db).update(startsWith("INSERT INTO analyses"), any(), eq(userId), eq("TEXT"), any(), eq("SUBMITTED"));
    order.verify(db).update(startsWith("INSERT INTO analysis_jobs"), any(), eq(analysisId), eq("SUBMITTED"));
    order.verify(events).publishEvent(new JobEnqueued(analysisId));
  }

  @Test
  void claimSubmissionAnnouncesItsJobToo() {
    when(db.queryForObject(anyString(), eq(String.class), any())).thenReturn("SUBMITTED");
    UUID userId = UUID.randomUUID();

    Map<String, Object> created =
        analyses.submitClaim(new CurrentUser(userId, "USER", true), Map.of("claim", "The bank printed notes."));

    assertEquals("SUBMITTED", created.get("status"));
    verify(events).publishEvent(new JobEnqueued((UUID) created.get("id")));
  }

  @Test
  void anEnqueuedJobWakesTheIdlePollerLongBeforeItsPollTimeout() throws Exception {
    AnalysisService idle = mock(AnalysisService.class);
    when(idle.claimWork(anyInt(), any())).thenReturn(List.of());
    // A one-minute poll interval: any second claim inside three seconds can only come from the wake-up.
    AnalysisWorker worker = new AnalysisWorker(idle, 1, 60, 0, 0, 60_000);
    try {
      Thread.sleep(200);
      worker.onJobEnqueued(new JobEnqueued(UUID.randomUUID()));
      verify(idle, timeout(3000).atLeast(2)).claimWork(anyInt(), any());
    } finally {
      worker.stop();
    }
  }
}
