package ai.truthlens;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {
  private final JdbcTemplate db;
  private final AccessControl access;

  public DashboardController(JdbcTemplate db, AccessControl access) {
    this.db = db;
    this.access = access;
  }

  @GetMapping("/dashboard")
  Map<String, Object> dashboard() {
    CurrentUser user = access.requireUser();
    String where = "WHERE " + access.historySql(user);
    Object[] args = access.historyUsesOwnerParameter(user) ? new Object[] {user.id()} : new Object[] {};
    List<Map<String, Object>> byType = db.queryForList(
        "SELECT input_type, count(*) AS total FROM analyses " + where + " GROUP BY input_type", args);
    List<Map<String, Object>> byStatus = db.queryForList(
        "SELECT status, count(*) AS total FROM analyses " + where + " GROUP BY status", args);
    List<Map<String, Object>> textLabels = db.queryForList(
        "SELECT result_json->>'label' AS label, count(*) AS total FROM analyses " + where
            + " AND input_type='TEXT' AND result_json ?? 'label' GROUP BY 1",
        args);
    List<Map<String, Object>> imageVerdicts = db.queryForList(
        "SELECT result_json->>'verdict' AS verdict, count(*) AS total FROM analyses " + where
            + " AND input_type='IMAGE' AND result_json ?? 'verdict' GROUP BY 1",
        args);
    // A claim whose verification call failed is counted as UNAVAILABLE, never folded into
    // INSUFFICIENT - including the historical rows that were stored that way.
    List<Map<String, Object>> verification = db.queryForList(
        "SELECT CASE WHEN component_status='UNAVAILABLE' THEN 'UNAVAILABLE'"
            + " ELSE COALESCE(verdict,'INSUFFICIENT') END AS verdict, count(*) AS total FROM claims WHERE analysis_id IN (SELECT id FROM analyses "
            + where + ") GROUP BY 1",
        args);
    List<Map<String, Object>> trend = db.queryForList(
        "SELECT date_trunc('day', created_at) AS day, count(*) AS total FROM analyses " + where
            + " GROUP BY 1 ORDER BY 1 DESC LIMIT 30",
        args);
    return Map.of(
        "scope", user.isAdmin() ? "assigned-records" : (user.isFactChecker() ? "own-and-reviewable" : "own-records"),
        "by_type", byType,
        "by_status", byStatus,
        "text_labels", textLabels,
        "image_verdicts", imageVerdicts,
        "verification_verdicts", verification,
        "daily_trend", trend);
  }
}
