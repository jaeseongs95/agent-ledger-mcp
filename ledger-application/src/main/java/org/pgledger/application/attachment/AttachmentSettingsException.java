package org.pgledger.application.attachment;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * A setting of {@code ledger.attachments.*} that stops the start. It is a failure of the settings (the validation of the record that
 * binds them), not an error code of a tool: no tool call can fail with it. The message is the English text followed by the Korean text,
 * because a failure of the start has no caller language, and it names the keys of the settings (never a path or a key).
 */
public final class AttachmentSettingsException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;
    private static final String BUNDLE = "messages/attachment-settings";

    /** The key directory is inside the attachment directory (or is it): both keys are named. */
    public static final String KEY_DIRECTORY_INSIDE_DIRECTORY = "key-directory-inside-directory";

    public AttachmentSettingsException(String messageKey) {
        super(text(messageKey));
    }

    private static String text(String messageKey) {
        String english = MessageFormat.format(ResourceBundle.getBundle(BUNDLE, Locale.ROOT).getString(messageKey));
        String korean = MessageFormat.format(ResourceBundle.getBundle(BUNDLE, Locale.KOREAN).getString(messageKey));
        return english + " / " + korean;
    }
}
