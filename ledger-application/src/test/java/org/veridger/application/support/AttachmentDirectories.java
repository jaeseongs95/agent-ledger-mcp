// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.support;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import org.veridger.application.attachment.AttachmentKeys;
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.application.attachment.PrivateFiles;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;

/**
 * Gives a test context what every installation has: an attachment directory and a key directory (two folders of a temporary folder of
 * their own, never inside each other), one key file made by {@link AttachmentKeys#create()}, and the settings
 * {@code ledger.attachments.directory}, {@code key-directory} and {@code active-key-id} that name them. The components of the
 * attachments exist only in a context that has the directory setting, so a test of another area is not given them. The folder is
 * removed when the context closes. Spring's test context cache keys a context by this class, so the test classes that name it (with
 * the same other settings) share one context, one schema and one folder; a test that counts the files of the folder has a context of its own.
 */
public final class AttachmentDirectories implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Path root = TestTrees.create("ledger-attachments-test-");
        Path directory = root.resolve("attachments");
        Path keyDirectory = root.resolve("keys");
        // the record is only the carrier of the two folders for the key maker; the settings of the context are the property source below
        String keyId = new AttachmentKeys(AttachmentSettings.properties(directory, keyDirectory, "to-be-created"),
                new PrivateFiles(), new SecureRandom()).create();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ledger-test-attachments",
                Map.of("ledger.attachments.directory", directory.toString(),
                        "ledger.attachments.key-directory", keyDirectory.toString(),
                        "ledger.attachments.active-key-id", keyId)));
        context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent closed && closed.getApplicationContext() == context) {
                TestTrees.delete(root);
            }
        });
    }
}
