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
import org.springframework.stereotype.Component;

/**
 * The adapter of {@link TokenPrivileges} for Windows: the privileges of the token of this process that taking a file over
 * needs, enabled for a short time. An elevated administrator holds {@code SeTakeOwnershipPrivilege} and
 * {@code SeRestorePrivilege}, but both are <em>disabled</em> in the token, and the JDK enables only {@code SeRestorePrivilege},
 * and only inside {@code AclFileAttributeView.setOwner} (java.base, {@code sun.nio.fs.WindowsAclFileAttributeView#setOwner};
 * {@code setAcl} enables none). Whoever owns a secret that {@code seal-host} handed over (the service account, with
 * Administrators only able to see that the file exists) can therefore not be replaced as owner or have its ACL written by an
 * administrator unless the privileges are enabled first.
 *
 * <p>Java has no API for it, so this is the Windows API itself, called with the foreign function API of Java 25
 * ({@code java.lang.foreign}; JEP 454): {@code OpenProcessToken}, {@code LookupPrivilegeValueW} and
 * {@code AdjustTokenPrivileges} of advapi32 (https://learn.microsoft.com/en-us/windows/win32/secauthz/enabling-and-disabling-privileges-in-c--).
 * {@code AdjustTokenPrivileges} succeeds even when the token does not hold a privilege and says so with the last error
 * {@code ERROR_NOT_ALL_ASSIGNED}, which is read in the same call (a {@code GetLastError} capture of the linker). A caller
 * enables the privileges around the one operation that needs them and closes the result: the token is put back as it was.
 *
 * <p>A component like every adapter, and cheap to have on any platform: the libraries are linked by {@link #enable}, so a host
 * that is not Windows never loads them (the Windows platform asks for the ACL view of the file system first, and a platform
 * that has none never gets here).
 *
 * <p>Calling native code is restricted in Java 25. The executable jar of this tool allows it for its own code with the manifest
 * attribute {@code Enable-Native-Access: ALL-UNNAMED} (JEP 454 and JEP 472, https://openjdk.org/jeps/472; see
 * {@code ledger-cli/build.gradle.kts}), so the installer
 * ({@code deploy/install.cmd}) starts the jar with {@code -jar} and no command line option; without it the first call prints a
 * warning on standard error and goes on.
 */
