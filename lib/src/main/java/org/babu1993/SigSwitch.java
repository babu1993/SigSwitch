package org.babu1993;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;

public class SigSwitch implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(SigSwitch.class.getName());
    private static final MemorySegment SIG_DFL = MemorySegment.ofAddress(0);
    private static final StructLayout sigAction = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("sa_handler"),
            ValueLayout.JAVA_INT.withName("sa_flags"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_INT).withName("sa_mask")
    );
    private static final VarHandle saHandlerHandleNew = sigAction.varHandle(
            MemoryLayout.PathElement.groupElement("sa_handler"));

    private final Linker nativeLinker;
    private final MethodHandle signalHandle;
    private final Arena arena;
    private final Set<SignalHandlerInfo> registeredSignal;


    public SigSwitch() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (!osName.equals("linux")){
            throw new RuntimeException("This library is only supported on Linux.");
        }
        this.nativeLinker = Linker.nativeLinker();
        SymbolLookup nativeLibC = this.nativeLinker.defaultLookup();
        MemorySegment sigActionAddress = nativeLibC.find("sigaction").orElseThrow(
                () -> new RuntimeException("Failed to find sigaction symbol"));
        FunctionDescriptor sigActionAddressDescriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS);
        MethodHandle rawHandle = this.nativeLinker.downcallHandle(sigActionAddress, sigActionAddressDescriptor);
        this.signalHandle = rawHandle.asType(MethodType.methodType(int.class, int.class,
                MemorySegment.class, MemorySegment.class));
        this.arena = Arena.ofShared();
        this.registeredSignal = new HashSet<>(29);
    }

    private MethodHandle getMethodHandle(Object obj) throws IllegalAccessException, NoSuchMethodException {
        if(obj instanceof SigSwitchHandler){
            return MethodHandles.lookup().findVirtual(obj.getClass(),
                    "signalHandler", MethodType.methodType(void.class, int.class)
            );
        }
        else if(obj instanceof Method){
            return MethodHandles.lookup().unreflect((Method) obj);
        }
        return null;
    }

    private int getDefaultSignal(){
        MemorySegment sigActionOldSegment = this.arena.allocate(sigAction);
        for(int signal=36; signal<65; signal++ ){
            int status=-1;
            if(this.registeredSignal.contains(new SignalHandlerInfo(signal, ""))){
                continue;
            }
            try {
                status = (int)this.signalHandle.invokeExact(signal, MemorySegment.NULL, sigActionOldSegment);
            }
            catch (Throwable t){
                throw new SigSwitchCallException("Failed to register signal handler: " + t.getMessage());
            }
            if(status != 0){
                throw new SigSwitchCallException("Failed to register signal handler: OS returned status " + status);
            }
            MemorySegment previousHandler = (MemorySegment) SigSwitch.saHandlerHandleNew.get(sigActionOldSegment, 0L);
            long addressValue = previousHandler.address();
            if(addressValue == SigSwitch.SIG_DFL.address()) {
                return signal;
            }
        }
        throw new SigSwitchCallException("No registered signal handler found in the range 36-64");
    }
    private SignalHandlerInfo registerHandler(int sigValue, Object handler, Method method) throws
            IllegalAccessException, SigSwitchCallException, NoSuchMethodException {
        MemorySegment sigActionNewSegment = this.arena.allocate(sigAction);
        MethodHandle rawHandle;
        String handlerName;
        if(method == null){
            rawHandle = getMethodHandle(handler);
            handlerName = handler.getClass().getName();
        }
        else {
            rawHandle = getMethodHandle(method);
            handlerName = method.getName();
        }
        if(rawHandle == null){
            throw new SigSwitchCallException("Handler should be an annotated method or Object of SigSwitchHandler");
        }
        MethodHandle boundHandle = rawHandle.bindTo(handler);
        MemorySegment callback = this.nativeLinker.upcallStub(boundHandle,
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT), arena);
        SigSwitch.saHandlerHandleNew.set(sigActionNewSegment, 0L, callback);
        int status = -1;
        try{

            status = (int) this.signalHandle.invokeExact(sigValue, sigActionNewSegment, MemorySegment.NULL);
            logger.info("Signal handler registered for signal: " + sigValue);
        }
        catch(Throwable t){
            throw new SigSwitchCallException("Failed to register signal handler: " + t.getMessage());
        }
        if(status != 0){
            throw new SigSwitchCallException("Failed to register signal handler: OS returned status " + status);
        }
        SignalHandlerInfo signalHandlerInfo = new SignalHandlerInfo(sigValue, handlerName);
        this.registeredSignal.add(signalHandlerInfo);
        return signalHandlerInfo;
    }

    private SignalHandlerInfo registerHandler(int sigValue, Object handler) throws IllegalAccessException,
            SigSwitchCallException, NoSuchMethodException {
        return this.registerHandler(sigValue, handler, null);
    }

    public SignalHandlerInfo registerHandler(SigSwitchHandler handler) throws NoSuchMethodException, IllegalAccessException, SigSwitchCallException {
        int availableSignal = this.getDefaultSignal();
        return registerHandler(availableSignal, handler);
    }
    public void close() {
        for(SignalHandlerInfo signalHandlerInfo : List.copyOf(this.registeredSignal)){
            try {
                unregisterHandler(signalHandlerInfo.signal());
            } catch (SigSwitchCallException e) {
                logger.warning("Failed to unregister signal handler for signal " + signalHandlerInfo.signal() + ": " + e.getMessage());
            }
        }
        this.arena.close();
    }
    public void unregisterHandler(int signal) throws SigSwitchCallException{
        SignalHandlerInfo signalHandlerInfo = new SignalHandlerInfo(signal, "");
        if(!this.registeredSignal.contains(signalHandlerInfo)){
            return;
        }
        MemorySegment sigActionNewSegment = this.arena.allocate(SigSwitch.sigAction);
        SigSwitch.saHandlerHandleNew.set(sigActionNewSegment, 0L, MemorySegment.NULL);
        int status;
        try{
            status = (int)this.signalHandle.invokeExact(signal, sigActionNewSegment, MemorySegment.NULL);
        }
        catch (Throwable t){
            throw new SigSwitchCallException("Failed to unregister signal handler: " + t.getMessage());
        }
        if(status != 0){
            throw new SigSwitchCallException("Failed to unregister signal handler: OS returned status " + status);
        }
        this.registeredSignal.remove(signalHandlerInfo);
    }

    private SignalHandlerInfo registerHandler(Method method, Object obj)
            throws IllegalAccessException, SigSwitchCallException, NoSuchMethodException {
        int availableSignal = this.getDefaultSignal();
        return this.registerHandler(availableSignal, obj, method);
    }

    public List<SignalHandlerInfo> registerHandler(Object obj) throws IllegalAccessException,
            SigSwitchCallException, NoSuchMethodException {
        Method[] methods = obj.getClass().getMethods();
        for(Method method: methods){
            if(method.isAnnotationPresent(SignalHandler.class)){
                SignalHandlerInfo signalHandlerInfo = this.registerHandler(method, obj);
                logger.info("Signal handler registered for signal: " + signalHandlerInfo.signal() +
                        " with handler: " + signalHandlerInfo.name());
            }
        }
        return this.registeredSignal.stream().toList();
    }

}
