package server.phoestorage.classes;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

public class UploadState {
    public final int totalChunks;
    public final BitSet received;
    public final AtomicInteger receivedCount = new AtomicInteger(0);

    public UploadState(int totalChunks) {
        this.totalChunks = totalChunks;
        this.received = new BitSet(totalChunks);
    }
}