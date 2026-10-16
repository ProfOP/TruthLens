package ai.truthlens;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public class DiskFile implements MultipartFile {
  private final Path path;
  private final String contentType;

  public DiskFile(Path path, String contentType) {
    this.path = path;
    this.contentType = contentType;
  }

  @Override
  public String getName() {
    return "file";
  }

  @Override
  public String getOriginalFilename() {
    return path.getFileName().toString();
  }

  @Override
  public String getContentType() {
    return contentType;
  }

  @Override
  public boolean isEmpty() {
    return getSize() == 0;
  }

  @Override
  public long getSize() {
    try {
      return Files.size(path);
    } catch (IOException e) {
      return 0;
    }
  }

  @Override
  public byte[] getBytes() throws IOException {
    return Files.readAllBytes(path);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return new FileInputStream(path.toFile());
  }

  @Override
  public Resource getResource() {
    return new FileSystemResource(path);
  }

  @Override
  public void transferTo(File dest) throws IOException, IllegalStateException {
    Files.copy(path, dest.toPath());
  }
}
