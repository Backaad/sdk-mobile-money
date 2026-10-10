package io.github.backaad.mobilemoney.spring;

import org.springframework.data.redis.connection.RedisConnectionFactory;

/** Gives the Redis tests access to the startup check. */
public final class MobileMoneyRedisAutoConfigurationAccess {

    private MobileMoneyRedisAutoConfigurationAccess() {
    }

    public static void check(RedisConnectionFactory factory) {
        MobileMoneyRedisAutoConfiguration.checkServer(factory, true);
    }
}
