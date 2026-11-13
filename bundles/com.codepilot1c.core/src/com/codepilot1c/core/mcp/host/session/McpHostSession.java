package com.codepilot1c.core.mcp.host.session;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import com.codepilot1c.core.evaluation.trace.AgentTraceSession;

public class McpHostSession {

    private final String sessionId;
    private final Clock clock;
    private final Instant createdAt;
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong activityVersion = new AtomicLong(0);
    private volatile Instant lastActivityAt;
    private volatile String protocolVersion;
    private volatile String clientName;
    private volatile String clientVersion;
    private volatile String transport;
    private volatile String remoteAddress;
    private volatile String lastRequestPath;
    private volatile boolean initialized;
    private volatile AgentTraceSession traceSession;
    private int activeRequests;
    private boolean retired;

    /** Transport admission is short; work and response IO never hold this monitor. */
    public synchronized boolean acquireRequest(){if(retired)return false;activeRequests++;touch();return true;}
    public synchronized void releaseRequest(){if(activeRequests<=0)throw new IllegalStateException("Unbalanced session lease");activeRequests--;touch();}
    public synchronized int activeRequests(){return activeRequests;}
    public synchronized void retire(){retired=true;}
    public synchronized boolean retired(){return retired;}

    public McpHostSession() {
        this(UUID.randomUUID().toString(), Clock.systemUTC());
    }

    public McpHostSession(String sessionId) {
        this(sessionId, Clock.systemUTC());
    }

    /**
     * Creates a session using the supplied clock so its activity and expiry can
     * be evaluated deterministically by the HTTP transport.
     */
    public McpHostSession(String sessionId, Clock clock) {
        this.sessionId = sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.createdAt = this.clock.instant();
        this.lastActivityAt = createdAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the time at which this session last handled a request.
     */
    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    /**
     * Records activity while the caller holds this session's monitor.
     */
    public void touch() {
        lastActivityAt = clock.instant();
        activityVersion.incrementAndGet();
    }

    /**
     * Monotonically changes with every touch, including multiple touches within
     * the same clock tick.
     */
    public long getActivityVersion() {
        return activityVersion.get();
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public String getLastRequestPath() {
        return lastRequestPath;
    }

    public void setLastRequestPath(String lastRequestPath) {
        this.lastRequestPath = lastRequestPath;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public AgentTraceSession getTraceSession() {
        return traceSession;
    }

    public void setTraceSession(AgentTraceSession traceSession) {
        this.traceSession = traceSession;
    }

    public long incrementRequestCount() {
        return requestCount.incrementAndGet();
    }

    public long getRequestCount() {
        return requestCount.get();
    }
}
