package ai.truthlens;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
  private final JdbcTemplate db;
  private final AccessControl access;

  public ReviewController(JdbcTemplate db, AccessControl access) {
    this.db = db;
    this.access = access;
  }

  @GetMapping("/reviews/queue")
  List<Map<String, Object>> queue() {
    CurrentUser user = access.requireUser();
    if (!user.isFactChecker() && !user.isAdmin()) {
      throw ApiException.forbidden();
    }
    return db.queryForList(
        """
        SELECT id, input_type, status, review_status, created_at, updated_at
        FROM analyses
        WHERE reviewable=TRUE AND owner_id IS NOT NULL
        ORDER BY updated_at DESC
        LIMIT 100
        """);
  }

  @PostMapping("/reviews/{analysisId}")
  Map<String, Object> review(@PathVariable UUID analysisId, @RequestBody Map<String, String> input) {
    CurrentUser user = access.requireUser();
    if (!user.isFactChecker() && !user.isAdmin()) {
      throw ApiException.forbidden();
    }
    Map<String, Object> analysis = access.requireReadableAnalysis(analysisId);
    if (!Boolean.TRUE.equals(analysis.get("reviewable"))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "This analysis is not in the review queue");
    }
    String status = String.valueOf(input.getOrDefault("status", "")).trim().toUpperCase();
    if (!List.of("NEEDS_INFO", "CONFIRMED", "DISPUTED", "CLOSED").contains(status)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Review status is invalid");
    }
    String notes = input.getOrDefault("notes", "");
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO reviews(id,analysis_id,reviewer_id,status,notes) VALUES (?,?,?,?,?)",
        id,
        analysisId,
        user.id(),
        status,
        notes);
    db.update("UPDATE analyses SET review_status=?, updated_at=now() WHERE id=?", status, analysisId);
    db.update(
        "INSERT INTO audit_events(id,actor_id,action,entity_id,created_at) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        user.id(),
        "REVIEW_" + status,
        analysisId,
        OffsetDateTime.now());
    return Map.of(
        "id",
        id,
        "analysisId",
        analysisId,
        "status",
        status,
        "notes",
        notes,
        "note",
        "Human review does not overwrite model predictions.");
  }
}
