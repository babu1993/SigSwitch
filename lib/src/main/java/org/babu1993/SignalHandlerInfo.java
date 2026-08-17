package org.babu1993;

public record SignalHandlerInfo(int signal, String name) {
    @Override
    public boolean equals(Object obj) {
        SignalHandlerInfo otherInfo = (SignalHandlerInfo) obj;
        return this.signal == otherInfo.signal;
    }

    @Override
    public int hashCode() {
        return this.signal;
    }
}
