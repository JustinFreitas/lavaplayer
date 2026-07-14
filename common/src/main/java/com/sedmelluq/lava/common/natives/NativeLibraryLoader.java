package com.sedmelluq.lava.common.natives;

import com.sedmelluq.lava.common.natives.architecture.SystemType;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.function.Predicate;

import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;
import static java.nio.file.attribute.PosixFilePermissions.fromString;

/**
 * Loads native libraries by name. Libraries are expected to be in classpath /natives/[arch]/[prefix]name[suffix]
 */
public class NativeLibraryLoader {
    private static final Logger log = LoggerFactory.getLogger(NativeLibraryLoader.class);

    private static final String DEFAULT_PROPERTY_PREFIX = "lava.native.";
    private static final String DEFAULT_RESOURCE_ROOT = "/natives/";

    // Extraction directories are named after their creation time (currentTimeMillis) plus a random
    // suffix. Only reap ones older than this, so a concurrently-starting JVM sharing the base
    // directory isn't robbed of its fresh extract.
    private static final long STALE_DIRECTORY_THRESHOLD_MS = 10 * 60 * 1000L; // 10 minutes

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String libraryName;
    private final Predicate<SystemType> systemFilter;
    private final NativeLibraryProperties properties;
    private final NativeLibraryBinaryProvider binaryProvider;
    private final Object lock;
    private volatile LoadResult previousResult;

    public NativeLibraryLoader(String libraryName, Predicate<SystemType> systemFilter, NativeLibraryProperties properties,
                               NativeLibraryBinaryProvider binaryProvider) {

        this.libraryName = libraryName;
        this.systemFilter = systemFilter;
        this.binaryProvider = binaryProvider;
        this.properties = properties;
        this.lock = new Object();
    }

    public static NativeLibraryLoader create(Class<?> classLoaderSample, String libraryName) {
        return createFiltered(classLoaderSample, libraryName, null);
    }

    public static NativeLibraryLoader createFiltered(Class<?> classLoaderSample, String libraryName,
                                                     Predicate<SystemType> systemFilter) {

        return new NativeLibraryLoader(
            libraryName,
            systemFilter,
            new SystemNativeLibraryProperties(libraryName, DEFAULT_PROPERTY_PREFIX),
            new ResourceNativeLibraryBinaryProvider(classLoaderSample, DEFAULT_RESOURCE_ROOT)
        );
    }

    public void load() {
        LoadResult result = previousResult;

        if (result == null) {
            synchronized (lock) {
                result = previousResult;

                if (result == null) {
                    result = loadWithFailureCheck();
                    previousResult = result;
                }
            }
        }

        if (!result.success) {
            throw result.exception;
        }
    }

    private LoadResult loadWithFailureCheck() {
        log.info("Native library {}: loading with filter {}", libraryName, systemFilter);

        try {
            loadInternal();
            return new LoadResult(true, null);
        } catch (Throwable e) {
            log.error("Native library {}: loading failed.", libraryName, e);
            return new LoadResult(false, new RuntimeException(e));
        }
    }

    private void loadInternal() {
        String explicitPath = properties.getLibraryPath();

        if (explicitPath != null) {
            log.debug("Native library {}: explicit path provided {}", libraryName, explicitPath);

            loadFromFile(Paths.get(explicitPath).toAbsolutePath());
        } else {
            SystemType systemType = detectMatchingSystemType();

            if (systemType != null) {
                String explicitDirectory = properties.getLibraryDirectory();

                if (explicitDirectory != null) {
                    log.debug("Native library {}: explicit directory provided {}", libraryName, explicitDirectory);

                    loadFromFile(Paths.get(explicitDirectory, systemType.formatLibraryName(libraryName)).toAbsolutePath());
                } else {
                    loadFromFile(extractLibraryFromResources(systemType));
                }
            }
        }
    }

    private void loadFromFile(Path libraryFilePath) {
        log.debug("Native library {}: attempting to load library at {}", libraryName, libraryFilePath);
        System.load(libraryFilePath.toAbsolutePath().toString());
        log.info("Native library {}: successfully loaded.", libraryName);
    }

