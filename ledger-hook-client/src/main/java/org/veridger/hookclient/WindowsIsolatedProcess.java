// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.veridger.files.FileKind;

/**
 * Starts the detached resident hook program on Windows with an explicit inheritable handle list
 * ({@code CreateProcessW} + {@code STARTUPINFOEXW} + {@code PROC_THREAD_ATTRIBUTE_HANDLE_LIST}) so it inherits only NUL
 * for standard input and the state folder log file for standard output/error, never extra pipe handles held by an
 * enclosing caller.
 *
 * <p>Package-private and reached only by {@link ResidentLauncher}; {@link #isSupported()} uses {@link FileKind} and never
 * loads {@code kernel32} on POSIX hosts. Calls restricted Java 25 FFM methods covered by
 * {@code Enable-Native-Access: ALL-UNNAMED} in {@code ledger-client-hook.jar}.
 */
@SuppressWarnings("restricted")
final class WindowsIsolatedProcess {

    private static final int GENERIC_READ = 0x80000000;
    private static final int FILE_APPEND_DATA = 0x00000004;
    private static final int SYNCHRONIZE = 0x00100000;
    private static final int FILE_SHARE_ALL = 0x00000001 | 0x00000002 | 0x00000004;
    private static final int OPEN_EXISTING = 3;
    private static final int OPEN_ALWAYS = 4;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;

    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002L;

    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int WAIT_OBJECT_0 = 0x00000000;
    private static final int WAIT_TIMEOUT = 0x00000102;

