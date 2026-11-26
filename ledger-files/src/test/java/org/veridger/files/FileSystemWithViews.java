// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.files;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * The file system of the test host with other attribute views: how a test makes a Linux file system ({@code posix}) or a Windows one
 * ({@code acl}, {@code dos}) of the file system it runs on, so that the choice of each kind is tested on every host. Everything else
 * is the delegate's.
 */
final class FileSystemWithViews extends FileSystem {

    static final Set<String> LINUX = Set.of("basic", "posix", "unix", "owner", "dos", "user");
    static final Set<String> WINDOWS = Set.of("basic", "dos", "acl", "owner", "user");
    static final Set<String> NEITHER = Set.of("basic");

    private final FileSystem delegate;
    private final Set<String> views;
    private final UserPrincipalLookupService lookup;

    FileSystemWithViews(FileSystem delegate, Set<String> views) {
        this.delegate = delegate;
        this.views = views;
        this.lookup = delegate.getUserPrincipalLookupService();
    }

    /** As above, and the accounts of this file system are these (by name): the lookup of any other name is refused. */
    FileSystemWithViews(FileSystem delegate, Set<String> views, Map<String, UserPrincipal> accounts) {
        this.delegate = delegate;
        this.views = views;
        this.lookup = new UserPrincipalLookupService() {
            @Override
            public UserPrincipal lookupPrincipalByName(String name) throws IOException {
                UserPrincipal found = accounts.get(name);
                if (found == null) {
                    throw new UserPrincipalNotFoundException(name);
                }
                return found;
            }

            @Override
            public GroupPrincipal lookupPrincipalByGroupName(String group) throws IOException {
                throw new UserPrincipalNotFoundException(group);
            }
        };
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return views;
    }

    @Override
    public FileSystemProvider provider() {
        return delegate.provider();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public String getSeparator() {
        return delegate.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return delegate.getRootDirectories();
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return delegate.getFileStores();
    }

    @Override
    public Path getPath(String first, String... more) {
        return delegate.getPath(first, more);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        return delegate.getPathMatcher(syntaxAndPattern);
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return lookup;
    }

    @Override
    public WatchService newWatchService() throws IOException {
        return delegate.newWatchService();
    }
}
