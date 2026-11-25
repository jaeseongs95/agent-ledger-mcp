// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.support;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import org.pgledger.files.WellKnownAccount;
import org.pgledger.files.WellKnownAccounts;

/**
 * The effective rights of an account on a file, computed by Windows from the DACL of the file ({@code GetNamedSecurityInfoW} and
 * {@code GetEffectiveRightsFromAclW} of advapi32, https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-geteffectiverightsfromaclw):
 * what the ACL grants to the account itself or to a group it belongs to. It answers the question of PCS-031 for the service account
 * LocalService without running as it (a logged-on LocalService needs a service or a tool that is not part of the build). The call goes
 * through the foreign function API; the test task of this module allows native access. The product calls the operating system from
 * one class only ({@code WindowsAccountNames} of ledger-files), and this is a test class.
 */
public final class WindowsEffectiveRights {

    public static final int FILE_READ_DATA = 0x0001;
    public static final int FILE_WRITE_DATA = 0x0002;
    public static final int FILE_APPEND_DATA = 0x0004;
    public static final int FILE_EXECUTE = 0x0020;
    public static final int DELETE = 0x00010000;
    public static final int WRITE_DAC = 0x00040000;
    public static final int WRITE_OWNER = 0x00080000;

    private static final int SE_FILE_OBJECT = 1;
    private static final int DACL_SECURITY_INFORMATION = 4;
    private static final int TRUSTEE_SIZE = 64;

    private WindowsEffectiveRights() {
    }

    /** As {@link #of(Path, String)} for a well-known account, by the name this host gives it (found by its SID). */
    public static int of(Path path, WellKnownAccount account) throws IOException {
        return of(path, WellKnownAccounts.host().nameOf(account));
    }

    /**
     * The access mask that the DACL of the path grants to the account.
     *
     * @param account the name of the account as Windows knows it ({@code NT AUTHORITY\LocalService}, {@code Everyone})
     * @throws IOException when a call of the Windows API fails (the status is in the message)
     */
    public static int of(Path path, String account) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", arena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            MethodHandle getNamedSecurityInfo = linker.downcallHandle(advapi32.find("GetNamedSecurityInfoW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle buildTrustee = linker.downcallHandle(advapi32.find("BuildTrusteeWithNameW").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle effectiveRights = linker.downcallHandle(advapi32.find("GetEffectiveRightsFromAclW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle localFree = linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            MemorySegment name = arena.allocateFrom(path.toAbsolutePath().toString(), java.nio.charset.StandardCharsets.UTF_16LE);
            MemorySegment dacl = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment descriptor = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment accountName = arena.allocateFrom(account, java.nio.charset.StandardCharsets.UTF_16LE);
            MemorySegment trustee = arena.allocate(TRUSTEE_SIZE);
            MemorySegment mask = arena.allocate(ValueLayout.JAVA_INT);
            try {
                int status = (int) getNamedSecurityInfo.invokeExact(name, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                        MemorySegment.NULL, MemorySegment.NULL, dacl, MemorySegment.NULL, descriptor);
                if (status != 0) {
                    throw new IOException("GetNamedSecurityInfoW failed with status " + status + " for " + path);
                }
                try {
                    buildTrustee.invokeExact(trustee, accountName);
                    status = (int) effectiveRights.invokeExact(dacl.get(ValueLayout.ADDRESS, 0), trustee, mask);
                    if (status != 0) {
                        throw new IOException("GetEffectiveRightsFromAclW failed with status " + status + " for " + account);
                    }
                    return mask.get(ValueLayout.JAVA_INT, 0);
                } finally {
                    MemorySegment ignored = (MemorySegment) localFree.invokeExact(descriptor.get(ValueLayout.ADDRESS, 0));
                }
            } catch (IOException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IOException("a call of the Windows API failed", failure);
            }
        }
    }
}