    private Path extractLibraryFromResources(SystemType systemType) {
        try (InputStream libraryStream = binaryProvider.getLibraryStream(systemType, libraryName)) {
            if (libraryStream == null) {
                throw new UnsatisfiedLinkError("Required library was not found");
            }

            Path extractedLibraryPath = prepareExtractionDirectory().resolve(systemType.formatLibraryName(libraryName));

            try (FileOutputStream fileStream = new FileOutputStream(extractedLibraryPath.toFile())) {
                IOUtils.copy(libraryStream, fileStream);
            }

            return extractedLibraryPath;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path prepareExtractionDirectory() throws IOException {
        Path baseDir = detectExtractionBaseDirectory();
        cleanStaleDirectories(baseDir);

        // Unpredictable, owner-only directory: the library is loaded with System.load() from here, so
        // on a shared system a predictable or world-writable path would let another local user plant
        // or swap the binary between extraction and load.
        Path extractionDirectory = baseDir.resolve(System.currentTimeMillis() + "-" + Long.toHexString(RANDOM.nextLong()));

        log.debug("Native library {}: using extraction directory {}.", libraryName, extractionDirectory);

        try {
            createPrivateDirectories(extractionDirectory);
        } catch (IOException e) {
            // The shared base directory may have been created by another user and not be writable for
            // us - fall back to a private directory directly under the temp directory.
            try {
                Path fallback = Files.createTempDirectory("lava-jni-natives-");
                log.debug("Native library {}: falling back to extraction directory {}.", libraryName, fallback);
                return fallback;
            } catch (IOException fallbackException) {
                fallbackException.addSuppressed(e);
                throw new IOException("Failed to create directory for unpacked native library.", fallbackException);
            }
        }

        return extractionDirectory;
    }

    private static void cleanStaleDirectories(Path baseDir) {
        if (!Files.exists(baseDir)) {
            return;
        }

        long now = System.currentTimeMillis();

        try (java.util.stream.Stream<Path> stream = Files.list(baseDir)) {
            stream.forEach(path -> {
                if (!Files.isDirectory(path)) {
                    return;
                }

                // Directory names are "<timestamp>" (legacy) or "<timestamp>-<random>".
                String name = path.getFileName().toString();
                int separator = name.indexOf('-');

                long timestamp;
                try {
                    timestamp = Long.parseLong(separator >= 0 ? name.substring(0, separator) : name);
                } catch (NumberFormatException ignored) {
                    // Ignore directories that don't match the timestamp naming pattern
                    return;
                }

                // Skip recently-created directories: another JVM instance sharing this base directory may
                // have just extracted its libraries there and not loaded them yet, so deleting them would
                // break that process. Only reap directories comfortably older than any live startup.
                if (now - timestamp < STALE_DIRECTORY_THRESHOLD_MS) {
                    return;
                }

                try {
                    deleteDirectoryRecursively(path);
                } catch (IOException e) {
                    // Ignore files/directories that are locked or cannot be deleted
                    log.trace("Failed to clean stale native library directory {}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("Failed to list files in base directory for stale library cleaning", e);
        }
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
        }
    }

    private Path detectExtractionBaseDirectory() {
        String explicitExtractionBase = properties.getExtractionPath();

        if (explicitExtractionBase != null) {
            log.debug("Native library {}: explicit extraction path provided - {}", libraryName, explicitExtractionBase);
            return Paths.get(explicitExtractionBase).toAbsolutePath();
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir", "/tmp"), "lava-jni-natives")
            .toAbsolutePath();

        log.debug("Native library {}: detected {} as base directory for extraction.", libraryName, path);
        return path;
    }

    private SystemType detectMatchingSystemType() {
        SystemType systemType;

        try {
            systemType = SystemType.detect(properties);
        } catch (IllegalArgumentException e) {
            if (systemFilter != null) {
                log.info("Native library {}: could not detect sytem type, but system filter is {} - assuming it does " +
                    "not match and skipping library.", libraryName, systemFilter);

                return null;
            } else {
                throw e;
            }
        }

        if (systemFilter != null && !systemFilter.test(systemType)) {
            log.debug("Native library {}: system filter does not match detected system {}, skipping", libraryName,
                systemType.formatSystemName());
            return null;
        }

        return systemType;
    }

    private static void createPrivateDirectories(Path path) throws IOException {
        boolean isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

        if (!isPosix) {
            Files.createDirectories(path);
        } else {
            Files.createDirectories(path, asFileAttribute(fromString("rwx------")));
        }
    }

    private static class LoadResult {
        public final boolean success;
        public final RuntimeException exception;

        private LoadResult(boolean success, RuntimeException exception) {
            this.success = success;
            this.exception = exception;
        }
    }
}
