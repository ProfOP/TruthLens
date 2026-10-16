package ai.truthlens;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StorageService {
  private final Path root;

  public StorageService(@Value("${truthlens.storage.dir:../storage}") String configured) {
    this.root = Path.of(configured).toAbsolutePath().normalize();
  }

  public Path analysisDir(UUID analysisId) throws IOException {
    Path dir = root.resolve("analyses").resolve(analysisId.toString()).normalize();
    if (!dir.startsWith(root)) {
      throw new IOException("Invalid storage path");
    }
    Files.createDirectories(dir);
    return dir;
  }

  public StoredFile saveUpload(UUID analysisId, MultipartFile file) throws IOException {
    String extension = extension(file.getOriginalFilename(), file.getContentType());
    Path target = analysisDir(analysisId).resolve("upload-" + UUID.randomUUID() + extension).normalize();
    if (!target.startsWith(root)) {
      throw new IOException("Invalid storage path");
    }
    Path temp = Files.createTempFile(analysisDir(analysisId), "tmp-", ".part");
    try (InputStream in = file.getInputStream()) {
      Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      Files.deleteIfExists(temp);
      Files.deleteIfExists(target);
      throw e;
    }
    return new StoredFile(target, relative(target), file.getContentType());
  }

  public Path resolveOwned(String relativePath) throws IOException {
    Path resolved = root.resolve(relativePath).toAbsolutePath().normalize();
    if (!resolved.startsWith(root) || !Files.exists(resolved)) {
      throw new IOException("Asset not found");
    }
    return resolved;
  }

  public void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  private String relative(Path path) {
    return root.relativize(path).toString().replace('\\', '/');
  }

  private static String extension(String name, String contentType) {
    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".png") || "image/png".equals(contentType)) {
      return ".png";
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || "image/jpeg".equals(contentType)) {
      return ".jpg";
    }
    return ".bin";
  }

  public record StoredFile(Path path, String relativePath, String contentType) {}
}
