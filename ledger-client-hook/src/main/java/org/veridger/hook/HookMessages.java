// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;

/**
 * The message source of the hook: it has no message bundle (the hook prints one JSON document and nothing the user
 * reads is looked up by a code), so every code is unknown and a default message is returned as it is.
 *
 * <p>It exists because the context makes a {@code DelegatingMessageSource} when no {@code messageSource} bean is
 * defined, and that class (through {@code MessageSourceSupport}) enumerates every locale of the JDK when it is first
 * used. That costs about 0.8 s of processor time on a cold JVM, more than a quarter of one start of the hook (D-0096);
 * this class has no such state.
 */
final class HookMessages implements MessageSource {

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        return defaultMessage;
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) {
        throw new NoSuchMessageException(code, locale);
    }

    @Override
    public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
        String defaultMessage = resolvable.getDefaultMessage();
        if (defaultMessage != null) {
            return defaultMessage;
        }
        String[] codes = resolvable.getCodes();
        throw new NoSuchMessageException(codes == null || codes.length == 0 ? "" : codes[codes.length - 1], locale);
    }
}
