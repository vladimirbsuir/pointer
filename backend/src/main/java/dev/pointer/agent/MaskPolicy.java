package dev.pointer.agent;

record MaskPolicy(int keep, int block) {

    static final MaskPolicy NONE = new MaskPolicy(Integer.MAX_VALUE, 1);

    MaskPolicy {
        if (keep < 0 || block < 1) {
            throw new IllegalArgumentException("keep must be at least 0 and block at least 1");
        }
    }

    int boundary(int results) {
        if (results <= keep) {
            return 0;
        }
        return (results - keep) / block * block;
    }
}
