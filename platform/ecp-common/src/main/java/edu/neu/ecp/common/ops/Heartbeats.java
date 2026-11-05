package edu.neu.ecp.common.ops;

public interface Heartbeats {
    void success(String service);

    void failure(String service, Throwable error);
}
