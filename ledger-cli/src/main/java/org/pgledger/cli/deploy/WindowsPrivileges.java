package org.pgledger.cli.deploy;

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
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

/**
 * The privileges of the token of this process that taking a file over needs, enabled for a short time. An elevated
 * administrator holds {@code SeTakeOwnershipPrivilege} and {@code SeRestorePrivilege}, but both are <em>disabled</em> in the
 * token, and the JDK enables only {@code SeRestorePrivilege}, and only inside {@code AclFileAttributeView.setOwner}
 * (java.base, {@code sun.nio.fs.WindowsAclFileAttributeView#setOwner}; {@code setAcl} enables none). Whoever owns a secret that
 * {@code seal-host} handed over (the service account, with Administrators only able to see that the file exists) can therefore
 * not be replaced as owner or have its ACL written by an administrator unless the privileges are enabled first.
 *
 * <p>Java has no API for it, so this is the Windows API itself, called with the foreign function API of Java 25
 * ({@code java.lang.foreign}; JEP 454): {@code OpenProcessToken}, {@code LookupPrivilegeValueW} and
 * {@code AdjustTokenPrivileges} of advapi32 (https://learn.microsoft.com/en-us/windows/win32/secauthz/enabling-and-disabling-privileges-in-c--).
 * {@code AdjustTokenPrivileges} succeeds even when the token does not hold a privilege and says so with the last error
 * {@code ERROR_NOT_ALL_ASSIGNED}, which is read in the same call (a {@code GetLastError} capture of the linker). A caller
 * enables the privileges around the one operation that needs them and closes the result: the token is put back as it was.
 * Only the classes of this file load the libraries, and only a Windows installation reaches it (the platform asks for the ACL
 * view of the file system first).
 *
 * <p>Calling native code is restricted in Java 25: without {@code --enable-native-access=ALL-UNNAMED} (or the attribute
 * {@code Enable-Native-Access: ALL-UNNAMED} in the manifest of the executable jar) the first call prints a warning on standard
 * error and goes on.
 */
final class WindowsPrivileges {

    static final String TAKE_OWNERSHIP = "SeTakeOwnershipPrivilege";
    static final String RESTORE = "SeRestorePrivilege";

    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_ADJUST_PRIVILEGES = 0x0020;
    private static final int SE_PRIVILEGE_ENABLED = 0x0002;
    private static final int ERROR_NOT_ALL_ASSIGNED = 1300;
    /** A LUID (two 32 bit words) and the attributes (one more): LUID_AND_ATTRIBUTES. */
    private static final long LUID_AND_ATTRIBUTES = 12;
    private static final long LUID = 8;
    /** The count of TOKEN_PRIVILEGES, before the entries. */
    private static final long COUNT = 4;

    private WindowsPrivileges() {
    }

    /**
     * One call of the Windows API. A method handle throws {@link Throwable}, which an interface method does not declare
     * (the catch rules of the project name no broad catch); {@link MethodHandleProxies} gives the handle as this interface,
     * so an exception that nobody can declare surfaces as the unchecked one it is. Public, because the proxy needs a public
     * interface.
     */
    public interface Downcall {

        Object call(Object... arguments);
    }

    /**
     * The libraries and the functions, linked on the first use (a class that is loaded by the first call of {@link #enable}).
     * The two calls that javac reports as restricted ({@code SymbolLookup.libraryLookup}, {@code Linker.downcallHandle}) are the
     * point of this class, and the build turns warnings into errors: they are named here and nowhere else.
     */
    @SuppressWarnings("restricted")
    private static final class Api {

        static final Linker LINKER = Linker.nativeLinker();
        static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
        static final long LAST_ERROR = CAPTURE.byteOffset(PathElement.groupElement("GetLastError"));
        static final Linker.Option CAPTURE_ERROR = Linker.Option.captureCallState("GetLastError");
        static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", Arena.global());

