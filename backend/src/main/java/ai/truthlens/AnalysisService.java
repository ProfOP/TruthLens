package ai.truthlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AnalysisService {
  public static final int MAX_CLAIMS = 3;
  private final JdbcTemplate db;
  private final AiGateway ai;
  private final ObjectMapper json;
  private final StorageService storage;
  private final AccessControl access;
  private final SystemSettingsService settings;
  private final ApplicationEventPublisher events;
  private final boolean syncJobs;

  public AnalysisService(
      JdbcTemplate db,
      AiGateway ai,
      ObjectMapper json,
      StorageService storage,
      AccessControl access,
      SystemSettingsService settings,
      ApplicationEventPublisher events,
      @Value("${truthlens.jobs.sync:false}") boolean syncJobs) {
    this.db = db;
    this.ai = ai;
    this.json = json;
    this.storage = storage;
    this.access = access;
    this.settings = settings;
    this.events = events;
    this.syncJobs = syncJobs;
  }

  public Map<String, Object> submitText(CurrentUser user, Map<String, String> input) {
    settings.requireSubmissionsOpen();
    String body = input.getOrDefault("body", "").trim();
    String title = input.getOrDefault("title", "").trim();
    if (body.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "body is required");
    }
    UUID id = UUID.randomUUID();
    String stored;
    try {
      stored = json.writeValueAsString(Map.of("title", title, "body", body));
    } catch (Exception e) {
      stored = body;
    }
    db.update(
        "INSERT INTO analyses(id,owner_id,input_type,input_text,status) VALUES (?,?,?,?,?)",
        id,
        user.id(),
        "TEXT",
        stored,
        "SUBMITTED");
    enqueue(id);
    if (syncJobs) {
      process(id);
    }
    return Map.of("id", id, "status", currentStatus(id));
  }

  public Map<String, Object> submitClaim(CurrentUser user, Map<String, String> input) {
    settings.requireSubmissionsOpen();
    String claim = input.getOrDefault("claim", "").trim();
    if (claim.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "claim is required");
    }
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO analyses(id,owner_id,input_type,input_text,status) VALUES (?,?,?,?,?)",
        id,
        user.id(),
        "CLAIM",
        claim,
        "SUBMITTED");
    enqueue(id);
    if (syncJobs) {
      process(id);
    }
    return Map.of("id", id, "status", currentStatus(id));
  }

  public Map<String, Object> submitImage(CurrentUser user, MultipartFile file) {
    settings.requireSubmissionsOpen();
    if (file == null || file.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "image is empty or exceeds 10 MB");
    }
    if (file.getSize() > 10L * 1024 * 1024) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "image is empty or exceeds 10 MB");
    }
    String contentType = file.getContentType();
    if (contentType == null || !(contentType.equals("image/jpeg") || contentType.equals("image/png"))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Only JPG, JPEG, or PNG images are accepted");
    }
    UUID id = UUID.randomUUID();
    Path stored = null;
    try {
      db.update(
          "INSERT INTO analyses(id,owner_id,input_type,input_text,status) VALUES (?,?,?,?,?)",
          id,
          user.id(),
          "IMAGE",
          file.getOriginalFilename(),
          "VALIDATING");
      try (InputStream in = file.getInputStream()) {
        BufferedImage decoded = ImageIO.read(in);
        if (decoded == null) {
          throw new ApiException(HttpStatus.BAD_REQUEST, "The upload is not a valid image");
        }
      }
      StorageService.StoredFile saved = storage.saveUpload(id, file);
      stored = saved.path();
      db.update(
          "INSERT INTO assets(id,analysis_id,kind,storage_path,content_type) VALUES (?,?,?,?,?)",
          UUID.randomUUID(),
          id,
          "UPLOAD",
          saved.relativePath(),
          saved.contentType());
      db.update("UPDATE analyses SET status=?, updated_at=now() WHERE id=?", "SUBMITTED", id);
      enqueue(id);
      if (syncJobs) {
        process(id);
      }
      return Map.of("id", id, "status", currentStatus(id));
    } catch (ApiException e) {
      storage.deleteQuietly(stored);
      db.update("UPDATE analyses SET status=?, error_message=?, updated_at=now() WHERE id=?", "FAILED", e.getMessage(), id);
      throw e;
    } catch (Exception e) {
      storage.deleteQuietly(stored);
      db.update("UPDATE analyses SET status=?, error_message=?, updated_at=now() WHERE id=?", "FAILED", "Upload could not be stored", id);
      throw new ApiException(HttpStatus.BAD_REQUEST, "Upload could not be stored");
    }
  }

  public Map<String, Object> replaceClaims(UUID analysisId, List<String> claims) {
    Map<String, Object> analysis = access.requireReadableAnalysis(analysisId);
    CurrentUser user = access.requireUser();
    UUID owner = (UUID) analysis.get("owner_id");
    if (!user.id().equals(owner) && !user.isAdmin()) {
      throw ApiException.forbidden();
    }
    if (!"TEXT".equals(analysis.get("input_type")) && !"CLAIM".equals(analysis.get("input_type"))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Claims can only be edited on text or claim analyses");
    }
    List<String> selected = claims.stream().map(v -> v == null ? "" : v.trim()).filter(v -> !v.isBlank()).limit(MAX_CLAIMS).toList();
    db.update("DELETE FROM evidence WHERE claim_id IN (SELECT id FROM claims WHERE analysis_id=?)", analysisId);
    db.update("DELETE FROM claims WHERE analysis_id=?", analysisId);
    List<JsonNode> edited = new ArrayList<>();
    for (String text : selected) {
      ObjectNode node = json.createObjectNode();
      node.put("text", text);
      node.put("method", "user-edited");
      edited.add(node);
    }
    verifyPersistedClaims(analysisId, edited);
    refreshResultJson(analysisId);
    return Map.of("id", analysisId, "status", currentStatus(analysisId), "claims", listClaims(analysisId));
  }

  public Map<String, Object> requestReview(UUID analysisId) {
    Map<String, Object> analysis = access.requireReadableAnalysis(analysisId);
    CurrentUser user = access.requireUser();
    if (!user.id().equals(analysis.get("owner_id")) && !user.isAdmin()) {
      throw ApiException.forbidden();
    }
    db.update("UPDATE analyses SET reviewable=TRUE, review_status=COALESCE(review_status,'QUEUED'), updated_at=now() WHERE id=?", analysisId);
    audit(user.id(), "REQUEST_REVIEW", analysisId);
    return Map.of("id", analysisId, "reviewable", true);
  }

  public List<Map<String, Object>> history(CurrentUser user, String query, String type, String status, int page, int size) {
    int limit = Math.min(Math.max(size, 1), 100);
    int offset = Math.max(page, 0) * limit;
    StringBuilder sql = new StringBuilder("SELECT id,input_type,status,reviewable,created_at,updated_at FROM analyses WHERE ");
    sql.append(access.historySql(user));
    List<Object> args = new ArrayList<>();
    if (access.historyUsesOwnerParameter(user)) {
      args.add(user.id());
    }
    if (query != null && !query.isBlank()) {
      sql.append(" AND (CAST(id AS TEXT) ILIKE ? OR COALESCE(input_text,'') ILIKE ?)");
      args.add("%" + query.trim() + "%");
      args.add("%" + query.trim() + "%");
    }
    if (type != null && !type.isBlank()) {
      sql.append(" AND input_type=?");
      args.add(type.trim().toUpperCase());
    }
    if (status != null && !status.isBlank()) {
      sql.append(" AND status=?");
      args.add(status.trim().toUpperCase());
    }
    sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return db.queryForList(sql.toString(), args.toArray());
  }

  public Map<String, Object> result(UUID id) {
    Map<String, Object> analysis = access.requireReadableAnalysis(id);
    analysis.remove("owner_id");
    analysis.remove("guest_token_hash");
    // JSONB columns arrive as PGobject; normalize them to real JSON objects so
    // the API contract exposes nested fields (label, verification, SHAP tokens, ...).
    analysis.put("result_json", jsonValue(analysis.get("result_json")));
    List<Map<String, Object>> explanations =
        db.queryForList("SELECT id,kind,status,payload,created_at FROM explanations WHERE analysis_id=? ORDER BY created_at", id);
    explanations.forEach(e -> e.put("payload", jsonValue(e.get("payload"))));
    analysis.put("claims", listClaims(id));
    analysis.put("explanations", explanations);
    analysis.put("assets", db.queryForList("SELECT id,kind,content_type,created_at FROM assets WHERE analysis_id=?", id));
    analysis.put("reviews", db.queryForList("SELECT id,status,notes,created_at,reviewer_id FROM reviews WHERE analysis_id=? ORDER BY created_at", id));
    return analysis;
  }

  public static final String DISCLAIMER =
      "This is an automated assessment that requires human verification. It is not definitive proof, "
          + "and model outputs are not calibrated truth probabilities.";

  /** Shown wherever a verification call failed: a service failure, never an evidence finding. */
  public static final String VERIFICATION_UNAVAILABLE_MESSAGE = "Verification could not be completed.";

  /**
   * Authorized report projection for one analysis. Article classification, claim
   * verification, and human review are returned as separate sections so a reviewer
   * can never mistake a human verdict for a model prediction. Fields that a legacy
   * record does not carry are reported as null / "Unavailable" rather than fabricated.
   */
  public Map<String, Object> report(UUID id) {
    Map<String, Object> full = result(id); // enforces read ownership
    ObjectNode modelOutput = full.get("result_json") instanceof ObjectNode objectNode ? objectNode : null;
    String type = String.valueOf(full.get("input_type"));

    Map<String, Object> report = new java.util.LinkedHashMap<>();
    report.put("id", full.get("id"));
    report.put("input_type", type);
    report.put("status", full.get("status"));
    report.put("error_message", full.get("error_message"));
    report.put("created_at", full.get("created_at"));
    report.put("updated_at", full.get("updated_at"));
    report.put("input", full.get("input_text"));
    report.put("model_output", modelOutput);
    report.put("model_versions", modelVersions(type, modelOutput, full.get("claims")));
    report.put("claims", full.get("claims"));
    report.put("explanations", full.get("explanations"));
    report.put("assets", full.get("assets"));
    Map<String, Object> humanReview = new java.util.LinkedHashMap<>();
    humanReview.put("review_status", full.get("review_status"));
    humanReview.put("reviewable", Boolean.TRUE.equals(full.get("reviewable")));
    humanReview.put("reviews", full.get("reviews"));
    humanReview.put("note", "Human review is recorded separately and never overwrites the model result.");
    report.put("human_review", humanReview);
    report.put("disclaimer", DISCLAIMER);
    return report;
  }

  private ObjectNode modelVersions(String type, ObjectNode modelOutput, Object claims) {
    ObjectNode versions = json.createObjectNode();
    versions.put("analysis_model", modelOutput != null && modelOutput.hasNonNull("model")
        ? modelOutput.get("model").asText()
        : "Unavailable");
    String verificationModel = "Unavailable";
    if (claims instanceof List<?> list) {
      for (Object element : list) {
        if (element instanceof Map<?, ?> claim
            && claim.get("nli_output") instanceof JsonNode nli
            && nli.path("model").isTextual()) {
          verificationModel = nli.get("model").asText();
          break;
        }
      }
    }
    versions.put("verification_model", verificationModel);
    return versions;
  }

  /**
   * Converts a JSONB column value (PGobject or JSON string) into a JSON tree.
   * The JDBC driver is runtime-scoped, so PGobject is handled by duck-typing
   * (getValue()/toString) instead of a compile-time reference.
   */
  private JsonNode jsonValue(Object raw) {
    if (raw == null) {
      return null;
    }
    String text;
    if (raw instanceof String s) {
      text = s;
    } else {
      Object value = null;
      try {
        value = raw.getClass().getMethod("getValue").invoke(raw);
      } catch (ReflectiveOperationException ignored) {
        // Not a PGobject; fall back to toString below.
      }
      text = (value == null) ? String.valueOf(raw) : String.valueOf(value);
    }
    try {
      return json.readTree(text);
    } catch (Exception e) {
      return json.getNodeFactory().textNode(text);
    }
  }

  public Map<String, Object> asset(UUID analysisId, UUID assetId) {
    access.requireReadableAnalysis(analysisId);
    var rows = db.queryForList("SELECT id,kind,storage_path,content_type FROM assets WHERE id=? AND analysis_id=?", assetId, analysisId);
    if (rows.isEmpty()) {
      throw ApiException.notFound();
    }
    return rows.get(0);
  }

  public void process(UUID analysisId) {
    if (!isPending(analysisId)) {
      return;
    }
    var rows = db.queryForList("SELECT id,owner_id,input_type,input_text FROM analyses WHERE id=?", analysisId);
    if (rows.isEmpty()) {
      return;
    }
    Map<String, Object> analysis = rows.get(0);
    String type = String.valueOf(analysis.get("input_type"));
    db.update("UPDATE analyses SET status=?, updated_at=now() WHERE id=?", "PROCESSING", analysisId);
    db.update("UPDATE analysis_jobs SET status=?, locked_at=now(), updated_at=now() WHERE analysis_id=?", "PROCESSING", analysisId);
    // Restart recovery is idempotent: a reprocessed job must not keep partial claims,
    // evidence, or explanations from a previous attempt that died mid-processing.
    db.update("DELETE FROM evidence WHERE claim_id IN (SELECT id FROM claims WHERE analysis_id=?)", analysisId);
    db.update("DELETE FROM claims WHERE analysis_id=?", analysisId);
    db.update("DELETE FROM explanations WHERE analysis_id=?", analysisId);
    try {
      if ("IMAGE".equals(type)) {
        processImage(analysisId);
      } else if ("CLAIM".equals(type)) {
        processDirectClaim(analysisId, String.valueOf(analysis.get("input_text")));
      } else {
        processText(analysisId, String.valueOf(analysis.get("input_text")));
      }
      db.update("UPDATE analyses SET status=?, updated_at=now() WHERE id=?", "COMPLETED", analysisId);
      db.update("UPDATE analysis_jobs SET status=?, locked_at=NULL, updated_at=now() WHERE analysis_id=?", "COMPLETED", analysisId);
    } catch (Exception e) {
      db.update("UPDATE analyses SET status=?, error_message=?, updated_at=now() WHERE id=?", "FAILED", "AI service unavailable", analysisId);
      db.update(
          "UPDATE analysis_jobs SET status=?, last_error=?, attempts=attempts+1, locked_at=NULL, updated_at=now() WHERE analysis_id=?",
          "FAILED",
          e.getMessage(),
          analysisId);
    }
  }

  /**
   * Recovers abandoned jobs regardless of when the application restarted.
   * A job is considered abandoned only when its processing lease expired: the
   * worker heartbeat refreshes {@code locked_at} for jobs it is actually running,
   * so slow (but live) inference is never reclaimed. Runs periodically, not just
   * at startup.
   */
  public void recoverStale(int leaseSeconds) {
    // Re-queue jobs whose worker lease expired (worker died without a heartbeat).
    db.update(
        """
        UPDATE analysis_jobs SET status='SUBMITTED', locked_at=NULL, updated_at=now()
        WHERE status='PROCESSING'
          AND (locked_at IS NULL OR locked_at < now() - (? * interval '1 second'))
        """,
        leaseSeconds);
    // Re-queue owned analyses stuck mid-processing/validation past the lease.
    db.update(
        """
        UPDATE analyses SET status='SUBMITTED', updated_at=now()
        WHERE status IN ('PROCESSING','VALIDATING') AND owner_id IS NOT NULL
          AND updated_at < now() - (? * interval '1 second')
        """,
        leaseSeconds);
    // Guarantee every re-queued owned analysis has a job row the workers can claim.
    db.update(
        """
        INSERT INTO analysis_jobs(id, analysis_id, status)
        SELECT gen_random_uuid(), id, 'SUBMITTED'
        FROM analyses a
        WHERE a.status='SUBMITTED' AND a.owner_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM analysis_jobs j WHERE j.analysis_id = a.id)
        """);
  }

  /** Renews the lease of every job currently being processed by this worker. */
  public void refreshLease(String workerToken) {
    db.update(
        "UPDATE analysis_jobs SET locked_at=now(), updated_at=now() WHERE lock_owner=? AND status='PROCESSING'",
        workerToken);
  }

  public List<UUID> claimWork(int limit, String workerToken) {
    return db.query(
        """
        UPDATE analysis_jobs
        SET status='PROCESSING', locked_at=now(), lock_owner=?, updated_at=now()
        WHERE id IN (
          SELECT id FROM analysis_jobs
          WHERE status='SUBMITTED'
          ORDER BY created_at, id
          LIMIT ?
          FOR UPDATE SKIP LOCKED
        )
        RETURNING analysis_id
        """,
        (rs, i) -> rs.getObject(1, UUID.class),
        workerToken,
        limit);
  }

  private void processText(UUID analysisId, String stored) throws Exception {
    String title = "";
    String body = stored == null ? "" : stored;
    try {
      JsonNode payload = json.readTree(stored);
      if (payload.has("body")) {
        title = payload.path("title").asText("");
        body = payload.path("body").asText("");
      }
    } catch (Exception ignored) {
    }
    JsonNode classification = ai.classifyArticle(Map.of("title", title, "body", body));
    ObjectNode result = json.createObjectNode();
    if (classification instanceof ObjectNode objectNode) {
      result.setAll(objectNode);
    } else {
      result.set("classification", classification);
    }
    result.put("limitations_note", "WELFake classification is not evidence verification and is not a calibrated truth probability.");
    persistJson(analysisId, result);
    JsonNode extracted;
    try {
      extracted = ai.extractClaims(Map.of("title", title, "body", body));
    } catch (Exception e) {
      result.putObject("verification")
          .put("status", "UNAVAILABLE")
          .put("note", VERIFICATION_UNAVAILABLE_MESSAGE + " Claim extraction was unavailable.")
          .set("service_error", json.getNodeFactory().textNode(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      persistJson(analysisId, result);
      return;
    }
    List<JsonNode> candidates = new ArrayList<>();
    if (extracted.has("claims") && extracted.get("claims").isArray()) {
      extracted.get("claims").forEach(candidates::add);
    }
    verifyPersistedClaims(analysisId, candidates);
    explanation(analysisId, Map.of("title", title, "body", body));
    refreshResultJson(analysisId);
  }

  private void processDirectClaim(UUID analysisId, String claim) throws Exception {
    ObjectNode seed = json.createObjectNode();
    seed.put("label", "UNAVAILABLE");
    seed.put("note", "Direct claim submission does not run WELFake article classification.");
    persistJson(analysisId, seed);
    ObjectNode candidate = json.createObjectNode();
    candidate.put("text", claim);
    candidate.put("method", "direct-claim");
    List<JsonNode> direct = new ArrayList<>();
    direct.add(candidate);
    verifyPersistedClaims(analysisId, direct);
    refreshResultJson(analysisId);
  }

  private void processImage(UUID analysisId) throws Exception {
    var assets = db.queryForList("SELECT id,storage_path,content_type FROM assets WHERE analysis_id=? AND kind='UPLOAD'", analysisId);
    if (assets.isEmpty()) {
      throw new IllegalStateException("Missing upload");
    }
    Path path = storage.resolveOwned(String.valueOf(assets.get(0).get("storage_path")));
    DiskFile file = new DiskFile(path, String.valueOf(assets.get(0).get("content_type")));
    JsonNode classification = ai.classifyImage(file);
    ObjectNode result = json.createObjectNode();
    if (classification instanceof ObjectNode objectNode) {
      result.setAll(objectNode);
    } else {
      result.set("classification", classification);
    }
    try {
      JsonNode heatmap = ai.gradcam(file);
      result.set("gradcam", heatmap);
      db.update(
          "INSERT INTO explanations(id,analysis_id,kind,status,payload) VALUES (?,?,?,?,?::jsonb)",
          UUID.randomUUID(),
          analysisId,
          "GRADCAM",
          heatmap.path("status").asText("generated"),
          heatmap.toString());
    } catch (Exception e) {
      ObjectNode missing = json.createObjectNode();
      missing.put("status", "unavailable");
      result.set("gradcam", missing);
    }
    persistJson(analysisId, result);
  }

  private void verifyPersistedClaims(UUID analysisId, List<JsonNode> candidates) {
    int count = 0;
    for (JsonNode candidate : candidates) {
      if (count >= MAX_CLAIMS) {
        break;
      }
      String text = candidate.path("text").asText("").trim();
      if (text.isBlank()) {
        continue;
      }
      UUID claimId = UUID.randomUUID();
      db.update(
          "INSERT INTO claims(id,analysis_id,claim_text,sentence_index,relevance,method,component_status) VALUES (?,?,?,?,?,?,?)",
          claimId,
          analysisId,
          text,
          candidate.path("sentence_index").isNumber() ? candidate.get("sentence_index").asInt() : null,
          candidate.path("relevance").isNumber() ? candidate.get("relevance").asDouble() : null,
          candidate.path("method").asText("deterministic heuristic baseline"),
          "PROCESSING");
      try {
        JsonNode verified = ai.verify(text);
        String verdict = verified.path("verdict").asText("INSUFFICIENT");
        if (verified.path("evidence").isArray() && verified.get("evidence").isEmpty()) {
          verdict = "INSUFFICIENT";
        }
        db.update(
            "UPDATE claims SET verdict=?, component_status=?, nli_output=?::jsonb WHERE id=?",
            verdict,
            "COMPLETED",
            verified.toString(),
            claimId);
        if (verified.path("evidence").isArray()) {
          for (JsonNode passage : verified.get("evidence")) {
            var provenance = json.createObjectNode()
                .put("model", verified.path("model").asText("FEVER Iteration 2"))
                .put("corpus", "local FEVER Iteration 2 repository; not live web search");
            String rejection = passage.path("rejected").asText("");
            if (!rejection.isBlank()) provenance.put("rejection", rejection);
            db.update(
                """
                INSERT INTO evidence(id,claim_id,page_id,sentence_id,passage,similarity,overlap,nli_scores,nli_labels,selected,provenance)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb)
                """,
                UUID.randomUUID(),
                claimId,
                passage.path("page_id").asText(null),
                passage.path("sentence_id").isNumber() ? passage.get("sentence_id").asInt() : null,
                passage.path("text").asText(""),
                passage.path("similarity").isNumber() ? passage.get("similarity").asDouble() : null,
                passage.path("overlap").isNumber() ? passage.get("overlap").asDouble() : null,
                passage.path("nli_scores").toString(),
                passage.path("nli_labels").toString(),
                passage.path("selected").asBoolean(false),
                provenance.toString());
          }
        }
      } catch (Exception e) {
        // A failed verification call is not an evidence finding: store no verdict, so INSUFFICIENT
        // stays reserved for a completed search that found no conclusive evidence.
        db.update(
            "UPDATE claims SET verdict=NULL, component_status=?, nli_output=?::jsonb WHERE id=?",
            "UNAVAILABLE",
            verificationFailure(e).toString(),
            claimId);
      }
      count++;
    }
  }

  private ObjectNode verificationFailure(Exception cause) {
    ObjectNode node = json.createObjectNode();
    node.put("status", "UNAVAILABLE");
    node.put("error", VERIFICATION_UNAVAILABLE_MESSAGE);
    node.put("reason", VERIFICATION_UNAVAILABLE_MESSAGE
        + " The evidence service call failed, so no verdict was produced and no evidence was retrieved. "
        + "This is a service failure, not an evidence-based INSUFFICIENT result.");
    String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    node.put("service_error", detail.length() > 200 ? detail.substring(0, 200) : detail);
    return node;
  }

  private void explanation(UUID analysisId, Map<String, String> input) {
    UUID explanationId = UUID.randomUUID();
    db.update(
        "INSERT INTO explanations(id,analysis_id,kind,status,payload) VALUES (?,?,?,?,?::jsonb)",
        explanationId,
        analysisId,
        "SHAP",
        "PENDING",
        json.createObjectNode().put("note", "Explanation is generated separately from classification.").toString());
    try {
      JsonNode shap = ai.shap(input);
      db.update(
          "UPDATE explanations SET status=?, payload=?::jsonb WHERE id=?",
          shap.path("status").asText("generated"),
          shap.toString(),
          explanationId);
    } catch (Exception e) {
      db.update(
          "UPDATE explanations SET status=?, payload=?::jsonb WHERE id=?",
          "UNAVAILABLE",
          json.createObjectNode().put("error", "Explanation unavailable").toString(),
          explanationId);
    }
  }

  private void refreshResultJson(UUID analysisId) {
    var rows = db.queryForList("SELECT result_json FROM analyses WHERE id=?", analysisId);
    ObjectNode result = json.createObjectNode();
    if (!rows.isEmpty() && rows.get(0).get("result_json") != null) {
      try {
        JsonNode existing = json.readTree(String.valueOf(rows.get(0).get("result_json")));
        if (existing instanceof ObjectNode objectNode) {
          result = objectNode;
        }
      } catch (Exception ignored) {
      }
    }
    ObjectNode verification = json.createObjectNode();
    List<Map<String, Object>> claims = listClaims(analysisId);
    long failed = claims.stream()
        .filter(c -> "UNAVAILABLE".equals(String.valueOf(c.get("component_status"))))
        .count();
    verification.put("status", failed == 0 ? "COMPLETED" : (failed == claims.size() ? "UNAVAILABLE" : "PARTIAL"));
    verification.put("coverage", "Limited local FEVER repository; not live web search. Missing evidence is INSUFFICIENT, not false.");
    verification.put("note", "Verification is separate from WELFake classification and is not a combined truth probability.");
    if (failed > 0) {
      verification.put("failed_claims", failed);
      verification.put("total_claims", claims.size());
      verification.put("unavailable_note", VERIFICATION_UNAVAILABLE_MESSAGE
          + " " + failed + " of " + claims.size()
          + " claim(s) could not be verified because the evidence service call failed. "
          + "Those claims carry no verdict and are not evidence-based INSUFFICIENT results.");
    }
    ArrayNode claimNodes = verification.putArray("claims");
    for (Map<String, Object> claim : claims) {
      claimNodes.add(json.valueToTree(claim));
    }
    result.set("verification", verification);
    var explanations = db.queryForList("SELECT kind,status,payload FROM explanations WHERE analysis_id=? AND kind='SHAP'", analysisId);
    if (!explanations.isEmpty()) {
      result.set("explanation", json.valueToTree(explanations.get(0)));
    }
    persistJson(analysisId, result);
  }

  private List<Map<String, Object>> listClaims(UUID analysisId) {
    List<Map<String, Object>> claims = db.queryForList(
        "SELECT id,claim_text,sentence_index,relevance,method,verdict,component_status,nli_output FROM claims WHERE analysis_id=? ORDER BY created_at",
        analysisId);
    for (Map<String, Object> claim : claims) {
      claim.put("nli_output", jsonValue(claim.get("nli_output")));
      claim.put(
          "evidence",
          db.queryForList(
              "SELECT id,page_id,sentence_id,passage,similarity,overlap,nli_scores,nli_labels,selected,provenance FROM evidence WHERE claim_id=?",
              claim.get("id"))
              .stream()
              .map(row -> {
                row.put("nli_scores", jsonValue(row.get("nli_scores")));
                row.put("nli_labels", jsonValue(row.get("nli_labels")));
                row.put("provenance", jsonValue(row.get("provenance")));
                return row;
              })
              .toList());
    }
    return claims;
  }

  private void persistJson(UUID analysisId, ObjectNode result) {
    db.update("UPDATE analyses SET result_json=?::jsonb, updated_at=now() WHERE id=?", result.toString(), analysisId);
  }

  private void enqueue(UUID analysisId) {
    db.update(
        "INSERT INTO analysis_jobs(id,analysis_id,status) VALUES (?,?,?)",
        UUID.randomUUID(),
        analysisId,
        "SUBMITTED");
    events.publishEvent(new JobEnqueued(analysisId));
  }

  private String currentStatus(UUID id) {
    return db.queryForObject("SELECT status FROM analyses WHERE id=?", String.class, id);
  }

  private boolean isPending(UUID id) {
    String status = db.query("SELECT status FROM analyses WHERE id=?", (rs, i) -> rs.getString(1), id)
        .stream()
        .findFirst()
        .orElse(null);
    return status != null && (status.equals("SUBMITTED") || status.equals("VALIDATING"));
  }

  private void audit(UUID actor, String action, UUID entity) {
    db.update("INSERT INTO audit_events(id,actor_id,action,entity_id,created_at) VALUES (?,?,?,?,?)", UUID.randomUUID(), actor, action, entity, OffsetDateTime.now());
  }
}