@Component
final class WindowsPrivileges implements TokenPrivileges {

    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_ADJUST_PRIVILEGES = 0x0020;
    private static final int SE_PRIVILEGE_ENABLED = 0x0002;
    private static final int ERROR_NOT_ALL_ASSIGNED = 1300;
    /** A LUID (two 32 bit words) and the attributes (one more): LUID_AND_ATTRIBUTES. */
    private static final long LUID_AND_ATTRIBUTES = 12;
    private static final long LUID = 8;
    /** The count of TOKEN_PRIVILEGES, before the entries. */
    private static final long COUNT = 4;

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
     * The libraries and the functions, linked for one {@link #enable}. The two calls that javac reports as restricted
     * ({@code SymbolLookup.libraryLookup}, {@code Linker.downcallHandle}) are the point of this class, and the build turns
     * warnings into errors: they are named here and nowhere else.
     */
    @SuppressWarnings("restricted")
    private static final class Api {

        private final Linker linker = Linker.nativeLinker();
        private final StructLayout captureLayout = Linker.Option.captureStateLayout();
        private final long lastErrorOffset = captureLayout.byteOffset(PathElement.groupElement("GetLastError"));
        private final Linker.Option captureError = Linker.Option.captureCallState("GetLastError");
        private final SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private final SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());

        private final Downcall getCurrentProcess = bind(kernel32, "GetCurrentProcess",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        private final Downcall closeHandle = bind(kernel32, "CloseHandle",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        /** The handle that is passed first is the capture state segment: the call writes GetLastError there. */
        private final Downcall openProcessToken = bind(advapi32, "OpenProcessToken",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                captureError);
        private final Downcall lookupPrivilegeValue = bind(advapi32, "LookupPrivilegeValueW",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                captureError);
        private final Downcall adjustTokenPrivileges = bind(advapi32, "AdjustTokenPrivileges",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                captureError);

        private Downcall bind(SymbolLookup library, String function, FunctionDescriptor descriptor, Linker.Option... options) {
            MethodHandle target = linker.downcallHandle(library.findOrThrow(function), descriptor, options);
            MethodHandle spread = target.asSpreader(Object[].class, target.type().parameterCount())
                    .asType(MethodType.methodType(Object.class, Object[].class));
            return MethodHandleProxies.asInterfaceInstance(Downcall.class, spread);
        }

        int lastError(MemorySegment capture) {
            return capture.get(ValueLayout.JAVA_INT, lastErrorOffset);
        }
    }

    /** The privileges, enabled on the token of this process until {@link #close}; closing puts the token back as it was. */
    private static final class Held implements TokenPrivileges.Enabled {

        private final Api api;
        private final Arena arena;
        private final MemorySegment capture;
        private final MemorySegment token;
        /** TOKEN_PRIVILEGES of the privileges this call changed, as they were. */
        private final MemorySegment previous;
        private final boolean allHeld;

        private Held(Api api, Arena arena, MemorySegment capture, MemorySegment token, MemorySegment previous, boolean allHeld) {
            this.api = api;
            this.arena = arena;
            this.capture = capture;
            this.token = token;
            this.previous = previous;
            this.allHeld = allHeld;
        }

        @Override
        public boolean allHeld() {
            return allHeld;
        }

        @Override
        public void close() {
            try {
                if (previous.get(ValueLayout.JAVA_INT, 0) > 0) {
                    int reverted = (int) api.adjustTokenPrivileges.call(capture, token, 0, previous, 0,
                            MemorySegment.NULL, MemorySegment.NULL);
                    if (reverted == 0) {
                        throw new IllegalStateException("the privileges of the process token could not be put back: "
                                + "Windows error " + api.lastError(capture));
                    }
                }
            } finally {
                api.closeHandle.call(token);
                arena.close();
            }
        }
    }

    @Override
    public Enabled enable(String... names) throws IOException {
        Api api = new Api();
        Arena arena = Arena.ofConfined();
        MemorySegment token = MemorySegment.NULL;
        boolean done = false;
        try {
            MemorySegment capture = arena.allocate(api.captureLayout);
            MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment process = (MemorySegment) api.getCurrentProcess.call();
            int opened = (int) api.openProcessToken.call(capture, process, TOKEN_QUERY | TOKEN_ADJUST_PRIVILEGES, tokenOut);
            require(api, "OpenProcessToken", opened, capture);
            token = tokenOut.get(ValueLayout.ADDRESS, 0);

            long size = COUNT + LUID_AND_ATTRIBUTES * names.length;
            MemorySegment state = arena.allocate(size, 4);
            MemorySegment previous = arena.allocate(size, 4);
            MemorySegment returned = arena.allocate(ValueLayout.JAVA_INT);
            state.set(ValueLayout.JAVA_INT, 0, names.length);
            for (int index = 0; index < names.length; index++) {
                long entry = COUNT + LUID_AND_ATTRIBUTES * index;
                MemorySegment name = arena.allocateFrom(names[index], StandardCharsets.UTF_16LE);
                int found = (int) api.lookupPrivilegeValue.call(capture, MemorySegment.NULL, name, state.asSlice(entry, LUID));
                require(api, "LookupPrivilegeValue " + names[index], found, capture);
                state.set(ValueLayout.JAVA_INT, entry + LUID, SE_PRIVILEGE_ENABLED);
            }
            int adjusted = (int) api.adjustTokenPrivileges.call(capture, token, 0, state, (int) size, previous, returned);
            require(api, "AdjustTokenPrivileges", adjusted, capture);
            boolean allHeld = api.lastError(capture) != ERROR_NOT_ALL_ASSIGNED;
            done = true;
            return new Held(api, arena, capture, token, previous, allHeld);
        } finally {
            if (!done) {
                if (token.address() != 0L) {
                    api.closeHandle.call(token);
                }
                arena.close();
            }
        }
    }

    private void require(Api api, String function, int result, MemorySegment capture) throws IOException {
        if (result == 0) {
            throw new IOException(function + " failed: Windows error " + api.lastError(capture));
        }
    }
}
