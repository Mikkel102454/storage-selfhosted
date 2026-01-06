package server.phoestorage.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.io.input.BoundedInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import server.phoestorage.classes.UploadState;
import server.phoestorage.datasource.file.FileEntity;
import server.phoestorage.datasource.file.FileRepository;
import server.phoestorage.datasource.folder.FolderRepository;
import server.phoestorage.datasource.users.UserEntity;
import server.phoestorage.datasource.users.UserRepository;
import server.phoestorage.dto.FileEntry;

@Service
public class FileService {
    private final FolderRepository folderRepository;
    private final UserRepository userRepository;
    @Value("${server.root}")
    private String rootPath;

    private final AppUserDetailsService appUserDetailsService;
    private final HandlerService handlerService;

    private final FileRepository fileRepository;

    public static final ConcurrentHashMap<String, UploadState> ongoingUploads = new ConcurrentHashMap<>();


    @Autowired
    public FileService(AppUserDetailsService appUserDetailsService,
                       HandlerService handlerService,
                       FileRepository fileRepository, FolderRepository folderRepository, UserRepository userRepository) {
        this.appUserDetailsService = appUserDetailsService;
        this.handlerService = handlerService;
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
    }

    /**
     * Saves chunks of a file
     *
     * @param chunkId the index of the current chunk
     * @param file the chunked file
     * @param folderId the folder to save the file into
     * @param fileName the name of the file
     * @param uploadId the current upload session id
     * @return the exit code
     *
     */
    public int saveChunk(int chunkId, int totalChunks, MultipartFile file, String folderId, String fileName, String uploadId, long constChunkSize){
        try{
            String uuid = appUserDetailsService.getUserEntity().getUuid();
            if(fileExistByName(uuid, folderId, fileName)) {return -2;}

            Path storageDir = Paths.get(rootPath, uuid, "storage");
            Files.createDirectories(storageDir);


            Path finalFile = Paths.get(rootPath, uuid, "storage", uploadId + ".lock");

            long offset = (long) chunkId * constChunkSize;

            try (InputStream is = file.getInputStream();
                 FileChannel ch = FileChannel.open(
                         finalFile,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE
                 )) {

                try (FileLock lock = ch.lock(offset, Math.max(1L, file.getSize()), false)) {

                    ch.position(offset);

                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = is.read(buffer)) != -1) {
                        ByteBuffer buf = ByteBuffer.wrap(buffer, 0, n);
                        while (buf.hasRemaining()) {
                            ch.write(buf);
                        }
                    }

                    ch.force(false); // flush file contents
                }
            }

            UserEntity userEntity = appUserDetailsService.getUserEntity();

            if(userEntity.getDataUsed() + finalFile.toFile().length() > userEntity.getDataLimit()) {
                return -3;
            }

            UploadState state = ongoingUploads.computeIfAbsent(
                    uuid + ":" + uploadId,
                    id -> new UploadState(totalChunks)
            );

            boolean complete;

            synchronized (state) {
                if (!state.received.get(chunkId)) {
                    state.received.set(chunkId);
                    state.receivedCount.incrementAndGet();
                }
                complete = state.receivedCount.get() == state.totalChunks;
            }
            if(complete){
                return saveFileDatabase(folderId, fileName, finalFile);
            }


