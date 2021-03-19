package io.xdag.discovery.peers;

import io.xdag.utils.discoveryutils.bytes.Bytes32;
import io.xdag.utils.discoveryutils.bytes.BytesValue;

import java.io.IOException;

public interface PeerId {
    BytesValue getId();

    /**
     * The Keccak-256 hash value of the peer's ID. The value may be memoized to avoid recomputation
     * overhead.
     *
     * @return The Keccak-256 hash of the peer's ID.
     */
    Bytes32 keccak256() throws IOException;
}
