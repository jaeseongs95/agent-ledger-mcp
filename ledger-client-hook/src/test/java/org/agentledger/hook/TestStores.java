// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import java.nio.file.Path;
import java.util.List;
import org.agentledger.files.ProcessAccount;
import org.agentledger.hook.keys.KeyFolder;
import org.agentledger.hook.keys.SessionKey;
import org.agentledger.hook.keys.SessionKeyStore;

/** How the tests open the key store of a folder and load a key: the one place that knows the shape of the store API. */
final class TestStores {

    /** The account that owns the key folder: what {@code Main} and {@code KeyStoreProperties} pass from {@link ProcessAccount}. */
    static final String USER = ProcessAccount.name().orElse("");

    private TestStores() {}

    static SessionKeyStore open(Path directory) {
        return new SessionKeyStore(KeyFolder.of(directory), USER);
    }

    /** A store for folder text as the environment variable gives it (it may be relative: the store refuses that). */
    static SessionKeyStore at(String first, List<String> more) {
        return new SessionKeyStore(KeyFolder.of(first, more), USER);
    }

    static SessionKey load(SessionKeyStore store, String server, String author) {
        return store.load(server, author);
    }
}
