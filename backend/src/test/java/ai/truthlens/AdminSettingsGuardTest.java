package ai.truthlens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Value validation and audit of the administrator system setting, asserted against the controller
 * with a mocked database so no real setting row is touched. The HTTP role boundary and the
 * enforcement of the pause are covered by AdminSettingsTest.
 */
class AdminSettingsGuardTest {
  private static final String VALUE_LOOKUP = "SELECT setting_value FROM system_settings WHERE setting_key=?";
  private static final String RECORD_LOOKUP = "SELECT setting_value, updated_at, updated_by";
  private static final String UPSERT = "INSERT INTO system_settings(setting_key, setting_value";

  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private final AccessControl access = mock(AccessControl.class);
  private final SystemSettingsService settings = new SystemSettingsService(db);
  private final AdminSettingsController controller = new AdminSettingsController(access, settings);
  private final UUID actorId = UUID.randomUUID();

  @BeforeEach
  void adminActor() {
    when(access.requireRole("ADMIN")).thenReturn(new CurrentUser(actorId, "ADMIN", true));
    when(db.queryForList(startsWith(RECORD_LOOKUP), any(Object[].class))).thenReturn(List.of());
  }

  @Test
  void nonAdministratorsAreDeniedBothReadingAndWriting() {
    when(access.requireRole("ADMIN")).thenThrow(ApiException.forbidden());

    assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, controller::read).status());
    assertEquals(HttpStatus.FORBIDDEN,
        assertThrows(ApiException.class, () -> controller.update(Map.of("submissionsEnabled", false))).status());
    verify(db, never()).update(any(String.class), any(Object[].class));
  }

  @Test
  void onlyTrueAndFalseAreAccepted() {
    for (Object rejected : new Object[] {"maybe", "", "1", "yes", 2, List.of("true")}) {
      ApiException error = assertThrows(ApiException.class,
          () -> controller.update(Map.of("submissionsEnabled", rejected)));
      assertEquals(HttpStatus.BAD_REQUEST, error.status(), String.valueOf(rejected));
    }
    assertEquals(HttpStatus.BAD_REQUEST,
        assertThrows(ApiException.class, () -> controller.update(Map.of())).status());
    verify(db, never()).update(any(String.class), any(Object[].class));
  }

  @Test
  void pausingIsPersistedAndAudited() {
    when(db.queryForList(startsWith(VALUE_LOOKUP), eq(String.class), any(Object[].class))).thenReturn(List.of("false"));

    Map<String, Object> result = controller.update(Map.of("submissionsEnabled", false));

    assertEquals(false, result.get("submissionsEnabled"));
    verify(db).update(startsWith(UPSERT), eq(SystemSettingsService.SUBMISSIONS_ENABLED), eq("false"), eq(actorId));
    verify(db).update(startsWith("INSERT INTO audit_events"), any(UUID.class), eq(actorId),
        eq("ADMIN_SYSTEM_SETTING_UPDATE"), any(), any(java.time.OffsetDateTime.class));
    assertFalse(settings.submissionsEnabled());
  }

  @Test
  void aStringTrueIsNormalisedAndResumesSubmissions() {
    when(db.queryForList(startsWith(VALUE_LOOKUP), eq(String.class), any(Object[].class))).thenReturn(List.of("true"));

    Map<String, Object> result = controller.update(Map.of("submissionsEnabled", "true"));

    assertEquals(true, result.get("submissionsEnabled"));
    ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
    verify(db).update(startsWith(UPSERT), eq(SystemSettingsService.SUBMISSIONS_ENABLED), value.capture(), eq(actorId));
    assertEquals("true", value.getValue());
  }

  @Test
  void aMissingRowKeepsSubmissionsOpenInsteadOfPausingThem() {
    when(db.queryForList(startsWith(VALUE_LOOKUP), eq(String.class), any(Object[].class))).thenReturn(List.of());

    assertTrue(settings.submissionsEnabled());
    settings.requireSubmissionsOpen();
  }

  @Test
  void pausedSubmissionsFailWithTheDocumentedMessage() {
    when(db.queryForList(startsWith(VALUE_LOOKUP), eq(String.class), any(Object[].class))).thenReturn(List.of("false"));

    ApiException error = assertThrows(ApiException.class, settings::requireSubmissionsOpen);
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
    assertEquals(SystemSettingsService.PAUSE_MESSAGE, error.getMessage());
  }

  @Test
  void thePublishedRecordCarriesOnlyTheOperationalSetting() {
    controller.read();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture(), any(Object[].class));
    assertTrue(sql.getValue().toLowerCase().contains("system_settings"));
    assertFalse(sql.getValue().toLowerCase().contains("password"));
    assertEquals(java.util.Set.of("submissionsEnabled"), settings.current().keySet());
  }
}