            return 0;
        } catch (Exception e){
            e.printStackTrace();
            return -1;
        }
    }

    /**
     * Saves file to database
     *
     * @param folderId the folder the file should be saved in
     * @param fileName the name of the saved file
     * @param filePath path to the name of the file being saved
     * @return exit code
     *
     */
    public int saveFileDatabase(String folderId, String fileName, Path filePath) {
        try{
            String uuid = appUserDetailsService.getUserEntity().getUuid();
            String fileUuid = filePath.toFile().getName().split("\\.")[0];
            if(fileExistByUuid(uuid, folderId, fileUuid)) {Files.delete(filePath); return 409;}
            if(folderRepository.findByOwnerAndFolderId(uuid, folderId) == null) {Files.delete(filePath); return 404;}

            String extension;

            String[] parts = fileName.split("\\.");
            if (parts.length > 1) {
                extension = parts[parts.length - 1].toLowerCase();
            } else {
                extension = "";
            }

            Path movedPath = filePath.resolveSibling(fileUuid);
            Files.move(filePath, movedPath);

            FileEntity fileEntity = new FileEntity();
            fileEntity.setUuid(fileUuid);
            fileEntity.setOwner(uuid);
            fileEntity.setName(fileName);
            fileEntity.setExtension(extension);
            fileEntity.setFolderId(folderId);
            fileEntity.setInternalPath(movedPath.toString());
            fileEntity.setCreated(LocalDateTime.now().toString());
            fileEntity.setSize(Files.size(movedPath));
            fileEntity.setStarred(false);

            UserEntity userEntity = appUserDetailsService.getUserEntity();
            userEntity.setDataUsed(userEntity.getDataUsed() + Files.size(movedPath));

            fileRepository.save(fileEntity);
            return 0;
        }catch (Exception e){
            System.err.println(e);

            return 500;
        }
    }

    /**
     * Downloads the file
     *
     * @param folderId the folder the file is in
     * @param fileId the id of the file
     * @param rangeHeader the folder the file should be saved in
     * @return response entity
     *
     */
    public ResponseEntity<?> downloadFile(String folderId, String fileId, String rangeHeader, String uuid) {
        try{

            if(!fileExistByUuid(uuid, folderId, fileId)) {return ResponseEntity.status(HttpStatus.NOT_FOUND).body(handlerService.get404());}

            Optional<FileEntity> fileEntity = fileRepository.findByOwnerAndFolderIdAndUuid(uuid, folderId, fileId);

            Path file = Paths.get(fileEntity.get().getInternalPath()); // warning is irrelevant because we call fileExistByUuid()
            Resource resource = new UrlResource(file.toUri());

            if (!resource.exists()) {
                System.out.print("Could not find file in local files. send 404");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(handlerService.get404());
            }

            long fileSize = Files.size(file);
            long start = 0, end = fileSize - 1;

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] ranges = rangeHeader.replace("bytes=", "").split("-");
                start = Long.parseLong(ranges[0]);
                if (ranges.length > 1 && !ranges[1].isEmpty()) {
                    end = Long.parseLong(ranges[1]);
                }
            }

            long contentLength = end - start + 1;
            InputStream inputStream = Files.newInputStream(file);

            long skipped = 0;
            while (skipped < start) {
                long bytes = inputStream.skip(start - skipped);
                if (bytes <= 0) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(handlerService.get500(new Exception()));
                skipped += bytes;
            }

            BoundedInputStream limited = new BoundedInputStream(inputStream, contentLength);
            InputStreamResource inputStreamResource = new InputStreamResource(limited);

            return ResponseEntity.status(rangeHeader == null ? 200 : 206)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileEntity.get().getName() + "\"")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentLength(contentLength)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(inputStreamResource);
        }catch (Exception e){
            System.err.println(e.getMessage() + "\n With Cause:\n" + e.getCause());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(handlerService.get500(e));
        }
    }

    public int moveFile(String fileId, String folderId, String newFolderUuid){
        try{
            String uuid = appUserDetailsService.getUserEntity().getUuid();

            Optional<FileEntity> fileEntity = fileRepository.findByOwnerAndFolderIdAndUuid(uuid, folderId, fileId);
            if(fileEntity.isEmpty()) return 404;

            if(folderRepository.findByOwnerAndUuid(uuid, newFolderUuid).isEmpty()) return 2;



            FileEntity file = fileEntity.get();

            if(fileExistByName(uuid, newFolderUuid, file.getName())) return 409;

            file.setFolderId(newFolderUuid);
            fileRepository.save(file);
            return 0;

        } catch (Exception e) {
            System.err.println(e);
            return 500;
        }
    }

    /**
     * Deletes the file local and on database
     *
     * @param folderId the folder the file is in
     * @param fileId the id of the file
     * @return response entity
     *
     */
    public ResponseEntity<?> deleteFile(String folderId, String fileId) {
        try{
            String uuid = appUserDetailsService.getUserEntity().getUuid();

            if (!fileExistByUuid(uuid, folderId, fileId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(handlerService.get404());
            }

            Optional<FileEntity> response = fileRepository.findByOwnerAndFolderIdAndUuid(uuid, folderId, fileId);
            FileEntity fileEntity = response.get();
            if (Files.exists(Paths.get(fileEntity.getInternalPath()))) {
                Files.delete(Paths.get(fileEntity.getInternalPath()));
            } else { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(handlerService.get404());}

            fileRepository.delete(fileEntity);

            UserEntity userEntity = appUserDetailsService.getUserEntity();
            userEntity.setDataUsed(userEntity.getDataUsed() - fileEntity.getSize());
            userRepository.save(userEntity);

            return ResponseEntity.ok().build();
        }catch (Exception e){
            System.err.println(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(handlerService.get500(e));
        }
    }

    public ResponseEntity<?> renameFile(String folderId, String fileId, String name) {
        try {
            String uuid = appUserDetailsService.getUserEntity().getUuid();

            if(!fileExistByUuid(uuid, folderId, fileId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(handlerService.get404());
            }

            if(fileRepository.findByOwnerAndFolderIdAndUuid(uuid, folderId, fileId).get().getName().equals(name)) {
                return ResponseEntity.ok().body("");
            }

            if(fileExistByName(uuid, folderId, name)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(name);
            }

            String extension;

            String[] parts = name.split("\\.");
            if (parts.length > 1) {
                extension = parts[parts.length - 1].toLowerCase();
            } else {
                extension = "";
            }

            if(fileRepository.renameFile(uuid, folderId, fileId, name, extension) != 1){
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(handlerService.get500(new Exception()));
            }
            return ResponseEntity.ok().build();
        } catch (Exception e){
            System.err.println(e.getMessage() + "\n With Cause:\n" + e.getCause());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(handlerService.get500(e));
        }
    }

    /**
     * File exist with uuid
     *
     * @param folderId the parent folder
     * @param fileId uuid of the file
     * @return exit code
     *
     */
    public boolean fileExistByUuid(String owner, String folderId, String fileId) throws IOException {
        Optional<FileEntity> fileEntity = fileRepository.findByOwnerAndFolderIdAndUuid(owner, folderId, fileId);

        if(fileEntity.isEmpty()){ return false; }

        Path file = Paths.get(fileEntity.get().getInternalPath());
        Resource resource = new UrlResource(file.toUri());

        return resource.exists();
    }

    /**
     * File exist with name
     *
     * @param folderId the parent folder
     * @param fileName name of the file
     * @return exit code
     *
     */
    public boolean fileExistByName(String owner, String folderId, String fileName) {
        Optional<FileEntity> fileEntity = fileRepository.findByOwnerAndFolderIdAndName(owner, folderId, fileName);

        return fileEntity.isPresent();

    }


    public ResponseEntity<?> getStarredFiles() {
        try{
            String uuid = appUserDetailsService.getUserEntity().getUuid();
            return ResponseEntity.ok(fileRepository.findByOwnerAndStarred(uuid, true));
        }catch (Exception e){
            System.err.println(e.getMessage() + "\n With Cause:\n" + e.getCause());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(handlerService.get500(e));
        }
    }

    public ResponseEntity<?> setStarredFile(String folderId, String fileId, boolean starred) {
        try{
            String uuid = appUserDetailsService.getUserEntity().getUuid();

            if(!fileExistByUuid(uuid, folderId, fileId)) {return ResponseEntity.status(HttpStatus.NOT_FOUND).body(handlerService.get404());}

            FileEntity file = fileRepository.findByOwnerAndFolderIdAndUuid(uuid, folderId, fileId).get();
            file.setStarred(starred);
            fileRepository.save(file);

            return ResponseEntity.ok("");
        }catch (Exception e){
            System.err.println(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(handlerService.get500(e));
        }
    }

    public List<FileEntry> searchFile(String query){
        String uuid = appUserDetailsService.getUserEntity().getUuid();

        List<FileEntity> result = new ArrayList<>();
        if(query.startsWith(".")){
            query = query.substring(1);
            result = fileRepository.findTop100ByOwnerAndExtensionContainingIgnoreCase(uuid, query);
        }else{
            result = fileRepository.findTop100ByOwnerAndNameContainingIgnoreCase(uuid, query);
        }

        List<FileEntry> r = new ArrayList<>();
        for (FileEntity fileEntity : result) {
            FileEntry fileEntry = new FileEntry();
            fileEntry.setUuid(fileEntity.getUuid());
            fileEntry.setOwner(fileEntity.getOwner());
            fileEntry.setName(fileEntity.getName());
            fileEntry.setExtension(fileEntity.getExtension());
            fileEntry.setFolderId(fileEntity.getFolderId());
            fileEntry.setCreated(fileEntity.getCreated());
            fileEntry.setModified(fileEntity.getModified());
            fileEntry.setAccessed(fileEntity.getAccessed());
            fileEntry.setSize(fileEntity.getSize());
            fileEntry.setStarred(fileEntity.getStarred());

            r.add(fileEntry);
        }
        return r;
    }
}
