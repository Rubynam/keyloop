package com.keyloop.scheduler.batch.service.heartbeat.impl;

import com.keyloop.scheduler.batch.repository.heartbeat.HeartbeatProbe;
import com.keyloop.scheduler.batch.service.heartbeat.HeartbeatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** {@link HeartbeatService} over a {@link HeartbeatProbe}; logs one INFO line per check. */
@Slf4j
@Service
public class HeartbeatServiceImpl implements HeartbeatService {

    private final HeartbeatProbe probe;

    public HeartbeatServiceImpl(HeartbeatProbe probe) {
        this.probe = probe;
    }

    @Override
    public void check() {
        Integer db = probe.pingPostgres();
        String redis = probe.pingRedis();
        log.info("heartbeat ok: postgres={} redis={}", db, redis);
    }
}
