# SigSwitch

SigSwitch is a small Java library for registering Linux signal handlers from Java using the Foreign Function & Memory API.

## Requirements

- Linux
- JDK 25
- Gradle Wrapper (`./gradlew`)

## Build

```bash
./gradlew build
```

## Test

```bash
./gradlew test
```

## Usage

Add the library module to your project and create a `SigSwitch` instance:

```java
import org.babu1993.SignalHandler;
import org.babu1993.SignalHandlerInfo;
import org.babu1993.SigSwitch;
import org.babu1993.SigSwitchHandler;

try (SigSwitch sigSwitch = new SigSwitch()) {
    SignalHandlerInfo info = sigSwitch.registerHandler((SigSwitchHandler) signal ->
            System.out.println("Received signal: " + signal));

    System.out.println("Registered on signal " + info.signal());
}
```

You can also register methods annotated with `@SignalHandler`:

```java
class MyHandlers {
    @SignalHandler
    public void onSignal(int signal) {
        System.out.println("Handled signal " + signal);
    }
}

try (SigSwitch sigSwitch = new SigSwitch()) {
    sigSwitch.registerHandler(new MyHandlers());
}
```

## API notes

- `SigSwitch` is Linux-only and throws if constructed on another OS.
- Handlers are auto-assigned a signal in the `36-64` range.
- Keep the handler object alive for the entire program lifetime; otherwise the signal may not reach the handler.
- `unregisterHandler(int signal)` removes a registered handler.
- `close()` unregisters all handlers and releases native resources.

## License

See [LICENSE](LICENSE).