package org.apache.airavata.dataorchestrator.file.client.watcher;

import org.apache.airavata.dataorchestrator.clients.core.AbstractListener;
import org.apache.airavata.dataorchestrator.file.client.model.Configuration;
import org.apache.airavata.dataorchestrator.file.client.model.FileEvent;
import org.apache.airavata.dataorchestrator.messaging.Constants;
import org.apache.airavata.dataorchestrator.messaging.model.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watch for given folder path and notify changes
 */
public class FileWatcher implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileWatcher.class);
    private List<AbstractListener> listeners = new ArrayList<>();

    private final File rootFolder;

    private static Map<WatchKey, Path> keyPathMap = new HashMap<>();

    private Configuration configuration;

    public FileWatcher(File rootFolder, Configuration configuration) throws IOException {
        this.rootFolder = rootFolder;
        this.configuration = configuration;
    }

    @Override
    public void run() {

        LOGGER.info("Watcher service starting at " + rootFolder.getAbsolutePath());
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Path path = Paths.get(rootFolder.getAbsolutePath());
            registerDir(path, watchService);
            while (true) {
                pollEvents(watchService);
            }

        } catch (Exception e) {
            LOGGER.error("Error occurred while watching  folder " + rootFolder.getAbsolutePath(), e);
            Thread.currentThread().interrupt();
        }
    }

    protected void pollEvents(WatchService watchService) throws Exception {

        WatchKey key = watchService.take();

        for (WatchEvent<?> event : key.pollEvents()) {
            notifyListeners(watchService, event.kind(), event, key);
        }

        if (!key.reset()) {
            keyPathMap.remove(key);
        }

        if (keyPathMap.isEmpty()) {
            return;
        }
    }


    protected void notifyListeners(WatchService watchService, WatchEvent.Kind<?> kind, WatchEvent keyEvent, WatchKey key) throws Exception {
        Path path = (Path) keyEvent.context();

        Path parentPath = keyPathMap.get(key);

        path = parentPath.resolve(path);
        File file = path.toFile();
        Optional<FileEvent> event = getFileEvent(file);

        if (kind == ENTRY_CREATE) {

            if (event.isPresent()) {
                for (AbstractListener listener : listeners) {
                    listener.onCreated(event.get());
                }
            }

            if (file.isDirectory()) {
                registerDir(path, watchService);
            }

        } else if (kind == ENTRY_MODIFY) {

            if (event.isPresent()) {
                for (AbstractListener listener : listeners) {
                    listener.onModified(event.get());
                }
            }

        } else if (kind == ENTRY_DELETE) {

            if (event.isPresent()) {
                for (AbstractListener listener : listeners) {
                    listener.onDeleted(event.get());
                }
            }
        }
    }

    public FileWatcher addListener(AbstractListener listener) {

        listeners.add(listener);

        return this;

    }


    public FileWatcher removeListener(AbstractListener listener) {

        listeners.remove(listener);

        return this;

    }


    public List<AbstractListener> getListeners() {

        return listeners;

    }


    public FileWatcher setListeners(List<AbstractListener> listeners) {

        this.listeners = listeners;

        return this;

    }


    /**
     * Register the given directory and all its sub-directories with the WatchService.
     */


    protected Optional<FileEvent> getFileEvent(File file) {
        FileEvent event = new FileEvent();


        String absolutePath = file.getAbsolutePath();
        if (configuration.getDepth() > 0) {
            String relativePath = absolutePath.substring(configuration.getListeningPath().length());
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            String[] relativeParts = relativePath.split("/");
            if (relativeParts.length >= configuration.getDepth()) {
                String beginPath = configuration.getListeningPath();
                beginPath = beginPath.endsWith("/") ? beginPath.substring(0, beginPath.length()-1) : beginPath;
                for (int step = 0; step < configuration.getDepth(); step++) {
                    beginPath = beginPath + "/" + relativeParts[step];
                }
                absolutePath = beginPath;
            } else {
                LOGGER.warn("Depth of path {} is not greater or equal to required depth {}", absolutePath, configuration.getDepth());
                return Optional.empty();
            }
        }

        if (new File(absolutePath).isDirectory()) {
            event.setResourceType(Constants.FOLDER);
        } else {
            event.setResourceType(Constants.FILE);
        }

        event.setResourcePath(absolutePath);
        event.setOccuredTime(System.currentTimeMillis());
        event.setAuthToken(Base64.getEncoder().encodeToString((configuration.getCustos().getServiceAccountId()
                + ":" + configuration.getCustos().getServiceAccountSecret()).getBytes(StandardCharsets.UTF_8)));
        event.setBasePath(configuration.getListeningPath());
        event.setTenantId(configuration.getCustos().getTenantId());
        event.setHostName(configuration.getHostName());
        return Optional.of(event);
    }

    private static void registerDir(Path path, WatchService watchService) throws
            IOException {


        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        LOGGER.info("registering path: " + path);

        WatchKey key = path.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keyPathMap.put(key, path);


        for (File f : path.toFile().listFiles()) {
            registerDir(f.toPath(), watchService);
        }
    }

}
