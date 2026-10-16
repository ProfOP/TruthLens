package ai.truthlens;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persisted operational settings, one row per key, read on each request so an administrator's
 * change is enforced immediately without restarting the service. Model thresholds, claim limits
 * and storage rules deliberately do not live here: they are fixed by the verified model artefacts
 * and would silently invalidate published accuracy figures if they were made editable.
 */
@Service
public class SystemSettingsService {
  public static final String SUBMISSIONS_ENABLED = "submissions.enabled";
  public static final String PAUSE_MESSAGE =
      "New analyses are paused by the administrator. Existing reports remain available.";

  private final JdbcTemplate db;

  public SystemSettingsService(JdbcTemplate db) {
    this.db = db;
  }

  public boolean submissionsEnabled() {
    List<String> rows = db.queryForList(
        "SELECT setting_value FROM system_settings WHERE setting_key=?", String.class, SUBMISSIONS_ENABLED);
    // A missing row is the seeded default: submissions open.
    return rows.isEmpty() || !"false".equalsIgnoreCase(rows.get(0));
  }

  public void requireSubmissionsOpen() {
    if (!submissionsEnabled()) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, PAUSE_MESSAGE);
    }
  }

  /** Admin-facing projection: current value plus who changed it and when. */
  public Map<String, Object> current() {
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT setting_value, updated_at, updated_by, "
            + "(SELECT display_name FROM users u WHERE u.id = s.updated_by) AS updated_by_name "
            + "FROM system_settings s WHERE setting_key=?",
        SUBMISSIONS_ENABLED);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("submissionsEnabled", submissionsEnabled());
    if (rows.isEmpty()) {
      return result;
    }
    Map<String, Object> row = rows.get(0);
    result.put("updatedAt", row.get("updated_at"));
    result.put("updatedBy", row.get("updated_by"));
    result.put("updatedByName", row.get("updated_by_name"));
    return result;
  }

  public Map<String, Object> updateSubmissions(CurrentUser actor, boolean enabled) {
    db.update("INSERT INTO system_settings(setting_key, setting_value, updated_at, updated_by) VALUES (?,?,now(),?) "
            + "ON CONFLICT (setting_key) DO UPDATE SET setting_value=excluded.setting_value, "
            + "updated_at=now(), updated_by=excluded.updated_by",
        SUBMISSIONS_ENABLED, String.valueOf(enabled), actor.id());
    db.update(
        "INSERT INTO audit_events(id,actor_id,action,entity_id,created_at) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        actor.id(),
        "ADMIN_SYSTEM_SETTING_UPDATE",
        null,
        OffsetDateTime.now());
    return current();
  }
}
