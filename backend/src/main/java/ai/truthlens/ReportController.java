package ai.truthlens;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SRS §10 Reporting Service. Read-only, ownership-scoped report views.
 * Listing reuses the same role/ownership filter as history; a single report is
 * only readable by its owner, an authorized fact checker (reviewable items), or an admin.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
  private final AnalysisService analyses;
  private final AccessControl access;

  public ReportController(AnalysisService analyses, AccessControl access) {
    this.analyses = analyses;
    this.access = access;
  }

  @GetMapping
  List<Map<String, Object>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return analyses.history(access.requireUser(), q, type, status, page, size);
  }

  @GetMapping("/{id}")
  Map<String, Object> report(@PathVariable UUID id) {
    return analyses.report(id);
  }
}
