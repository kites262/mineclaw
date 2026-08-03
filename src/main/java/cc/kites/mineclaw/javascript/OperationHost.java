package cc.kites.mineclaw.javascript;

/** Per-invocation adapter for Mineclaw approval, command and native-tool operations. */
@FunctionalInterface
public interface OperationHost {
    OperationHandle invoke(OperationCall call);
}
