package org.babu1993;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;

public class SigSwitch {

    private static final MemorySegment SIG_ERR = MemorySegment.ofAddress(-1);
    private static final MemorySegment SIG_DFL = MemorySegment.ofAddress(0);
    private static final MemorySegment SIG_IGN = MemorySegment.ofAddress(1);

    private final Linker nativeLinker;
    private final MethodHandle signalHandle;
    private final Arena arena;


    public SigSwitch() throws Throwable {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (!osName.equals("linux")){
            System.out.println("This library is only supported on Linux.");
        }
        nativeLinker = Linker.nativeLinker();
        SymbolLookup nativeLibC = nativeLinker.defaultLookup();
        MemorySegment signalFunctionAddress = nativeLibC.find("signal").orElseThrow(
                () -> new RuntimeException("Failed to find signal symbol"));
        FunctionDescriptor getSignalDescriptor = FunctionDescriptor.of(ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        MethodHandle rawHandle = nativeLinker.downcallHandle(signalFunctionAddress, getSignalDescriptor);
        signalHandle = rawHandle.asType(MethodType.methodType(MemorySegment.class, int.class, MemorySegment.class));
        arena = Arena.ofShared();
    }

    public void registerHandler(int sigValue, SigSwitchHandler handler) throws NoSuchMethodException,
            IllegalAccessException, SigSwitchCallException {
        if(sigValue < 32 || sigValue > 64 ){
            throw new IllegalArgumentException("Signal value must be between 32 and 64");
        }
        MemorySegment previousHandler;
        MethodHandle rawHandle = MethodHandles.lookup().findVirtual(handler.getClass(),
                "signalHandler", MethodType.methodType(void.class, int.class)
                );
        MethodHandle boundHandle = rawHandle.bindTo(handler);
        MemorySegment callback = nativeLinker.upcallStub(boundHandle,
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT), arena);
        try{
            previousHandler = (MemorySegment) this.signalHandle.invokeExact(sigValue, callback);
        }
        catch(Throwable t){
            throw new SigSwitchCallException("Failed to register signal handler: " + t.getMessage());
        }
        if (previousHandler.equals(SIG_ERR)) {
            throw new RuntimeException("Failed to register signal handler: OS returned SIG_ERR");
        } else if (previousHandler.equals(SIG_DFL)) {
            System.out.println("Registration success. Previous handler was the Default Action (SIG_DFL).");
        } else if (previousHandler.equals(SIG_IGN)) {
            System.out.println("Registration success. Previous handler was set to Ignore (SIG_IGN).");
        } else {
            System.out.println("Registration success. Previous handler was a custom function pointer at: " + previousHandler.address());
        }

    }
}
