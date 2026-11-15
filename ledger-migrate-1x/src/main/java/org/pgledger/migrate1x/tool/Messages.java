package org.pgledger.migrate1x.tool;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** Every user-visible text comes from the one MessageSource. */
@Component
public class Messages {

    private final MessageSource messageSource;

    Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String get(String key, String... arguments) {
        return messageSource.getMessage(key, arguments, LocaleContextHolder.getLocale());
    }
}
