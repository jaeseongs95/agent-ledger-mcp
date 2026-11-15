package org.pgledger.mcp.support;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import org.pgledger.application.attachment.AttachmentKeys;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.PrivateFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * Gives a test context of the server what every installation has: an attachment directory and a key directory (two folders of a
 * temporary folder of their own, never inside each other), one key file made by {@link AttachmentKeys#create()} (a random 256-bit
 * key, private to the account of the test), and the settings {@code ledger.attachments.directory}, {@code key-directory} and
 * {@code active-key-id} that name them. Without the directory setting the attachments (and their five tools) do not exist; with it
 * the server runs the start check of the attachments like a deployed one, which is why the key is made before the context starts.
 * The temporary folder is removed when the context closes. Used as {@code initializers} of the test annotations of the server, next to
 * the {@link org.pgledger.testing.database.TestSchemaInitializer}, so the contexts that share a configuration share the folders.
 */
public final class AttachmentTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentTestInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Path root = temporaryFolder();
        Path directory = root.resolve("attachments");
        Path keyDirectory = root.resolve("keys");
        // the record is only the carrier of the two folders for the key maker; the settings of the context are the property source below
        AttachmentProperties folders = new AttachmentProperties(directory, keyDirectory, "to-be-created",
                DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4, Duration.ofMinutes(10),
                Duration.ofHours(24), Duration.ofMinutes(1));
        String keyId = new AttachmentKeys(folders, new PrivateFiles(), new SecureRandom()).create();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ledger-test-attachments",
                Map.of(McpTestSettings.ATTACHMENTS_DIRECTORY, directory.toString(),
                        McpTestSettings.ATTACHMENTS_KEY_DIRECTORY, keyDirectory.toString(),
                        McpTestSettings.ATTACHMENTS_ACTIVE_KEY_ID, keyId)));
        context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent closed && closed.getApplicationContext() == context) {
                remove(root);
            }
        });
    }

    private static Path temporaryFolder() {
        try {
            return Files.createTempDirectory("ledger-attachments-test-");
        } catch (IOException failure) {
            throw new IllegalStateException("the temporary folder of the attachments could not be made", failure);
        }
    }

    /** Removes the folder with the files in it (a finished file is read-only, which a file system without POSIX modes keeps from deleting it). */
    private static void remove(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                        Files.getFileAttributeView(file, DosFileAttributeView.class).setReadOnly(false);
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(folder);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            LOG.warn("the temporary folder {} of the attachments was not removed", root, failure);
        }
    }
}