    static final StructLayout SECURITY_ATTRIBUTES_LAYOUT = securityAttributesLayout();
    static final StructLayout STARTUPINFOW_LAYOUT = startupInfoLayout();
    static final StructLayout STARTUPINFOEXW_LAYOUT = MemoryLayout.structLayout(
            STARTUPINFOW_LAYOUT.withName("StartupInfo"),
            ValueLayout.ADDRESS.withName("lpAttributeList"));
    static final StructLayout PROCESS_INFORMATION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("hProcess"),
            ValueLayout.ADDRESS.withName("hThread"),
            ValueLayout.JAVA_INT.withName("dwProcessId"),
            ValueLayout.JAVA_INT.withName("dwThreadId"));

    private static final long SA_LENGTH_OFFSET = SECURITY_ATTRIBUTES_LAYOUT.byteOffset(PathElement.groupElement("nLength"));
    private static final long SA_INHERIT_OFFSET = SECURITY_ATTRIBUTES_LAYOUT.byteOffset(PathElement.groupElement("bInheritHandle"));

    private static final long SI_CB_OFFSET = STARTUPINFOEXW_LAYOUT.byteOffset(
            PathElement.groupElement("StartupInfo"), PathElement.groupElement("cb"));
    private static final long SI_FLAGS_OFFSET = STARTUPINFOEXW_LAYOUT.byteOffset(
            PathElement.groupElement("StartupInfo"), PathElement.groupElement("dwFlags"));
    private static final long SI_STD_INPUT_OFFSET = STARTUPINFOEXW_LAYOUT.byteOffset(
            PathElement.groupElement("StartupInfo"), PathElement.groupElement("hStdInput"));
    private static final long SI_STD_OUTPUT_OFFSET = STARTUPINFOEXW_LAYOUT.byteOffset(
            PathElement.groupElement("StartupInfo"), PathElement.groupElement("hStdOutput"));
    private static final long SI_STD_ERROR_OFFSET = STARTUPINFOEXW_LAYOUT.byteOffset(
            PathElement.groupElement("StartupInfo"), PathElement.groupElement("hStdError"));
    private static final long SIEX_ATTR_LIST_OFFSET = STARTUPINFOEXW_LAYOUT.byteOffset(
            PathElement.groupElement("lpAttributeList"));

    private static final long PI_PROCESS_OFFSET = PROCESS_INFORMATION_LAYOUT.byteOffset(PathElement.groupElement("hProcess"));
    private static final long PI_THREAD_OFFSET = PROCESS_INFORMATION_LAYOUT.byteOffset(PathElement.groupElement("hThread"));

    private WindowsIsolatedProcess() {}

    /** Whether this host uses Windows ACL semantics without POSIX permissions. */
    static boolean isSupported() {
        return FileKind.of(FileSystems.getDefault()) == FileKind.ACL;
    }

    /**
     * Spawns {@code command} with inherited handles strictly restricted to {@code NUL} (stdin) and {@code logFile}
     * (stdout and stderr).
     */
    static ResidentLauncher.StartedResident spawn(List<String> command, Path workingDirectory, Path logFile)
            throws IOException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(logFile, "logFile");
        String commandLine = formatCommandLine(command);
        String workDirText = workingDirectory.toAbsolutePath().normalize().toString();
        String logFileText = logFile.toAbsolutePath().normalize().toString();

        MemorySegment hProcess = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(Functions.ERROR_STATE);
            try (SpawnResources resources = new SpawnResources(error)) {
                MemorySegment sa = arena.allocate(SECURITY_ATTRIBUTES_LAYOUT);
                sa.set(ValueLayout.JAVA_INT, SA_LENGTH_OFFSET, (int) SECURITY_ATTRIBUTES_LAYOUT.byteSize());
                sa.set(ValueLayout.JAVA_INT, SA_INHERIT_OFFSET, 1);

                MemorySegment hNul = openFile(arena, error, "NUL", GENERIC_READ, OPEN_EXISTING, sa);
                resources.setNul(hNul);

                MemorySegment hLog = openFile(arena, error, logFileText, FILE_APPEND_DATA | SYNCHRONIZE, OPEN_ALWAYS, sa);
                resources.setLog(hLog);

                MemorySegment attrListSize = arena.allocate(Functions.SIZE_T);
                int sizeQueryOk = (int) Functions.INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST.invokeExact(
                        error, MemorySegment.NULL, 1, 0, attrListSize);
                long requiredBytes = readSizeT(attrListSize);
                if (sizeQueryOk == 0 && lastError(error) != ERROR_INSUFFICIENT_BUFFER) {
                    throw failure("InitializeProcThreadAttributeList", error);
                }
                if (requiredBytes <= 0) {
                    throw new IOException("InitializeProcThreadAttributeList returned invalid size " + requiredBytes);
                }
                MemorySegment attrList = arena.allocate(requiredBytes, ValueLayout.ADDRESS.byteAlignment());
                if ((int) Functions.INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST.invokeExact(
                        error, attrList, 1, 0, attrListSize) == 0) {
                    throw failure("InitializeProcThreadAttributeList", error);
                }
                resources.setAttrList(attrList);

                // Deduplicated handle list: UpdateProcThreadAttribute rejects duplicate entries with ERROR_INVALID_PARAMETER.
                MemorySegment handles = arena.allocate(ValueLayout.ADDRESS, 2);
                handles.setAtIndex(ValueLayout.ADDRESS, 0, hNul);
                handles.setAtIndex(ValueLayout.ADDRESS, 1, hLog);
                if (updateHandleList(error, attrList, handles) == 0) {
                    throw failure("UpdateProcThreadAttribute", error);
                }

                MemorySegment startupInfoEx = arena.allocate(STARTUPINFOEXW_LAYOUT);
                startupInfoEx.set(ValueLayout.JAVA_INT, SI_CB_OFFSET, (int) STARTUPINFOEXW_LAYOUT.byteSize());
                startupInfoEx.set(ValueLayout.JAVA_INT, SI_FLAGS_OFFSET, STARTF_USESTDHANDLES);
                startupInfoEx.set(ValueLayout.ADDRESS, SI_STD_INPUT_OFFSET, hNul);
                startupInfoEx.set(ValueLayout.ADDRESS, SI_STD_OUTPUT_OFFSET, hLog);
                startupInfoEx.set(ValueLayout.ADDRESS, SI_STD_ERROR_OFFSET, hLog);
                startupInfoEx.set(ValueLayout.ADDRESS, SIEX_ATTR_LIST_OFFSET, attrList);

                MemorySegment commandBuffer = arena.allocateFrom(commandLine, StandardCharsets.UTF_16LE);
                MemorySegment workDirBuffer = arena.allocateFrom(workDirText, StandardCharsets.UTF_16LE);
                MemorySegment processInfo = arena.allocate(PROCESS_INFORMATION_LAYOUT);
                int created = (int) Functions.CREATE_PROCESS_W.invokeExact(
                        error, MemorySegment.NULL, commandBuffer, MemorySegment.NULL, MemorySegment.NULL,
                        1, EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW, MemorySegment.NULL,
                        workDirBuffer, startupInfoEx, processInfo);
                if (created == 0) {
                    throw failure("CreateProcessW", error);
                }

                hProcess = processInfo.get(ValueLayout.ADDRESS, PI_PROCESS_OFFSET);
                MemorySegment hThread = processInfo.get(ValueLayout.ADDRESS, PI_THREAD_OFFSET);
                closeHandleChecked(hThread, error);
            }
            return new StartedProcess(hProcess);
        } catch (Throwable failure) {
            closeHandleBestEffort(hProcess, failure);
            throw asIoException(failure, "failed to spawn isolated Windows process");
        }
    }

    /** Formats {@code command} according to Windows CRT {@code CommandLineToArgvW} escaping rules. */
    static String formatCommandLine(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg == null) {
                throw new IllegalArgumentException("command argument must not be null");
            }
            if (arg.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("command argument must not contain NUL characters");
            }
            if (i > 0) {
                out.append(' ');
            }
            appendQuotedArgument(out, arg);
        }
        return out.toString();
    }

    private static void appendQuotedArgument(StringBuilder out, String arg) {
        if (!needsQuoting(arg)) {
            out.append(arg);
            return;
        }
        out.append('"');
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                appendBackslashes(out, backslashes * 2 + 1);
                out.append('"');
                backslashes = 0;
            } else {
                appendBackslashes(out, backslashes);
                out.append(c);
                backslashes = 0;
            }
        }
        appendBackslashes(out, backslashes * 2);
        out.append('"');
    }

    private static boolean needsQuoting(String arg) {
        if (arg.isEmpty()) {
            return true;
        }
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == ' ' || c == '\t' || c == '"') {
                return true;
            }
        }
        return false;
    }

    private static void appendBackslashes(StringBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            out.append('\\');
        }
    }

    private static MemorySegment openFile(Arena arena, MemorySegment error, String path, int desiredAccess,
            int creationDisposition, MemorySegment securityAttributes) throws Throwable {
        MemorySegment widePath = arena.allocateFrom(path, StandardCharsets.UTF_16LE);
        MemorySegment handle = (MemorySegment) Functions.CREATE_FILE_W.invokeExact(
                error, widePath, desiredAccess, FILE_SHARE_ALL, securityAttributes,
                creationDisposition, FILE_ATTRIBUTE_NORMAL, MemorySegment.NULL);
        if (isInvalidHandle(handle)) {
            throw failure("CreateFileW(" + path + ")", error);
        }
        return handle;
    }

    private static int updateHandleList(MemorySegment error, MemorySegment attrList, MemorySegment handles)
            throws Throwable {
        if (Functions.SIZE_T.byteSize() == Long.BYTES) {
            return (int) Functions.UPDATE_PROC_THREAD_ATTRIBUTE.invokeExact(
                    error, attrList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, handles.byteSize(),
                    MemorySegment.NULL, MemorySegment.NULL);
        }
        return (int) Functions.UPDATE_PROC_THREAD_ATTRIBUTE.invokeExact(
                error, attrList, 0, (int) PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, (int) handles.byteSize(),
                MemorySegment.NULL, MemorySegment.NULL);
    }

    private static long readSizeT(MemorySegment segment) {
        return Functions.SIZE_T.byteSize() == Long.BYTES
                ? segment.get(ValueLayout.JAVA_LONG, 0)
                : Integer.toUnsignedLong(segment.get(ValueLayout.JAVA_INT, 0));
    }

    private static boolean isInvalidHandle(MemorySegment handle) {
        long address = handle.address();
        return address == 0L || address == -1L
                || (ValueLayout.ADDRESS.byteSize() == Integer.BYTES && (int) address == -1);
    }

    private static void closeHandleChecked(MemorySegment handle, MemorySegment error) throws IOException {
        if (handle.address() == 0L) {
            return;
        }
        try {
            if ((int) Functions.CLOSE_HANDLE.invokeExact(error, handle) == 0) {
                throw failure("CloseHandle", error);
            }
        } catch (Throwable failure) {
            throw asIoException(failure, "CloseHandle failed");
        }
    }

    private static void closeHandleBestEffort(MemorySegment handle, Throwable primary) {
        if (handle.address() == 0L) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            closeHandleChecked(handle, arena.allocate(Functions.ERROR_STATE));
        } catch (Throwable suppressed) {
            primary.addSuppressed(suppressed);
        }
    }

    private static IOException asIoException(Throwable failure, String message) {
        if (failure instanceof IOException io) {
            return io;
        }
        rethrowUnchecked(failure);
        return new IOException(message, failure);
    }

    private static IllegalStateException asIllegalState(Throwable failure, String message) {
        rethrowUnchecked(failure);
        return new IllegalStateException(message, failure);
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
    }

    private static int lastError(MemorySegment error) {
        return error.get(ValueLayout.JAVA_INT, Functions.LAST_ERROR_OFFSET);
    }

    private static IOException failure(String operation, MemorySegment error) {
        return new IOException(operation + " failed with error " + lastError(error));
    }

    private static StructLayout securityAttributesLayout() {
        long ptrPad = ValueLayout.ADDRESS.byteSize() - ValueLayout.JAVA_INT.byteSize();
        List<MemoryLayout> fields = new ArrayList<>();
        fields.add(ValueLayout.JAVA_INT.withName("nLength"));
        if (ptrPad > 0) {
            fields.add(MemoryLayout.paddingLayout(ptrPad));
        }
        fields.add(ValueLayout.ADDRESS.withName("lpSecurityDescriptor"));
        fields.add(ValueLayout.JAVA_INT.withName("bInheritHandle"));
        if (ptrPad > 0) {
            fields.add(MemoryLayout.paddingLayout(ptrPad));
        }
        return MemoryLayout.structLayout(fields.toArray(MemoryLayout[]::new));
    }

    private static StructLayout startupInfoLayout() {
        long ptrPad = ValueLayout.ADDRESS.byteSize() - ValueLayout.JAVA_INT.byteSize();
        List<MemoryLayout> fields = new ArrayList<>();
        fields.add(ValueLayout.JAVA_INT.withName("cb"));
        if (ptrPad > 0) {
            fields.add(MemoryLayout.paddingLayout(ptrPad));
        }
        fields.add(ValueLayout.ADDRESS.withName("lpReserved"));
        fields.add(ValueLayout.ADDRESS.withName("lpDesktop"));
        fields.add(ValueLayout.ADDRESS.withName("lpTitle"));
        fields.add(ValueLayout.JAVA_INT.withName("dwX"));
        fields.add(ValueLayout.JAVA_INT.withName("dwY"));
        fields.add(ValueLayout.JAVA_INT.withName("dwXSize"));
        fields.add(ValueLayout.JAVA_INT.withName("dwYSize"));
        fields.add(ValueLayout.JAVA_INT.withName("dwXCountChars"));
        fields.add(ValueLayout.JAVA_INT.withName("dwYCountChars"));
        fields.add(ValueLayout.JAVA_INT.withName("dwFillAttribute"));
        fields.add(ValueLayout.JAVA_INT.withName("dwFlags"));
        fields.add(ValueLayout.JAVA_SHORT.withName("wShowWindow"));
        fields.add(ValueLayout.JAVA_SHORT.withName("cbReserved2"));
        if (ptrPad > 0) {
            fields.add(MemoryLayout.paddingLayout(ptrPad));
        }
        fields.add(ValueLayout.ADDRESS.withName("lpReserved2"));
        fields.add(ValueLayout.ADDRESS.withName("hStdInput"));
        fields.add(ValueLayout.ADDRESS.withName("hStdOutput"));
        fields.add(ValueLayout.ADDRESS.withName("hStdError"));
        return MemoryLayout.structLayout(fields.toArray(MemoryLayout[]::new));
    }

    private static final class SpawnResources implements AutoCloseable {

        private final MemorySegment error;
        private MemorySegment hNul = MemorySegment.NULL;
        private MemorySegment hLog = MemorySegment.NULL;
        private MemorySegment attrList = MemorySegment.NULL;

        SpawnResources(MemorySegment error) {
            this.error = error;
        }

        void setNul(MemorySegment handle) {
            this.hNul = handle;
        }

        void setLog(MemorySegment handle) {
            this.hLog = handle;
        }

        void setAttrList(MemorySegment segment) {
            this.attrList = segment;
        }

        @Override
        public void close() throws IOException {
            Throwable failure = null;
            if (attrList.address() != 0L) {
                try {
                    Functions.DELETE_PROC_THREAD_ATTRIBUTE_LIST.invokeExact(attrList);
                } catch (Throwable ex) {
                    failure = record(failure, ex);
                }
            }
            failure = closeOne(hLog, failure);
            failure = closeOne(hNul, failure);
            if (failure != null) {
                throw asIoException(failure, "failed releasing Windows spawn resources");
            }
        }

        private Throwable closeOne(MemorySegment handle, Throwable failure) {
            try {
                closeHandleChecked(handle, error);
            } catch (IOException ex) {
                return record(failure, ex);
            }
            return failure;
        }

        private static Throwable record(Throwable first, Throwable next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }
    }

    private static final class StartedProcess implements ResidentLauncher.StartedResident {

        private final MemorySegment processHandle;
        private final AtomicBoolean closed = new AtomicBoolean();

        StartedProcess(MemorySegment processHandle) {
            this.processHandle = processHandle;
        }

        @Override
        public boolean isAlive() {
            if (closed.get()) {
                return false;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment error = arena.allocate(Functions.ERROR_STATE);
                int status = (int) Functions.WAIT_FOR_SINGLE_OBJECT.invokeExact(error, processHandle, 0);
                if (status == WAIT_TIMEOUT) {
                    return true;
                }
                if (status == WAIT_OBJECT_0) {
                    return false;
                }
                throw new IllegalStateException("WaitForSingleObject failed with status " + status
                        + ", error " + lastError(error));
            } catch (Throwable failure) {
                throw asIllegalState(failure, "failed querying Windows process state");
            }
        }

        @Override
        public int exitValue() {
            if (closed.get()) {
                throw new IllegalStateException("process handle already closed");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment error = arena.allocate(Functions.ERROR_STATE);
                int status = (int) Functions.WAIT_FOR_SINGLE_OBJECT.invokeExact(error, processHandle, 0);
                if (status == WAIT_TIMEOUT) {
                    throw new IllegalThreadStateException("process has not exited");
                }
                if (status != WAIT_OBJECT_0) {
                    throw new IllegalStateException("WaitForSingleObject failed with status " + status
                            + ", error " + lastError(error));
                }
                MemorySegment code = arena.allocate(ValueLayout.JAVA_INT);
                if ((int) Functions.GET_EXIT_CODE_PROCESS.invokeExact(error, processHandle, code) == 0) {
                    throw new IllegalStateException("GetExitCodeProcess failed with error " + lastError(error));
                }
                return code.get(ValueLayout.JAVA_INT, 0);
            } catch (Throwable failure) {
                throw asIllegalState(failure, "failed reading Windows process exit code");
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try (Arena arena = Arena.ofConfined()) {
                closeHandleChecked(processHandle, arena.allocate(Functions.ERROR_STATE));
            } catch (Throwable failure) {
                throw asIllegalState(failure, "failed closing Windows process handle");
            }
        }
    }

    /** Lazy native lookup holder so non-ACL hosts never load {@code kernel32}. */
    private static final class Functions {

        static final Linker.Option CAPTURE = Linker.Option.captureCallState("GetLastError");
        static final StructLayout ERROR_STATE = Linker.Option.captureStateLayout();
        static final long LAST_ERROR_OFFSET = ERROR_STATE.byteOffset(PathElement.groupElement("GetLastError"));
        static final ValueLayout SIZE_T = (ValueLayout) Linker.nativeLinker().canonicalLayouts().get("size_t");

        static final MethodHandle CREATE_FILE_W;
        static final MethodHandle INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST;
        static final MethodHandle UPDATE_PROC_THREAD_ATTRIBUTE;
        static final MethodHandle DELETE_PROC_THREAD_ATTRIBUTE_LIST;
        static final MethodHandle CREATE_PROCESS_W;
        static final MethodHandle WAIT_FOR_SINGLE_OBJECT;
        static final MethodHandle GET_EXIT_CODE_PROCESS;
        static final MethodHandle CLOSE_HANDLE;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            CREATE_FILE_W = linker.downcallHandle(kernel32.find("CreateFileW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS),
                    CAPTURE);
            INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST = linker.downcallHandle(
                    kernel32.find("InitializeProcThreadAttributeList").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    CAPTURE);
            UPDATE_PROC_THREAD_ATTRIBUTE = linker.downcallHandle(
                    kernel32.find("UpdateProcThreadAttribute").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            SIZE_T, ValueLayout.ADDRESS, SIZE_T, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    CAPTURE);
            DELETE_PROC_THREAD_ATTRIBUTE_LIST = linker.downcallHandle(
                    kernel32.find("DeleteProcThreadAttributeList").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            CREATE_PROCESS_W = linker.downcallHandle(
                    kernel32.find("CreateProcessW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    CAPTURE);
            WAIT_FOR_SINGLE_OBJECT = linker.downcallHandle(
                    kernel32.find("WaitForSingleObject").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    CAPTURE);
            GET_EXIT_CODE_PROCESS = linker.downcallHandle(
                    kernel32.find("GetExitCodeProcess").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    CAPTURE);
            CLOSE_HANDLE = linker.downcallHandle(
                    kernel32.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    CAPTURE);
        }

        private Functions() {}
    }
}
