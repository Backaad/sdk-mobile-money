package io.github.backaad.mobilemoney.spring.redis;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * A Redis server for the tests: {@code REDIS_URL} when set (the CI starts a Redis service), otherwise a
 * {@code redis-server} started on a free port. Without either, the tests are skipped, unless
 * {@code MOBILE_MONEY_REQUIRE_REDIS=true}, which makes them fail instead (set in the CI).
 */
final class RedisServer {

    private static RedisServer shared;

    final String host;
    final int port;
    private final Process process;

    private RedisServer(String host, int port, Process process) {
        this.host = host;
        this.port = port;
        this.process = process;
    }

    static synchronized RedisServer get() {
        if (shared != null) {
            return shared;
        }
        String url = System.getenv("REDIS_URL");
        if (url != null && !url.isBlank()) {
            URI uri = URI.create(url);
            shared = new RedisServer(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379, null);
            return shared;
        }
        String binary = findRedisServer();
        boolean required = "true".equals(System.getenv("MOBILE_MONEY_REQUIRE_REDIS"));
        if (binary == null) {
            if (required) {
                throw new IllegalStateException("MOBILE_MONEY_REQUIRE_REDIS=true but neither REDIS_URL nor"
                        + " redis-server is available");
            }
            assumeTrue(false, "No Redis server available (set REDIS_URL or install redis-server)");
        }
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Process process = new ProcessBuilder(binary, "--port", Integer.toString(port), "--bind", "127.0.0.1",
                    "--save", "", "--appendonly", "no").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            RedisServer server = new RedisServer("127.0.0.1", port, process);
            server.awaitReady();
            shared = server;
            return shared;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start redis-server", e);
        }
    }

    LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private void awaitReady() {
        for (int i = 0; i < 100; i++) {
            try (java.net.Socket socket = new java.net.Socket(host, port)) {
                return;
            } catch (IOException e) {
                if (process != null && !process.isAlive()) {
                    throw new IllegalStateException("redis-server exited");
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        throw new IllegalStateException("redis-server did not start");
    }

    private static String findRedisServer() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        List<String> dirs = Arrays.asList(path.split(java.io.File.pathSeparator));
        for (String dir : dirs) {
            Path candidate = Path.of(dir, "redis-server");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }
}
