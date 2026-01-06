package server.phoestorage.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class UGCService {
    @Value("${server.root}")
    private String rootFolder;
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(5);

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void startUGC(){
        System.out.println("Starting UGC service");
        try (Stream<Path> userDirs = Files.list(Paths.get(rootFolder))) {
            Instant now = Instant.now(); // Get time now
            userDirs
                    .filter(Files::isDirectory)
                    .forEach(userDirectory -> {
                        Path uploadsRoot = Paths.get(userDirectory.toString(), "storage");
                        // Check if the folder exists

                        if (Files.exists(uploadsRoot)) {
                            try (Stream<Path> uploadDirs = Files.list(uploadsRoot)) {
                                uploadDirs
                                        .filter(path -> path.getFileName().toString().endsWith(".lock"))
                                        .filter(path -> isExpired(path, now))
                                        .forEach(this::deletePathSilently);
                            } catch (IOException e) {
                                System.out.println(e.getMessage());
                            }
                        }
                    });

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isExpired(Path dir, Instant now) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(dir);
            return lastModified.toInstant().plus(UPLOAD_TIMEOUT).isBefore(now);
        } catch (IOException e) {
            return false;
        }
    }

    private void deletePathSilently(Path path) {
        try {
            if (Files.isDirectory(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {}
                        });
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {}
    }
}
