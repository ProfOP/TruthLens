package ai.truthlens;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class AnalysisController {
  private final AnalysisService analyses;
  private final AccessControl access;
  private final StorageService storage;
  private final SystemSettingsService settings;

  public AnalysisController(
      AnalysisService analyses, AccessControl access, StorageService storage, SystemSettingsService settings) {
    this.analyses = analyses;
    this.access = access;
    this.storage = storage;
    this.settings = settings;
  }

  /** Operational status any signed-in user may read, so a pause can be explained before submitting. */
  @GetMapping("/system/status")
  Map<String, Object> systemStatus() {
    access.requireUser();
    return Map.of("submissionsEnabled", settings.submissionsEnabled());
  }

  @PostMapping("/analysis/text")
  ResponseEntity<Map<String, Object>> text(@RequestBody Map<String, String> input) {
    return ResponseEntity.accepted().body(analyses.submitText(access.requireUser(), input));
  }

  @PostMapping("/analysis/claim")
  ResponseEntity<Map<String, Object>> claim(@RequestBody Map<String, String> input) {
    return ResponseEntity.accepted().body(analyses.submitClaim(access.requireUser(), input));
  }

  @PostMapping(value = "/analysis/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<Map<String, Object>> image(@RequestPart("file") MultipartFile file) {
    return ResponseEntity.accepted().body(analyses.submitImage(access.requireUser(), file));
  }

  @GetMapping("/analysis/{id}")
  Map<String, Object> analysis(@PathVariable UUID id) {
    return analyses.result(id);
  }

  @GetMapping("/analysis")
  List<Map<String, Object>> history(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return analyses.history(access.requireUser(), q, type, status, page, size);
  }

  @PostMapping("/analysis/{id}/claims")
  Map<String, Object> claims(@PathVariable UUID id, @RequestBody Map<String, List<String>> input) {
    return analyses.replaceClaims(id, input.getOrDefault("claims", List.of()));
  }

  @PostMapping("/analysis/{id}/review-request")
  Map<String, Object> reviewRequest(@PathVariable UUID id) {
    return analyses.requestReview(id);
  }

  @GetMapping("/analysis/{id}/assets/{assetId}")
  ResponseEntity<FileSystemResource> asset(@PathVariable UUID id, @PathVariable UUID assetId) throws Exception {
    Map<String, Object> row = analyses.asset(id, assetId);
    Path path = storage.resolveOwned(String.valueOf(row.get("storage_path")));
    FileSystemResource resource = new FileSystemResource(path);
    String contentType = row.get("content_type") == null ? Files.probeContentType(path) : String.valueOf(row.get("content_type"));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .contentType(MediaType.parseMediaType(contentType == null ? "application/octet-stream" : contentType))
        .body(resource);
  }
}