        static final Downcall GET_CURRENT_PROCESS = bind(KERNEL32, "GetCurrentProcess",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        static final Downcall CLOSE_HANDLE = bind(KERNEL32, "CloseHandle",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        /** The handle that is passed first is the capture state segment: the call writes GetLastError there. */
        static final Downcall OPEN_PROCESS_TOKEN = bind(ADVAPI32, "OpenProcessToken",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                CAPTURE_ERROR);
        static final Downcall LOOKUP_PRIVILEGE_VALUE = bind(ADVAPI32, "LookupPrivilegeValueW",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                CAPTURE_ERROR);
        static final Downcall ADJUST_TOKEN_PRIVILEGES = bind(ADVAPI32, "AdjustTokenPrivileges",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                CAPTURE_ERROR);

        private Api() {
        }

        private static Downcall bind(SymbolLookup library, String function, FunctionDescriptor descriptor,
                Linker.Option... options) {
            MethodHandle target = LINKER.downcallHandle(library.findOrThrow(function), descriptor, options);
            MethodHandle spread = target.asSpreader(Object[].class, target.type().parameterCount())
                    .asType(MethodType.methodType(Object.class, Object[].class));
            return MethodHandleProxies.asInterfaceInstance(Downcall.class, spread);
        }
    }

    /**
     * The privileges, enabled on the token of this process until {@link #close}; closing puts the token back as it was (a
     * privilege that was enabled before stays enabled). Used on the thread that made it.
     */
    static final class Enabled implements AutoCloseable {

        private final Arena arena;
        private final MemorySegment capture;
        private final MemorySegment token;
        /** TOKEN_PRIVILEGES of the privileges this call changed, as they were. */
        private final MemorySegment previous;
        private final boolean allHeld;

        private Enabled(Arena arena, MemorySegment capture, MemorySegment token, MemorySegment previous, boolean allHeld) {
            this.arena = arena;
            this.capture = capture;
            this.token = token;
            this.previous = previous;
            this.allHeld = allHeld;
        }

        /**
         * Whether the token holds every privilege that was asked for. When it does not (the process is not elevated), the
         * ones it holds are enabled all the same and the operation that follows may still succeed for a file the process
         * owns; a refusal of an operation that needed a privilege is then the one to report as a missing privilege.
         */
        boolean allHeld() {
            return allHeld;
        }

        @Override
        public void close() {
            try {
                if (previous.get(ValueLayout.JAVA_INT, 0) > 0) {
                    int reverted = (int) Api.ADJUST_TOKEN_PRIVILEGES.call(capture, token, 0, previous, 0,
                            MemorySegment.NULL, MemorySegment.NULL);
                    if (reverted == 0) {
                        throw new IllegalStateException("the privileges of the process token could not be put back: "
                                + "Windows error " + lastError(capture));
                    }
                }
            } finally {
                Api.CLOSE_HANDLE.call(token);
                arena.close();
            }
        }
    }

    /**
     * Enables the privileges on the token of this process.
     *
     * @param names the names of the privileges ({@link #TAKE_OWNERSHIP}, {@link #RESTORE})
     * @throws IOException when the token cannot be opened or a name is not a privilege of this system (not when the token
     *         merely does not hold one: see {@link Enabled#allHeld})
     */
    static Enabled enable(String... names) throws IOException {
        Arena arena = Arena.ofConfined();
        MemorySegment token = MemorySegment.NULL;
        boolean done = false;
        try {
            MemorySegment capture = arena.allocate(Api.CAPTURE);
            MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment process = (MemorySegment) Api.GET_CURRENT_PROCESS.call();
            int opened = (int) Api.OPEN_PROCESS_TOKEN.call(capture, process, TOKEN_QUERY | TOKEN_ADJUST_PRIVILEGES, tokenOut);
            require("OpenProcessToken", opened, capture);
            token = tokenOut.get(ValueLayout.ADDRESS, 0);

            long size = COUNT + LUID_AND_ATTRIBUTES * names.length;
            MemorySegment state = arena.allocate(size, 4);
            MemorySegment previous = arena.allocate(size, 4);
            MemorySegment returned = arena.allocate(ValueLayout.JAVA_INT);
            state.set(ValueLayout.JAVA_INT, 0, names.length);
            for (int index = 0; index < names.length; index++) {
                long entry = COUNT + LUID_AND_ATTRIBUTES * index;
                MemorySegment name = arena.allocateFrom(names[index], StandardCharsets.UTF_16LE);
                int found = (int) Api.LOOKUP_PRIVILEGE_VALUE.call(capture, MemorySegment.NULL, name, state.asSlice(entry, LUID));
                require("LookupPrivilegeValue " + names[index], found, capture);
                state.set(ValueLayout.JAVA_INT, entry + LUID, SE_PRIVILEGE_ENABLED);
            }
            int adjusted = (int) Api.ADJUST_TOKEN_PRIVILEGES.call(capture, token, 0, state, (int) size, previous, returned);
            require("AdjustTokenPrivileges", adjusted, capture);
            boolean allHeld = lastError(capture) != ERROR_NOT_ALL_ASSIGNED;
            done = true;
            return new Enabled(arena, capture, token, previous, allHeld);
        } finally {
            if (!done) {
                if (token.address() != 0L) {
                    Api.CLOSE_HANDLE.call(token);
                }
                arena.close();
            }
        }
    }

    private static int lastError(MemorySegment capture) {
        return capture.get(ValueLayout.JAVA_INT, Api.LAST_ERROR);
    }

    private static void require(String function, int result, MemorySegment capture) throws IOException {
        if (result == 0) {
            throw new IOException(function + " failed: Windows error " + lastError(capture));
        }
    }
}
