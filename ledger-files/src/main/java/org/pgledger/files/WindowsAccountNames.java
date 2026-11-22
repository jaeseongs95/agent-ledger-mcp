// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.files;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * The one place of the product that calls the operating system with the foreign function API ({@code java.lang.foreign}, final in
 * Java 25): it turns the SID of a {@link WellKnownAccount} into the name this Windows gives the account, because the JDK cannot look
 * an account up by its SID ({@code lookupPrincipalByName("S-1-5-19")} is refused) and the names depend on the display language.
 * Two functions of advapi32 do it: {@code ConvertStringSidToSidW} and {@code LookupAccountSidW}
 * (https://learn.microsoft.com/en-us/windows/win32/api/sddl/nf-sddl-convertstringsidtosidw ,
 * https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-lookupaccountsidw), and {@code LocalFree} of kernel32 gives
 * back the SID that the first one allocated. The name is {@code DOMAIN\name}, as the JDK gives it for the principal of a file.
 *
 * <p>Package private and reached only by {@link WellKnownAccounts#host()}, which does nothing before a name is asked for: no other
 * class of the product depends on it, and a host without ACLs never loads it (checked by the architecture rules). The call is a
 * restricted method of Java 25: a program that reaches it runs with {@code Enable-Native-Access: ALL-UNNAMED} in the manifest of its
 * executable jar (JEP 454, JEP 472), or the JVM warns on its standard error.
 */
@SuppressWarnings("restricted")
final class WindowsAccountNames implements WellKnownAccounts {

    /** The most characters of a name or a domain: UNLEN and DNLEN of lmcons.h are 256 and 15, plus the terminator. */
    private static final int NAME_CHARACTERS = 257;

    @Override
    public String nameOf(WellKnownAccount account) throws IOException {
        return nameOfSid(account.sid());
    }

    /** The name of the account with this SID, {@code DOMAIN\name}, or the name alone when the account has no domain. */
    String nameOfSid(String sid) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(Functions.ERROR_STATE);
            MemorySegment text = arena.allocateFrom(sid, StandardCharsets.UTF_16LE);
            MemorySegment binary = arena.allocate(ValueLayout.ADDRESS);
            if ((int) Functions.CONVERT_STRING_SID.invokeExact(error, text, binary) == 0) {
                throw failure("ConvertStringSidToSidW", sid, error);
            }
            MemorySegment binarySid = binary.get(ValueLayout.ADDRESS, 0);
            try {
                MemorySegment name = arena.allocate(NAME_CHARACTERS * 2L);
                MemorySegment domain = arena.allocate(NAME_CHARACTERS * 2L);
                MemorySegment nameLength = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment domainLength = arena.allocate(ValueLayout.JAVA_INT);
                nameLength.set(ValueLayout.JAVA_INT, 0, NAME_CHARACTERS);
                domainLength.set(ValueLayout.JAVA_INT, 0, NAME_CHARACTERS);
                MemorySegment use = arena.allocate(ValueLayout.JAVA_INT);
                if ((int) Functions.LOOKUP_ACCOUNT_SID.invokeExact(error, MemorySegment.NULL, binarySid, name, nameLength, domain,
                        domainLength, use) == 0) {
                    throw failure("LookupAccountSidW", sid, error);
                }
                String account = name.getString(0, StandardCharsets.UTF_16LE);
                String authority = domain.getString(0, StandardCharsets.UTF_16LE);
                return authority.isEmpty() ? account : authority + "\\" + account;
            } finally {
                MemorySegment ignored = (MemorySegment) Functions.LOCAL_FREE.invokeExact(binarySid);
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("the Windows API could not tell the name of the account " + sid, failure);
        }
    }

    private static IOException failure(String function, String sid, MemorySegment error) {
        int status = error.get(ValueLayout.JAVA_INT, Functions.LAST_ERROR_OFFSET);
        return new IOException(function + " failed with the status " + status + " for the SID " + sid);
    }

    /** The functions, linked once, when the first name is asked for. */
    private static final class Functions {

        static final Linker.Option CAPTURE = Linker.Option.captureCallState("GetLastError");
        static final StructLayout ERROR_STATE = Linker.Option.captureStateLayout();
        static final long LAST_ERROR_OFFSET = ERROR_STATE.byteOffset(PathElement.groupElement("GetLastError"));
        static final MethodHandle CONVERT_STRING_SID;
        static final MethodHandle LOOKUP_ACCOUNT_SID;
        static final MethodHandle LOCAL_FREE;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            CONVERT_STRING_SID = linker.downcallHandle(advapi32.find("ConvertStringSidToSidW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE);
            LOOKUP_ACCOUNT_SID = linker.downcallHandle(advapi32.find("LookupAccountSidW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    CAPTURE);
            LOCAL_FREE = linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        }

        private Functions() {
        }
    }
}
